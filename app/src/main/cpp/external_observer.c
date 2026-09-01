#define _GNU_SOURCE

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <sched.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define OBS_BUFFER_CAPACITY (4U * 1024U * 1024U)
#define OBS_MAX_PIDS 32
#define OBS_MAX_SEEN_PIDS 96
#define OBS_FAST_INTERVAL_MS 25ULL
#define OBS_SYSTEM_INTERVAL_ACTIVE_MS 200ULL
#define OBS_SYSTEM_INTERVAL_IDLE_MS 500ULL
#define OBS_SLOW_INTERVAL_MS 1000ULL

static pthread_t observer_thread;
static atomic_int observer_running;
static atomic_int observer_target_pid;
static char *observer_buffer;
static size_t observer_length;
static unsigned long observer_dropped;
static char observer_log_path[PATH_MAX];
static int observer_log_fd = -1;
static off_t observer_log_offset;
static char observer_log_partial[1024];
static size_t observer_log_partial_length;
static pid_t observer_seen_pids[OBS_MAX_SEEN_PIDS];
static size_t observer_seen_pid_count;

static uint64_t boottime_ms(void) {
  struct timespec ts = {0};
  if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
    return 0;
  }
  return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
}

static void observer_append(const char *format, ...) {
  if (!observer_buffer || observer_length >= OBS_BUFFER_CAPACITY) {
    observer_dropped++;
    return;
  }

  va_list args;
  va_start(args, format);
  int written = vsnprintf(observer_buffer + observer_length,
                          OBS_BUFFER_CAPACITY - observer_length,
                          format, args);
  va_end(args);
  if (written < 0) {
    observer_dropped++;
    return;
  }
  size_t count = (size_t)written;
  if (count >= OBS_BUFFER_CAPACITY - observer_length) {
    observer_length = OBS_BUFFER_CAPACITY;
    observer_dropped++;
    return;
  }
  observer_length += count;
}

static ssize_t read_text(const char *path, char *buffer, size_t capacity) {
  if (!buffer || capacity < 2) return -1;
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  ssize_t count;
  do {
    count = read(fd, buffer, capacity - 1);
  } while (count < 0 && errno == EINTR);
  close(fd);
  if (count < 0) return -1;
  buffer[count] = '\0';
  return count;
}

static unsigned long long named_value(const char *text, const char *key) {
  if (!text || !key) return 0;
  size_t key_len = strlen(key);
  const char *line = text;
  while (*line) {
    if (strncmp(line, key, key_len) == 0 &&
        (line[key_len] == ':' || line[key_len] == ' ' || line[key_len] == '\t')) {
      const char *p = line + key_len;
      while (*p == ':' || *p == ' ' || *p == '\t') p++;
      errno = 0;
      unsigned long long value = strtoull(p, NULL, 10);
      return errno == 0 ? value : 0;
    }
    const char *next = strchr(line, '\n');
    if (!next) break;
    line = next + 1;
  }
  return 0;
}

static double psi_avg10(const char *path, unsigned long long *total_out) {
  char text[512];
  if (read_text(path, text, sizeof(text)) <= 0) {
    if (total_out) *total_out = 0;
    return -1.0;
  }
  double avg10 = -1.0;
  double avg60 = 0.0;
  double avg300 = 0.0;
  unsigned long long total = 0;
  if (sscanf(text, "some avg10=%lf avg60=%lf avg300=%lf total=%llu",
             &avg10, &avg60, &avg300, &total) < 4) {
    avg10 = -1.0;
    total = 0;
  }
  if (total_out) *total_out = total;
  return avg10;
}

static void sanitize_inline(char *text) {
  if (!text) return;
  for (char *p = text; *p; ++p) {
    if (*p == '\n' || *p == '\r' || *p == '|') *p = ';';
  }
}

static int extract_line_value(const char *text, const char *key,
                              char *out, size_t out_size) {
  if (!text || !key || !out || out_size == 0) return 0;
  size_t key_len = strlen(key);
  const char *line = text;
  while (*line) {
    if (strncmp(line, key, key_len) == 0) {
      const char *value = line + key_len;
      while (*value == ':' || *value == ' ' || *value == '\t') value++;
      const char *end = strchr(value, '\n');
      size_t len = end ? (size_t)(end - value) : strlen(value);
      if (len >= out_size) len = out_size - 1;
      memcpy(out, value, len);
      out[len] = '\0';
      sanitize_inline(out);
      return 1;
    }
    const char *next = strchr(line, '\n');
    if (!next) break;
    line = next + 1;
  }
  return 0;
}

static int pid_seen(pid_t pid) {
  for (size_t i = 0; i < observer_seen_pid_count; ++i) {
    if (observer_seen_pids[i] == pid) return 1;
  }
  if (observer_seen_pid_count < OBS_MAX_SEEN_PIDS) {
    observer_seen_pids[observer_seen_pid_count++] = pid;
  }
  return 0;
}

static void sample_process_metadata(pid_t pid, uint64_t now_ms) {
  char path[128];
  char status[8192] = {0};
  char cgroup[2048] = {0};
  char sched_text[8192] = {0};
  char allowed[128] = "unknown";
  char voluntary[64] = "unknown";
  char involuntary[64] = "unknown";
  char uclamp_min[64] = "unknown";
  char uclamp_max[64] = "unknown";
  char effective_min[64] = "unknown";
  char effective_max[64] = "unknown";

  snprintf(path, sizeof(path), "/proc/%d/status", pid);
  if (read_text(path, status, sizeof(status)) > 0) {
    (void)extract_line_value(status, "Cpus_allowed_list", allowed, sizeof(allowed));
    (void)extract_line_value(status, "voluntary_ctxt_switches", voluntary,
                             sizeof(voluntary));
    (void)extract_line_value(status, "nonvoluntary_ctxt_switches", involuntary,
                             sizeof(involuntary));
  }

  snprintf(path, sizeof(path), "/proc/%d/cgroup", pid);
  if (read_text(path, cgroup, sizeof(cgroup)) <= 0) {
    strcpy(cgroup, "unknown");
  }
  sanitize_inline(cgroup);

  snprintf(path, sizeof(path), "/proc/%d/sched", pid);
  if (read_text(path, sched_text, sizeof(sched_text)) > 0) {
    (void)extract_line_value(sched_text, "uclamp.min", uclamp_min, sizeof(uclamp_min));
    (void)extract_line_value(sched_text, "uclamp.max", uclamp_max, sizeof(uclamp_max));
    (void)extract_line_value(sched_text, "effective uclamp.min", effective_min,
                             sizeof(effective_min));
    (void)extract_line_value(sched_text, "effective uclamp.max", effective_max,
                             sizeof(effective_max));
  }

  observer_append(
      "RMG_OBSERVER_V2|event=proc_meta|t_ms=%llu|pid=%d|cpus=%s|vol=%s|invol=%s|"
      "uclamp_min=%s|uclamp_max=%s|effective_uclamp_min=%s|effective_uclamp_max=%s|"
      "cgroup=%s\n",
      (unsigned long long)now_ms, pid, allowed, voluntary, involuntary,
      uclamp_min, uclamp_max, effective_min, effective_max, cgroup);
}

struct proc_sample {
  pid_t ppid;
  char state;
  unsigned long long utime;
  unsigned long long stime;
  long threads;
  long processor;
  unsigned long long runtime_ns;
  unsigned long long wait_ns;
  unsigned long long slices;
  char comm[48];
};

static int parse_proc_stat(pid_t pid, struct proc_sample *sample) {
  char path[128];
  char text[4096];
  snprintf(path, sizeof(path), "/proc/%d/stat", pid);
  if (read_text(path, text, sizeof(text)) <= 0) return 0;

  char *lparen = strchr(text, '(');
  char *rparen = strrchr(text, ')');
  if (!lparen || !rparen || rparen <= lparen) return 0;
  size_t comm_len = (size_t)(rparen - lparen - 1);
  if (comm_len >= sizeof(sample->comm)) comm_len = sizeof(sample->comm) - 1;
  memcpy(sample->comm, lparen + 1, comm_len);
  sample->comm[comm_len] = '\0';
  sanitize_inline(sample->comm);

  char *p = rparen + 2;
  if (!*p) return 0;
  sample->state = *p;
  p += 2;
  int field = 4;
  while (*p && field <= 39) {
    while (*p == ' ') p++;
    if (!*p) break;
    char *end = NULL;
    errno = 0;
    long long value = strtoll(p, &end, 10);
    if (end == p || errno != 0) break;
    if (field == 4) sample->ppid = (pid_t)value;
    if (field == 14) sample->utime = (unsigned long long)value;
    if (field == 15) sample->stime = (unsigned long long)value;
    if (field == 20) sample->threads = (long)value;
    if (field == 39) sample->processor = (long)value;
    p = end;
    field++;
  }

  snprintf(path, sizeof(path), "/proc/%d/schedstat", pid);
  char schedstat[256];
  if (read_text(path, schedstat, sizeof(schedstat)) > 0) {
    (void)sscanf(schedstat, "%llu %llu %llu", &sample->runtime_ns,
                 &sample->wait_ns, &sample->slices);
  }
  return field > 20;
}

static int pid_in_list(const pid_t *pids, size_t count, pid_t pid) {
  for (size_t i = 0; i < count; ++i) {
    if (pids[i] == pid) return 1;
  }
  return 0;
}

static size_t collect_process_tree(pid_t root, pid_t *pids, size_t capacity) {
  if (root <= 0 || capacity == 0) return 0;
  size_t count = 1;
  pids[0] = root;
  for (size_t index = 0; index < count && count < capacity; ++index) {
    pid_t parent = pids[index];
    char path[128];
    char children[2048];
    snprintf(path, sizeof(path), "/proc/%d/task/%d/children", parent, parent);
    if (read_text(path, children, sizeof(children)) <= 0) continue;
    char *p = children;
    while (*p && count < capacity) {
      while (*p == ' ' || *p == '\t' || *p == '\n') p++;
      if (!*p) break;
      char *end = NULL;
      long value = strtol(p, &end, 10);
      if (end == p) break;
      if (value > 0 && !pid_in_list(pids, count, (pid_t)value)) {
        pids[count++] = (pid_t)value;
      }
      p = end;
    }
  }
  return count;
}

static void sample_process_tree(pid_t root, uint64_t now_ms) {
  pid_t pids[OBS_MAX_PIDS];
  size_t count = collect_process_tree(root, pids, OBS_MAX_PIDS);
  observer_append("RMG_OBSERVER_V2|event=tree|t_ms=%llu|root=%d|count=%zu\n",
                  (unsigned long long)now_ms, root, count);
  for (size_t i = 0; i < count; ++i) {
    struct proc_sample sample;
    memset(&sample, 0, sizeof(sample));
    sample.processor = -1;
    if (!parse_proc_stat(pids[i], &sample)) continue;
    if (!pid_seen(pids[i])) sample_process_metadata(pids[i], now_ms);
    observer_append(
        "RMG_OBSERVER_V2|event=proc|t_ms=%llu|pid=%d|ppid=%d|comm=%s|state=%c|"
        "cpu=%ld|threads=%ld|utime=%llu|stime=%llu|runtime_ns=%llu|wait_ns=%llu|slices=%llu\n",
        (unsigned long long)now_ms, pids[i], sample.ppid, sample.comm,
        sample.state ? sample.state : '?', sample.processor, sample.threads,
        sample.utime, sample.stime, sample.runtime_ns, sample.wait_ns,
        sample.slices);
  }
}

static void sample_system(uint64_t now_ms) {
  unsigned long long cpu_psi_total = 0;
  unsigned long long mem_psi_total = 0;
  unsigned long long io_psi_total = 0;
  double cpu_psi = psi_avg10("/proc/pressure/cpu", &cpu_psi_total);
  double mem_psi = psi_avg10("/proc/pressure/memory", &mem_psi_total);
  double io_psi = psi_avg10("/proc/pressure/io", &io_psi_total);

  char loadavg[256] = {0};
  double load1 = -1.0, load5 = -1.0, load15 = -1.0;
  int runnable = -1, tasks = -1;
  if (read_text("/proc/loadavg", loadavg, sizeof(loadavg)) > 0) {
    (void)sscanf(loadavg, "%lf %lf %lf %d/%d", &load1, &load5, &load15,
                 &runnable, &tasks);
  }

  char meminfo[8192] = {0};
  (void)read_text("/proc/meminfo", meminfo, sizeof(meminfo));
  unsigned long long mem_available = named_value(meminfo, "MemAvailable");
  unsigned long long slab = named_value(meminfo, "Slab");
  unsigned long long sreclaim = named_value(meminfo, "SReclaimable");
  unsigned long long sunreclaim = named_value(meminfo, "SUnreclaim");
  unsigned long long anon = named_value(meminfo, "AnonPages");
  unsigned long long pagetables = named_value(meminfo, "PageTables");

  char stat[512] = {0};
  unsigned long long user = 0, nice = 0, system = 0, idle = 0, iowait = 0;
  unsigned long long irq = 0, softirq = 0, steal = 0;
  if (read_text("/proc/stat", stat, sizeof(stat)) > 0) {
    (void)sscanf(stat, "cpu %llu %llu %llu %llu %llu %llu %llu %llu",
                 &user, &nice, &system, &idle, &iowait, &irq, &softirq, &steal);
  }

  observer_append(
      "RMG_OBSERVER_V2|event=system|t_ms=%llu|load1=%.2f|load5=%.2f|load15=%.2f|"
      "runnable=%d|tasks=%d|psi_cpu10=%.2f|psi_cpu_total=%llu|psi_mem10=%.2f|"
      "psi_mem_total=%llu|psi_io10=%.2f|psi_io_total=%llu|memavail_kb=%llu|"
      "slab_kb=%llu|sreclaim_kb=%llu|sunreclaim_kb=%llu|anon_kb=%llu|pagetables_kb=%llu|"
      "cpu_user=%llu|cpu_nice=%llu|cpu_system=%llu|cpu_idle=%llu|cpu_iowait=%llu|"
      "cpu_irq=%llu|cpu_softirq=%llu|cpu_steal=%llu\n",
      (unsigned long long)now_ms, load1, load5, load15, runnable, tasks,
      cpu_psi, cpu_psi_total, mem_psi, mem_psi_total, io_psi, io_psi_total,
      mem_available, slab, sreclaim, sunreclaim, anon, pagetables,
      user, nice, system, idle, iowait, irq, softirq, steal);
}

static unsigned long long vmstat_value(const char *text, const char *key) {
  return named_value(text, key);
}

static void sample_buddy(unsigned long long *orders, size_t order_count) {
  char text[8192];
  memset(orders, 0, order_count * sizeof(*orders));
  if (read_text("/proc/buddyinfo", text, sizeof(text)) <= 0) return;
  char *save_line = NULL;
  for (char *line = strtok_r(text, "\n", &save_line); line;
       line = strtok_r(NULL, "\n", &save_line)) {
    char *zone = strstr(line, "zone");
    if (!zone) continue;
    char *p = zone + 4;
    while (*p == ' ' || *p == '\t') p++;
    while (*p && *p != ' ' && *p != '\t') p++;
    size_t order = 0;
    while (*p && order < order_count) {
      while (*p == ' ' || *p == '\t') p++;
      if (!*p) break;
      char *end = NULL;
      unsigned long long value = strtoull(p, &end, 10);
      if (end == p) break;
      orders[order++] += value;
      p = end;
    }
  }
}

static void sample_slow(uint64_t now_ms) {
  char vmstat[32768] = {0};
  (void)read_text("/proc/vmstat", vmstat, sizeof(vmstat));
  unsigned long long pgfault = vmstat_value(vmstat, "pgfault");
  unsigned long long pgmajfault = vmstat_value(vmstat, "pgmajfault");
  unsigned long long pgscan_kswapd = vmstat_value(vmstat, "pgscan_kswapd");
  unsigned long long pgscan_direct = vmstat_value(vmstat, "pgscan_direct");
  unsigned long long pgsteal_kswapd = vmstat_value(vmstat, "pgsteal_kswapd");
  unsigned long long pgsteal_direct = vmstat_value(vmstat, "pgsteal_direct");
  unsigned long long allocstall = vmstat_value(vmstat, "allocstall");
  unsigned long long compact_stall = vmstat_value(vmstat, "compact_stall");

  unsigned long long buddy[11];
  sample_buddy(buddy, sizeof(buddy) / sizeof(buddy[0]));

  char frequencies[384] = {0};
  size_t used = 0;
  long cpus = sysconf(_SC_NPROCESSORS_CONF);
  if (cpus < 0) cpus = 0;
  if (cpus > 16) cpus = 16;
  for (long cpu = 0; cpu < cpus; ++cpu) {
    char path[160];
    char value[64] = {0};
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_cur_freq", cpu);
    unsigned long long freq = 0;
    if (read_text(path, value, sizeof(value)) > 0) freq = strtoull(value, NULL, 10);
    int n = snprintf(frequencies + used, sizeof(frequencies) - used,
                     "%s%llu", cpu ? "," : "",
                     (unsigned long long)freq);
    if (n < 0 || (size_t)n >= sizeof(frequencies) - used) break;
    used += (size_t)n;
  }

  observer_append(
      "RMG_OBSERVER_V2|event=vm|t_ms=%llu|pgfault=%llu|pgmajfault=%llu|"
      "pgscan_kswapd=%llu|pgscan_direct=%llu|pgsteal_kswapd=%llu|pgsteal_direct=%llu|"
      "allocstall=%llu|compact_stall=%llu|buddy_o0=%llu|buddy_o1=%llu|buddy_o2=%llu|"
      "buddy_o3=%llu|buddy_o4=%llu|buddy_o5=%llu|buddy_o6=%llu|buddy_o7=%llu|"
      "buddy_o8=%llu|buddy_o9=%llu|buddy_o10=%llu|freq_khz=%s\n",
      (unsigned long long)now_ms, pgfault, pgmajfault, pgscan_kswapd,
      pgscan_direct, pgsteal_kswapd, pgsteal_direct, allocstall, compact_stall,
      buddy[0], buddy[1], buddy[2], buddy[3], buddy[4], buddy[5], buddy[6],
      buddy[7], buddy[8], buddy[9], buddy[10], frequencies[0] ? frequencies : "unknown");
}

static const char *classify_marker(const char *line) {
  if (strstr(line, "exploit attempt=")) return "attempt_begin";
  if (strstr(line, "slide source mode=p0")) return "p0_begin";
  if (strstr(line, "p0 pipe oracle prepared")) return "p0_oracle_ready";
  if (strstr(line, "slide app bank selected") || strstr(line, "slide app stage=trigger"))
    return "race_route";
  if (strstr(line, "p0 physical write")) return "writer_result";
  if (strstr(line, "app fops stage=trigger-return")) return "fops_trigger_result";
  if (strstr(line, "pipe caches")) return "physrw_begin";
  if (strstr(line, "slide kaslr leak failed")) return "p0_failed";
  if (strstr(line, "writer route outcome")) return "unsafe_stop";
  if (strstr(line, "exploit completed attempt=")) return "exploit_success";
  return NULL;
}

static void record_log_line(const char *line, uint64_t now_ms) {
  const char *marker = classify_marker(line);
  if (!marker) return;
  char copy[256];
  size_t len = strlen(line);
  if (len >= sizeof(copy)) len = sizeof(copy) - 1;
  memcpy(copy, line, len);
  copy[len] = '\0';
  sanitize_inline(copy);
  observer_append("RMG_OBSERVER_V2|event=marker|t_ms=%llu|name=%s|line=%s\n",
                  (unsigned long long)now_ms, marker, copy);
}

static void tail_payload_log(uint64_t now_ms) {
  if (observer_log_fd < 0) return;
  struct stat st;
  if (fstat(observer_log_fd, &st) == 0 && st.st_size < observer_log_offset) {
    observer_log_offset = 0;
    observer_log_partial_length = 0;
  }

  char chunk[2048];
  for (;;) {
    ssize_t count = pread(observer_log_fd, chunk, sizeof(chunk), observer_log_offset);
    if (count <= 0) break;
    observer_log_offset += count;
    for (ssize_t i = 0; i < count; ++i) {
      char ch = chunk[i];
      if (ch == '\n') {
        observer_log_partial[observer_log_partial_length] = '\0';
        record_log_line(observer_log_partial, now_ms);
        observer_log_partial_length = 0;
      } else if (ch != '\r') {
        if (observer_log_partial_length + 1 < sizeof(observer_log_partial)) {
          observer_log_partial[observer_log_partial_length++] = ch;
        } else {
          observer_log_partial_length = 0;
        }
      }
    }
  }
}

static void observer_pin_away_from_exploit(void) {
  (void)setpriority(PRIO_PROCESS, 0, 10);
  long cpus = sysconf(_SC_NPROCESSORS_ONLN);
  if (cpus <= 2) return;
  cpu_set_t set;
  CPU_ZERO(&set);
  long selected = cpus - 1;
  if (selected >= CPU_SETSIZE) selected = CPU_SETSIZE - 1;
  CPU_SET((int)selected, &set);
  (void)sched_setaffinity(0, sizeof(set), &set);
}

static void *observer_main(void *unused) {
  (void)unused;
  observer_pin_away_from_exploit();
  uint64_t last_system = 0;
  uint64_t last_slow = 0;
  int last_target = -1;

  while (atomic_load_explicit(&observer_running, memory_order_relaxed)) {
    uint64_t now_ms = boottime_ms();
    int target = atomic_load_explicit(&observer_target_pid, memory_order_relaxed);
    if (target != last_target) {
      observer_append("RMG_OBSERVER_V2|event=target|t_ms=%llu|pid=%d\n",
                      (unsigned long long)now_ms, target);
      last_target = target;
    }

    tail_payload_log(now_ms);
    if (target > 0) sample_process_tree((pid_t)target, now_ms);

    uint64_t system_interval = target > 0 ? OBS_SYSTEM_INTERVAL_ACTIVE_MS
                                          : OBS_SYSTEM_INTERVAL_IDLE_MS;
    if (last_system == 0 || now_ms - last_system >= system_interval) {
      sample_system(now_ms);
      last_system = now_ms;
    }
    if (last_slow == 0 || now_ms - last_slow >= OBS_SLOW_INTERVAL_MS) {
      sample_slow(now_ms);
      last_slow = now_ms;
    }

    struct timespec sleep_for = {
        .tv_sec = 0,
        .tv_nsec = (long)(target > 0 ? OBS_FAST_INTERVAL_MS : 100ULL) * 1000000L,
    };
    while (nanosleep(&sleep_for, &sleep_for) < 0 && errno == EINTR) {
    }
  }
  tail_payload_log(boottime_ms());
  return NULL;
}

static void observer_reset(void) {
  if (observer_log_fd >= 0) {
    close(observer_log_fd);
    observer_log_fd = -1;
  }
  free(observer_buffer);
  observer_buffer = NULL;
  observer_length = 0;
  observer_dropped = 0;
  observer_log_path[0] = '\0';
  observer_log_offset = 0;
  observer_log_partial_length = 0;
  observer_seen_pid_count = 0;
  atomic_store(&observer_target_pid, 0);
}

JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_observerStart(JNIEnv *env, jobject thiz,
                                                    jstring log_path) {
  (void)thiz;
  if (atomic_load(&observer_running)) return JNI_TRUE;
  observer_reset();
  observer_buffer = malloc(OBS_BUFFER_CAPACITY);
  if (!observer_buffer) return JNI_FALSE;
  observer_buffer[0] = '\0';

  if (log_path) {
    const char *path = (*env)->GetStringUTFChars(env, log_path, NULL);
    if (path) {
      snprintf(observer_log_path, sizeof(observer_log_path), "%s", path);
      (*env)->ReleaseStringUTFChars(env, log_path, path);
      observer_log_fd = open(observer_log_path, O_RDONLY | O_CLOEXEC);
    }
  }

  observer_append(
      "RMG_OBSERVER_V2|event=start|t_ms=%llu|observer_pid=%d|fast_ms=%llu|system_ms=%llu|slow_ms=%llu|"
      "log_tail=%d|buffer_bytes=%u\n",
      (unsigned long long)boottime_ms(), getpid(),
      (unsigned long long)OBS_FAST_INTERVAL_MS,
      (unsigned long long)OBS_SYSTEM_INTERVAL_ACTIVE_MS,
      (unsigned long long)OBS_SLOW_INTERVAL_MS,
      observer_log_fd >= 0, OBS_BUFFER_CAPACITY);
  atomic_store(&observer_running, 1);
  if (pthread_create(&observer_thread, NULL, observer_main, NULL) != 0) {
    atomic_store(&observer_running, 0);
    observer_reset();
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_observerAttachPid(JNIEnv *env, jobject thiz,
                                                       jlong pid) {
  (void)env;
  (void)thiz;
  if (!atomic_load(&observer_running) || pid <= 0 || pid > INT_MAX) return JNI_FALSE;
  atomic_store_explicit(&observer_target_pid, (int)pid, memory_order_relaxed);
  return JNI_TRUE;
}

static int write_all(int fd, const char *data, size_t length) {
  size_t written = 0;
  while (written < length) {
    ssize_t count = write(fd, data + written, length - written);
    if (count < 0 && errno == EINTR) continue;
    if (count <= 0) return 0;
    written += (size_t)count;
  }
  return 1;
}

JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_observerStop(JNIEnv *env, jobject thiz,
                                                  jstring output_path) {
  (void)thiz;
  if (!output_path) return JNI_FALSE;
  if (atomic_exchange(&observer_running, 0)) {
    (void)pthread_join(observer_thread, NULL);
  }
  if (!observer_buffer) return JNI_FALSE;
  observer_append("RMG_OBSERVER_V2|event=stop|t_ms=%llu|dropped=%lu|bytes=%zu\n",
                  (unsigned long long)boottime_ms(), observer_dropped,
                  observer_length);

  const char *path = (*env)->GetStringUTFChars(env, output_path, NULL);
  if (!path) {
    observer_reset();
    return JNI_FALSE;
  }
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  int ok = fd >= 0 && write_all(fd, observer_buffer, observer_length);
  if (fd >= 0) close(fd);
  (*env)->ReleaseStringUTFChars(env, output_path, path);
  observer_reset();
  return ok ? JNI_TRUE : JNI_FALSE;
}
