#define _GNU_SOURCE

#include <jni.h>

#include <dirent.h>
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
#define OBS_MAX_PIDS 96
#define OBS_MAX_SEEN_PIDS 256
#define OBS_MAX_TRACKED_PIDS 64
#define OBS_ROLE_CAPACITY 24
#define OBS_MAX_CPUS 16
#define OBS_POLL_INTERVAL_MS 25ULL
#define OBS_PROCESS_INTERVAL_BURST_MS 25ULL
#define OBS_PROCESS_INTERVAL_IDLE_MS 250ULL
#define OBS_SYSTEM_INTERVAL_BURST_MS 200ULL
#define OBS_SYSTEM_INTERVAL_IDLE_MS 500ULL
#define OBS_SLOW_INTERVAL_MS 1000ULL
#define OBS_BURST_WINDOW_MS 2000ULL

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
struct observer_process_identity {
  pid_t pid;
  unsigned long long starttime_ticks;
};
static struct observer_process_identity observer_seen_identities[OBS_MAX_SEEN_PIDS];
static size_t observer_seen_identity_count;
struct observer_tracked_pid {
  pid_t pid;
  unsigned long long starttime_ticks;
  uint64_t discovered_ms;
  char role[OBS_ROLE_CAPACITY];
};
static struct observer_tracked_pid observer_tracked_pids[OBS_MAX_TRACKED_PIDS];
static size_t observer_tracked_pid_count;
static pthread_mutex_t observer_pid_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_ullong observer_burst_until_ms;

struct observer_capabilities {
  int loadavg;
  int psi_cpu;
  int psi_mem;
  int psi_io;
  int meminfo;
  int proc_stat;
  int vmstat;
  int buddyinfo;
  long cpu_count;
  int cpufreq[OBS_MAX_CPUS];
};

static struct observer_capabilities observer_caps;

static uint64_t boottime_ms(void) {
  struct timespec ts = {0};
  if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) return 0;
  return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
}

static pthread_mutex_t observer_buffer_mutex = PTHREAD_MUTEX_INITIALIZER;

static void observer_append(const char *format, ...) {
  pthread_mutex_lock(&observer_buffer_mutex);
  if (!observer_buffer || observer_length >= OBS_BUFFER_CAPACITY) {
    observer_dropped++;
    pthread_mutex_unlock(&observer_buffer_mutex);
    return;
  }
  va_list args;
  va_start(args, format);
  int written = vsnprintf(observer_buffer + observer_length,
                          OBS_BUFFER_CAPACITY - observer_length, format, args);
  va_end(args);
  if (written < 0) {
    observer_dropped++;
    pthread_mutex_unlock(&observer_buffer_mutex);
    return;
  }
  size_t count = (size_t)written;
  if (count >= OBS_BUFFER_CAPACITY - observer_length) {
    observer_length = OBS_BUFFER_CAPACITY;
    observer_dropped++;
    pthread_mutex_unlock(&observer_buffer_mutex);
    return;
  }
  observer_length += count;
  pthread_mutex_unlock(&observer_buffer_mutex);
}

static ssize_t read_text(const char *path, char *buffer, size_t capacity) {
  if (!buffer || capacity < 2) return -1;
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  ssize_t count;
  do {
    count = read(fd, buffer, capacity - 1);
  } while (count < 0 && errno == EINTR);
  int saved_errno = errno;
  close(fd);
  errno = saved_errno;
  if (count < 0) return -1;
  buffer[count] = '\0';
  return count;
}

static int probe_readable(const char *path) {
  char text[64];
  return read_text(path, text, sizeof(text)) >= 0;
}

static int named_value_checked(const char *text, const char *key,
                               unsigned long long *value_out) {
  if (!text || !key || !value_out) return 0;
  size_t key_len = strlen(key);
  const char *line = text;
  while (*line) {
    if (strncmp(line, key, key_len) == 0 &&
        (line[key_len] == ':' || line[key_len] == ' ' || line[key_len] == '\t')) {
      const char *p = line + key_len;
      while (*p == ':' || *p == ' ' || *p == '\t') p++;
      errno = 0;
      char *end = NULL;
      unsigned long long value = strtoull(p, &end, 10);
      if (errno != 0 || end == p) return 0;
      *value_out = value;
      return 1;
    }
    const char *next = strchr(line, '\n');
    if (!next) break;
    line = next + 1;
  }
  return 0;
}

static void format_u64(char *out, size_t size, int available,
                       unsigned long long value) {
  if (!out || size == 0) return;
  if (!available) snprintf(out, size, "NA");
  else snprintf(out, size, "%llu", value);
}

static void format_int(char *out, size_t size, int available, int value) {
  if (!out || size == 0) return;
  if (!available) snprintf(out, size, "NA");
  else snprintf(out, size, "%d", value);
}

static void format_double(char *out, size_t size, int available, double value) {
  if (!out || size == 0) return;
  if (!available) snprintf(out, size, "NA");
  else snprintf(out, size, "%.2f", value);
}

static int psi_read(const char *path, double *avg10_out,
                    unsigned long long *total_out) {
  char text[512];
  if (read_text(path, text, sizeof(text)) <= 0) return 0;
  double avg10 = 0.0, avg60 = 0.0, avg300 = 0.0;
  unsigned long long total = 0;
  if (sscanf(text, "some avg10=%lf avg60=%lf avg300=%lf total=%llu",
             &avg10, &avg60, &avg300, &total) < 4) return 0;
  *avg10_out = avg10;
  *total_out = total;
  return 1;
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

static int read_proc_starttime(pid_t pid, unsigned long long *starttime_ticks) {
  if (pid <= 0 || !starttime_ticks) return 0;
  char path[128], text[4096];
  snprintf(path, sizeof(path), "/proc/%d/stat", pid);
  if (read_text(path, text, sizeof(text)) <= 0) return 0;
  char *rparen = strrchr(text, ')');
  if (!rparen) return 0;
  char *p = rparen + 2;
  if (!*p) return 0;
  p += 2;
  int field = 4;
  while (*p && field <= 22) {
    while (*p == ' ') p++;
    if (!*p) break;
    char *token_end = p;
    while (*token_end && *token_end != ' ') token_end++;
    if (field == 22) {
      errno = 0;
      char *parsed_end = NULL;
      unsigned long long value = strtoull(p, &parsed_end, 10);
      if (errno != 0 || parsed_end != token_end || value == 0) return 0;
      *starttime_ticks = value;
      return 1;
    }
    p = token_end;
    field++;
  }
  return 0;
}

static int identity_seen(pid_t pid, unsigned long long starttime_ticks) {
  if (pid <= 0 || starttime_ticks == 0) return 0;
  for (size_t i = 0; i < observer_seen_identity_count; ++i) {
    if (observer_seen_identities[i].pid == pid &&
        observer_seen_identities[i].starttime_ticks == starttime_ticks)
      return 1;
  }
  if (observer_seen_identity_count < OBS_MAX_SEEN_PIDS) {
    observer_seen_identities[observer_seen_identity_count].pid = pid;
    observer_seen_identities[observer_seen_identity_count].starttime_ticks = starttime_ticks;
    observer_seen_identity_count++;
  }
  return 0;
}

static void observer_track_pid(pid_t pid, const char *role, uint64_t now_ms) {
  if (pid <= 0 || !role || !*role) return;
  unsigned long long starttime_ticks = 0;
  int identity_ok = read_proc_starttime(pid, &starttime_ticks);
  if (!identity_ok) {
    observer_append("RMG_OBSERVER_V2|event=pid_discovered|t_ms=%llu|role=%s|pid=%d|starttime_ticks=NA|identity_ok=0|source=marker\n",
        (unsigned long long)now_ms, role, pid);
    return;
  }

  pthread_mutex_lock(&observer_pid_mutex);
  for (size_t i = 0; i < observer_tracked_pid_count; ++i) {
    if (observer_tracked_pids[i].pid == pid &&
        observer_tracked_pids[i].starttime_ticks == starttime_ticks &&
        strcmp(observer_tracked_pids[i].role, role) == 0) {
      pthread_mutex_unlock(&observer_pid_mutex);
      return;
    }
  }
  for (size_t i = 0; i < observer_tracked_pid_count; ++i) {
    if (observer_tracked_pids[i].pid != pid) continue;
    if (observer_tracked_pids[i].starttime_ticks == starttime_ticks) continue;
    observer_tracked_pids[i].starttime_ticks = starttime_ticks;
    observer_tracked_pids[i].discovered_ms = now_ms;
    snprintf(observer_tracked_pids[i].role,
             sizeof(observer_tracked_pids[i].role), "%s", role);
    pthread_mutex_unlock(&observer_pid_mutex);
    observer_append("RMG_OBSERVER_V2|event=pid_discovered|t_ms=%llu|role=%s|pid=%d|starttime_ticks=%llu|identity_ok=1|source=marker\n",
        (unsigned long long)now_ms, role, pid, starttime_ticks);
    return;
  }
  if (observer_tracked_pid_count < OBS_MAX_TRACKED_PIDS) {
    struct observer_tracked_pid *slot =
        &observer_tracked_pids[observer_tracked_pid_count++];
    slot->pid = pid;
    slot->starttime_ticks = starttime_ticks;
    slot->discovered_ms = now_ms;
    snprintf(slot->role, sizeof(slot->role), "%s", role);
  }
  pthread_mutex_unlock(&observer_pid_mutex);
  observer_append("RMG_OBSERVER_V2|event=pid_discovered|t_ms=%llu|role=%s|pid=%d|starttime_ticks=%llu|identity_ok=1|source=marker\n",
      (unsigned long long)now_ms, role, pid, starttime_ticks);
}

static size_t observer_snapshot_tracked(struct observer_tracked_pid *out,
                                        size_t capacity) {
  if (!out || capacity == 0) return 0;
  pthread_mutex_lock(&observer_pid_mutex);
  size_t count = observer_tracked_pid_count;
  if (count > capacity) count = capacity;
  memcpy(out, observer_tracked_pids, count * sizeof(*out));
  pthread_mutex_unlock(&observer_pid_mutex);
  return count;
}

static void observer_forget_tracked_identity(pid_t pid,
                                             unsigned long long starttime_ticks) {
  if (pid <= 0 || starttime_ticks == 0) return;
  pthread_mutex_lock(&observer_pid_mutex);
  for (size_t i = 0; i < observer_tracked_pid_count;) {
    if (observer_tracked_pids[i].pid != pid ||
        observer_tracked_pids[i].starttime_ticks != starttime_ticks) {
      i++;
      continue;
    }
    if (i + 1 < observer_tracked_pid_count) {
      memmove(&observer_tracked_pids[i], &observer_tracked_pids[i + 1],
              (observer_tracked_pid_count - i - 1) * sizeof(observer_tracked_pids[0]));
    }
    observer_tracked_pid_count--;
    memset(&observer_tracked_pids[observer_tracked_pid_count], 0,
           sizeof(observer_tracked_pids[0]));
  }
  pthread_mutex_unlock(&observer_pid_mutex);
}

static pid_t parse_marker_pid_after(const char *line, const char *token) {
  if (!line || !token) return 0;
  const char *p = strstr(line, token);
  if (!p) return 0;
  p += strlen(token);
  errno = 0;
  char *end = NULL;
  long value = strtol(p, &end, 10);
  if (errno != 0 || end == p || value <= 0 || value > INT_MAX) return 0;
  return (pid_t)value;
}

static void track_marker_processes(const char *line, uint64_t now_ms) {
  pid_t pid = parse_marker_pid_after(line, "preload supervisor pid=");
  if (pid > 0) {
    observer_track_pid(pid, "supervisor", now_ms);
    return;
  }
  if (strstr(line, "[+] exploit attempt=")) {
    pid = parse_marker_pid_after(line, " pid=");
    if (pid > 0) {
      observer_track_pid(pid, "attempt", now_ms);
      return;
    }
  }
  if (strstr(line, "slide child context")) {
    pid = parse_marker_pid_after(line, " pid=");
    if (pid > 0) observer_track_pid(pid, "slide_child", now_ms);
  }
}

static void sample_process_metadata(pid_t pid, uint64_t now_ms) {
  char path[128], status[8192] = {0}, cgroup[2048] = {0}, sched_text[8192] = {0};
  char allowed[128] = "NA", voluntary[64] = "NA", involuntary[64] = "NA";
  char uclamp_min[64] = "NA", uclamp_max[64] = "NA";
  char effective_min[64] = "NA", effective_max[64] = "NA";
  snprintf(path, sizeof(path), "/proc/%d/status", pid);
  int status_ok = read_text(path, status, sizeof(status)) > 0;
  if (status_ok) {
    (void)extract_line_value(status, "Cpus_allowed_list", allowed, sizeof(allowed));
    (void)extract_line_value(status, "voluntary_ctxt_switches", voluntary, sizeof(voluntary));
    (void)extract_line_value(status, "nonvoluntary_ctxt_switches", involuntary, sizeof(involuntary));
  }
  snprintf(path, sizeof(path), "/proc/%d/cgroup", pid);
  int cgroup_ok = read_text(path, cgroup, sizeof(cgroup)) > 0;
  if (!cgroup_ok) strcpy(cgroup, "NA");
  sanitize_inline(cgroup);
  snprintf(path, sizeof(path), "/proc/%d/sched", pid);
  int sched_ok = read_text(path, sched_text, sizeof(sched_text)) > 0;
  if (sched_ok) {
    (void)extract_line_value(sched_text, "uclamp.min", uclamp_min, sizeof(uclamp_min));
    (void)extract_line_value(sched_text, "uclamp.max", uclamp_max, sizeof(uclamp_max));
    (void)extract_line_value(sched_text, "effective uclamp.min", effective_min, sizeof(effective_min));
    (void)extract_line_value(sched_text, "effective uclamp.max", effective_max, sizeof(effective_max));
  }
  observer_append("RMG_OBSERVER_V2|event=proc_meta|t_ms=%llu|pid=%d|status_access=%d|sched_access=%d|cgroup_access=%d|cpus=%s|vol=%s|invol=%s|uclamp_min=%s|uclamp_max=%s|effective_uclamp_min=%s|effective_uclamp_max=%s|cgroup=%s\n",
      (unsigned long long)now_ms, pid, status_ok, sched_ok, cgroup_ok, allowed,
      voluntary, involuntary, uclamp_min, uclamp_max, effective_min, effective_max, cgroup);
}

struct proc_sample {
  pid_t ppid;
  char state;
  unsigned long long utime, stime, starttime_ticks;
  long threads, processor;
  unsigned long long runtime_ns, wait_ns, slices;
  int schedstat_ok, processor_ok;
  char comm[48];
};

static int parse_proc_stat(pid_t pid, struct proc_sample *sample) {
  char path[128], text[4096];
  sample->processor = -1;
  sample->processor_ok = 0;
  sample->starttime_ticks = 0;
  snprintf(path, sizeof(path), "/proc/%d/stat", pid);
  if (read_text(path, text, sizeof(text)) <= 0) return 0;
  char *lparen = strchr(text, '('), *rparen = strrchr(text, ')');
  if (!lparen || !rparen || rparen <= lparen) return 0;
  size_t comm_len = (size_t)(rparen - lparen - 1);
  if (comm_len >= sizeof(sample->comm)) comm_len = sizeof(sample->comm) - 1;
  memcpy(sample->comm, lparen + 1, comm_len); sample->comm[comm_len] = '\0';
  sanitize_inline(sample->comm);
  char *p = rparen + 2;
  if (!*p) return 0;
  sample->state = *p;
  p += 2;
  int field = 4;
  while (*p && field <= 39) {
    while (*p == ' ') p++;
    if (!*p) break;
    char *token_end = p;
    while (*token_end && *token_end != ' ') token_end++;
    char *parsed_end = NULL;
    errno = 0;
    if (field == 4 || field == 20 || field == 39) {
      long long value = strtoll(p, &parsed_end, 10);
      int parsed = errno == 0 && parsed_end == token_end;
      if (!parsed && field <= 20) return 0;
      if (parsed && field == 4) sample->ppid = (pid_t)value;
      if (parsed && field == 20) sample->threads = (long)value;
      if (parsed && field == 39) {
        sample->processor = (long)value;
        sample->processor_ok = value >= 0;
      }
    } else if (field == 14 || field == 15 || field == 22) {
      unsigned long long value = strtoull(p, &parsed_end, 10);
      if (errno != 0 || parsed_end != token_end) return 0;
      if (field == 14) sample->utime = value;
      else if (field == 15) sample->stime = value;
      else sample->starttime_ticks = value;
    }
    p = token_end;
    field++;
  }
  if (sample->starttime_ticks == 0) return 0;
  snprintf(path, sizeof(path), "/proc/%d/schedstat", pid);
  char schedstat[256];
  if (read_text(path, schedstat, sizeof(schedstat)) > 0 &&
      sscanf(schedstat, "%llu %llu %llu", &sample->runtime_ns, &sample->wait_ns, &sample->slices) == 3)
    sample->schedstat_ok = 1;
  return field > 22;
}

static int pid_in_list(const pid_t *pids, size_t count, pid_t pid) {
  for (size_t i = 0; i < count; ++i) if (pids[i] == pid) return 1;
  return 0;
}

static void append_children_text(char *children, pid_t *pids, size_t *count, size_t capacity) {
  char *p = children;
  while (*p && *count < capacity) {
    while (*p == ' ' || *p == '\t' || *p == '\n') p++;
    if (!*p) break;
    char *end = NULL; errno = 0; long value = strtol(p, &end, 10);
    if (end == p || errno != 0) break;
    if (value > 0 && !pid_in_list(pids, *count, (pid_t)value)) pids[(*count)++] = (pid_t)value;
    p = end;
  }
}

static void collect_thread_children(pid_t process, pid_t *pids, size_t *count, size_t capacity) {
  char task_path[128]; snprintf(task_path, sizeof(task_path), "/proc/%d/task", process);
  DIR *dir = opendir(task_path); int read_any_thread = 0;
  if (dir) {
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && *count < capacity) {
      if (entry->d_name[0] == '.') continue;
      char *end = NULL; errno = 0; long tid = strtol(entry->d_name, &end, 10);
      if (errno != 0 || end == entry->d_name || *end != '\0' || tid <= 0) continue;
      char path[160], children[2048];
      snprintf(path, sizeof(path), "/proc/%d/task/%ld/children", process, tid);
      if (read_text(path, children, sizeof(children)) <= 0) continue;
      read_any_thread = 1; append_children_text(children, pids, count, capacity);
    }
    closedir(dir);
  }
  if (!read_any_thread && *count < capacity) {
    char path[160], children[2048];
    snprintf(path, sizeof(path), "/proc/%d/task/%d/children", process, process);
    if (read_text(path, children, sizeof(children)) > 0)
      append_children_text(children, pids, count, capacity);
  }
}

static const char *tracked_role_for_identity(
    pid_t pid, unsigned long long starttime_ticks,
    const struct observer_tracked_pid *tracked, size_t tracked_count) {
  for (size_t i = 0; i < tracked_count; ++i) {
    if (tracked[i].pid == pid && tracked[i].starttime_ticks == starttime_ticks)
      return tracked[i].role;
  }
  return "tree";
}

static int snapshot_identity_is_current(
    pid_t pid, const struct observer_tracked_pid *tracked, size_t tracked_count) {
  unsigned long long current = 0;
  if (!read_proc_starttime(pid, &current)) return 0;
  for (size_t i = 0; i < tracked_count; ++i) {
    if (tracked[i].pid == pid && tracked[i].starttime_ticks == current) return 1;
  }
  return 0;
}

static int snapshot_has_tracked_pid(
    pid_t pid, const struct observer_tracked_pid *tracked, size_t tracked_count) {
  for (size_t i = 0; i < tracked_count; ++i) if (tracked[i].pid == pid) return 1;
  return 0;
}

static void forget_snapshot_identities_for_pid(
    pid_t pid, const struct observer_tracked_pid *tracked, size_t tracked_count,
    unsigned long long keep_starttime) {
  for (size_t i = 0; i < tracked_count; ++i) {
    if (tracked[i].pid != pid || tracked[i].starttime_ticks == keep_starttime) continue;
    observer_forget_tracked_identity(pid, tracked[i].starttime_ticks);
  }
}

static size_t collect_observed_processes(
    pid_t root, pid_t *pids, size_t capacity,
    const struct observer_tracked_pid *tracked, size_t tracked_count) {
  if (capacity == 0) return 0;
  size_t count = 0;
  if (root > 0 && snapshot_identity_is_current(root, tracked, tracked_count))
    pids[count++] = root;
  for (size_t i = 0; i < tracked_count && count < capacity; ++i) {
    if (tracked[i].pid <= 0 || pid_in_list(pids, count, tracked[i].pid)) continue;
    unsigned long long current = 0;
    if (!read_proc_starttime(tracked[i].pid, &current) ||
        current != tracked[i].starttime_ticks) {
      observer_forget_tracked_identity(
          tracked[i].pid, tracked[i].starttime_ticks);
      continue;
    }
    pids[count++] = tracked[i].pid;
  }
  for (size_t index = 0; index < count && count < capacity; ++index) {
    if (snapshot_has_tracked_pid(pids[index], tracked, tracked_count) &&
        !snapshot_identity_is_current(pids[index], tracked, tracked_count))
      continue;
    collect_thread_children(pids[index], pids, &count, capacity);
  }
  return count;
}

static void sample_process_tree(pid_t root, uint64_t now_ms) {
  struct observer_tracked_pid tracked[OBS_MAX_TRACKED_PIDS];
  size_t tracked_count = observer_snapshot_tracked(tracked, OBS_MAX_TRACKED_PIDS);
  pid_t pids[OBS_MAX_PIDS];
  size_t count = collect_observed_processes(
      root, pids, OBS_MAX_PIDS, tracked, tracked_count);
  observer_append("RMG_OBSERVER_V2|event=tree|t_ms=%llu|root=%d|count=%zu|tracked=%zu\n",
                  (unsigned long long)now_ms, root, count, tracked_count);
  for (size_t i = 0; i < count; ++i) {
    struct proc_sample sample; memset(&sample, 0, sizeof(sample));
    if (!parse_proc_stat(pids[i], &sample)) {
      observer_append("RMG_OBSERVER_V2|event=proc_unavailable|t_ms=%llu|pid=%d|role=unknown\n",
          (unsigned long long)now_ms, pids[i]);
      forget_snapshot_identities_for_pid(pids[i], tracked, tracked_count, 0);
      continue;
    }
    forget_snapshot_identities_for_pid(
        pids[i], tracked, tracked_count, sample.starttime_ticks);
    const char *role = tracked_role_for_identity(
        pids[i], sample.starttime_ticks, tracked, tracked_count);
    if (!identity_seen(pids[i], sample.starttime_ticks))
      sample_process_metadata(pids[i], now_ms);
    char cpu[24], runtime[40], wait[40], slices[40];
    if (sample.processor_ok) snprintf(cpu, sizeof(cpu), "%ld", sample.processor);
    else snprintf(cpu, sizeof(cpu), "NA");
    format_u64(runtime, sizeof(runtime), sample.schedstat_ok, sample.runtime_ns);
    format_u64(wait, sizeof(wait), sample.schedstat_ok, sample.wait_ns);
    format_u64(slices, sizeof(slices), sample.schedstat_ok, sample.slices);
    observer_append("RMG_OBSERVER_V2|event=proc|t_ms=%llu|pid=%d|starttime_ticks=%llu|ppid=%d|comm=%s|state=%c|cpu=%s|threads=%ld|utime=%llu|stime=%llu|runtime_ns=%s|wait_ns=%s|slices=%s|role=%s\n",
        (unsigned long long)now_ms, pids[i], sample.starttime_ticks,
        sample.ppid, sample.comm, sample.state ? sample.state : '?', cpu,
        sample.threads, sample.utime, sample.stime, runtime, wait, slices, role);
  }
}

static void probe_capabilities(void) {
  memset(&observer_caps, 0, sizeof(observer_caps));
  observer_caps.loadavg = probe_readable("/proc/loadavg");
  observer_caps.psi_cpu = probe_readable("/proc/pressure/cpu");
  observer_caps.psi_mem = probe_readable("/proc/pressure/memory");
  observer_caps.psi_io = probe_readable("/proc/pressure/io");
  observer_caps.meminfo = probe_readable("/proc/meminfo");
  observer_caps.proc_stat = probe_readable("/proc/stat");
  observer_caps.vmstat = probe_readable("/proc/vmstat");
  observer_caps.buddyinfo = probe_readable("/proc/buddyinfo");
  long cpus = sysconf(_SC_NPROCESSORS_CONF); if (cpus < 0) cpus = 0; if (cpus > OBS_MAX_CPUS) cpus = OBS_MAX_CPUS;
  observer_caps.cpu_count = cpus;
  for (long cpu = 0; cpu < cpus; ++cpu) {
    char path[160]; snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_cur_freq", cpu);
    observer_caps.cpufreq[cpu] = probe_readable(path);
  }
  char freq_caps[96] = {0}; size_t used = 0;
  for (long cpu = 0; cpu < cpus; ++cpu) {
    int n = snprintf(freq_caps + used, sizeof(freq_caps) - used, "%s%d", cpu ? "," : "", observer_caps.cpufreq[cpu]);
    if (n < 0 || (size_t)n >= sizeof(freq_caps) - used) break; used += (size_t)n;
  }
  observer_append("RMG_OBSERVER_V2|event=capabilities|t_ms=%llu|loadavg=%d|psi_cpu=%d|psi_mem=%d|psi_io=%d|meminfo=%d|proc_stat=%d|vmstat=%d|buddyinfo=%d|cpufreq=%s\n",
      (unsigned long long)boottime_ms(), observer_caps.loadavg, observer_caps.psi_cpu,
      observer_caps.psi_mem, observer_caps.psi_io, observer_caps.meminfo,
      observer_caps.proc_stat, observer_caps.vmstat, observer_caps.buddyinfo,
      freq_caps[0] ? freq_caps : "NA");
}

static void sample_system(uint64_t now_ms) {
  double load1 = 0.0, load5 = 0.0, load15 = 0.0; int runnable = 0, tasks = 0, load_ok = 0;
  if (observer_caps.loadavg) {
    char loadavg[256] = {0};
    if (read_text("/proc/loadavg", loadavg, sizeof(loadavg)) > 0 && sscanf(loadavg, "%lf %lf %lf %d/%d", &load1, &load5, &load15, &runnable, &tasks) == 5) load_ok = 1;
    else observer_caps.loadavg = 0;
  }
  double cpu_psi = 0.0, mem_psi = 0.0, io_psi = 0.0; unsigned long long cpu_psi_total = 0, mem_psi_total = 0, io_psi_total = 0;
  int cpu_psi_ok = observer_caps.psi_cpu && psi_read("/proc/pressure/cpu", &cpu_psi, &cpu_psi_total);
  int mem_psi_ok = observer_caps.psi_mem && psi_read("/proc/pressure/memory", &mem_psi, &mem_psi_total);
  int io_psi_ok = observer_caps.psi_io && psi_read("/proc/pressure/io", &io_psi, &io_psi_total);
  if (observer_caps.psi_cpu && !cpu_psi_ok) observer_caps.psi_cpu = 0;
  if (observer_caps.psi_mem && !mem_psi_ok) observer_caps.psi_mem = 0;
  if (observer_caps.psi_io && !io_psi_ok) observer_caps.psi_io = 0;
  char meminfo[8192] = {0}; int mem_ok = observer_caps.meminfo && read_text("/proc/meminfo", meminfo, sizeof(meminfo)) > 0;
  if (observer_caps.meminfo && !mem_ok) observer_caps.meminfo = 0;
  unsigned long long mem_available=0, slab=0, sreclaim=0, sunreclaim=0, anon=0, pagetables=0;
  int mem_available_ok = mem_ok && named_value_checked(meminfo,"MemAvailable",&mem_available);
  int slab_ok=mem_ok&&named_value_checked(meminfo,"Slab",&slab), sreclaim_ok=mem_ok&&named_value_checked(meminfo,"SReclaimable",&sreclaim);
  int sunreclaim_ok=mem_ok&&named_value_checked(meminfo,"SUnreclaim",&sunreclaim), anon_ok=mem_ok&&named_value_checked(meminfo,"AnonPages",&anon), pagetables_ok=mem_ok&&named_value_checked(meminfo,"PageTables",&pagetables);
  unsigned long long user=0,nice=0,system=0,idle=0,iowait=0,irq=0,softirq=0,steal=0; int stat_ok=0;
  if (observer_caps.proc_stat) {
    char stat[512]={0};
    if (read_text("/proc/stat",stat,sizeof(stat))>0 && sscanf(stat,"cpu %llu %llu %llu %llu %llu %llu %llu %llu",&user,&nice,&system,&idle,&iowait,&irq,&softirq,&steal)==8) stat_ok=1;
    else observer_caps.proc_stat=0;
  }
  char a[32],b[32],d[32],e[24],f[24],g[32],h[40],i[32],j[40],k[32],l[40],m[40],n[40],o[40],p[40],q[40],r[40],s[40],t[40],u[40],v[40],w[40],x[40],y[40],z[40];
  format_double(a,sizeof(a),load_ok,load1); format_double(b,sizeof(b),load_ok,load5); format_double(d,sizeof(d),load_ok,load15); format_int(e,sizeof(e),load_ok,runnable); format_int(f,sizeof(f),load_ok,tasks);
  format_double(g,sizeof(g),cpu_psi_ok,cpu_psi); format_u64(h,sizeof(h),cpu_psi_ok,cpu_psi_total); format_double(i,sizeof(i),mem_psi_ok,mem_psi); format_u64(j,sizeof(j),mem_psi_ok,mem_psi_total); format_double(k,sizeof(k),io_psi_ok,io_psi); format_u64(l,sizeof(l),io_psi_ok,io_psi_total);
  format_u64(m,sizeof(m),mem_available_ok,mem_available); format_u64(n,sizeof(n),slab_ok,slab); format_u64(o,sizeof(o),sreclaim_ok,sreclaim); format_u64(p,sizeof(p),sunreclaim_ok,sunreclaim); format_u64(q,sizeof(q),anon_ok,anon); format_u64(r,sizeof(r),pagetables_ok,pagetables);
  format_u64(s,sizeof(s),stat_ok,user); format_u64(t,sizeof(t),stat_ok,nice); format_u64(u,sizeof(u),stat_ok,system); format_u64(v,sizeof(v),stat_ok,idle); format_u64(w,sizeof(w),stat_ok,iowait); format_u64(x,sizeof(x),stat_ok,irq); format_u64(y,sizeof(y),stat_ok,softirq); format_u64(z,sizeof(z),stat_ok,steal);
  observer_append("RMG_OBSERVER_V2|event=system|t_ms=%llu|load1=%s|load5=%s|load15=%s|runnable=%s|tasks=%s|psi_cpu10=%s|psi_cpu_total=%s|psi_mem10=%s|psi_mem_total=%s|psi_io10=%s|psi_io_total=%s|memavail_kb=%s|slab_kb=%s|sreclaim_kb=%s|sunreclaim_kb=%s|anon_kb=%s|pagetables_kb=%s|cpu_user=%s|cpu_nice=%s|cpu_system=%s|cpu_idle=%s|cpu_iowait=%s|cpu_irq=%s|cpu_softirq=%s|cpu_steal=%s\n",
      (unsigned long long)now_ms,a,b,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z);
}

static int sample_buddy(unsigned long long *orders, size_t order_count) {
  char text[8192]; memset(orders,0,order_count*sizeof(*orders)); if (read_text("/proc/buddyinfo",text,sizeof(text))<=0) return 0;
  char *save_line=NULL;
  for(char *line=strtok_r(text,"\n",&save_line);line;line=strtok_r(NULL,"\n",&save_line)){
    char *zone=strstr(line,"zone"); if(!zone)continue; char *p=zone+4; while(*p==' '||*p=='\t')p++; while(*p&&*p!=' '&&*p!='\t')p++; size_t order=0;
    while(*p&&order<order_count){while(*p==' '||*p=='\t')p++;if(!*p)break;char *end=NULL;errno=0;unsigned long long value=strtoull(p,&end,10);if(end==p||errno!=0)break;orders[order++]+=value;p=end;}
  }
  return 1;
}

static void sample_slow(uint64_t now_ms) {
  char vmstat[32768]={0}; int vm_ok=observer_caps.vmstat&&read_text("/proc/vmstat",vmstat,sizeof(vmstat))>0; if(observer_caps.vmstat&&!vm_ok)observer_caps.vmstat=0;
  static const char *keys[]={"pgfault","pgmajfault","pgscan_kswapd","pgscan_direct","pgsteal_kswapd","pgsteal_direct","allocstall","compact_stall"}; unsigned long long vals[8]={0}; int oks[8]={0};
  if(vm_ok)for(size_t idx=0;idx<8;++idx)oks[idx]=named_value_checked(vmstat,keys[idx],&vals[idx]);
  unsigned long long buddy[11]={0}; int buddy_ok=observer_caps.buddyinfo&&sample_buddy(buddy,11); if(observer_caps.buddyinfo&&!buddy_ok)observer_caps.buddyinfo=0;
  char frequencies[384]={0}; size_t used=0;
  for(long cpu=0;cpu<observer_caps.cpu_count;++cpu){char txt[64]={0};unsigned long long freq=0;int ok=0;if(observer_caps.cpufreq[cpu]){char path[160];snprintf(path,sizeof(path),"/sys/devices/system/cpu/cpu%ld/cpufreq/scaling_cur_freq",cpu);if(read_text(path,txt,sizeof(txt))>0){errno=0;char *end=NULL;freq=strtoull(txt,&end,10);ok=errno==0&&end!=txt;}if(!ok)observer_caps.cpufreq[cpu]=0;}char one[40];format_u64(one,sizeof(one),ok,freq);int count=snprintf(frequencies+used,sizeof(frequencies)-used,"%s%s",cpu?",":"",one);if(count<0||(size_t)count>=sizeof(frequencies)-used)break;used+=(size_t)count;}
  char vt[8][40],bt[11][40];for(size_t idx=0;idx<8;++idx)format_u64(vt[idx],sizeof(vt[idx]),oks[idx],vals[idx]);for(size_t idx=0;idx<11;++idx)format_u64(bt[idx],sizeof(bt[idx]),buddy_ok,buddy[idx]);
  observer_append("RMG_OBSERVER_V2|event=vm|t_ms=%llu|pgfault=%s|pgmajfault=%s|pgscan_kswapd=%s|pgscan_direct=%s|pgsteal_kswapd=%s|pgsteal_direct=%s|allocstall=%s|compact_stall=%s|buddy_o0=%s|buddy_o1=%s|buddy_o2=%s|buddy_o3=%s|buddy_o4=%s|buddy_o5=%s|buddy_o6=%s|buddy_o7=%s|buddy_o8=%s|buddy_o9=%s|buddy_o10=%s|freq_khz=%s\n",
      (unsigned long long)now_ms,vt[0],vt[1],vt[2],vt[3],vt[4],vt[5],vt[6],vt[7],bt[0],bt[1],bt[2],bt[3],bt[4],bt[5],bt[6],bt[7],bt[8],bt[9],bt[10],frequencies[0]?frequencies:"NA");
}

static const char *classify_marker(const char *line) {
  if(strstr(line,"preload supervisor pid="))return "supervisor_start";
  if(strstr(line,"[+] exploit attempt="))return "attempt_begin";
  if(strstr(line,"slide child context"))return "slide_child";
  if(strstr(line,"slide source mode=p0"))return "p0_begin";
  if(strstr(line,"p0 pipe oracle prepared"))return "p0_oracle_ready";
  if(strstr(line,"kernel page prepare mode=1"))return "p0_page_prepared";
  if(strstr(line,"slide-kaslr-ok"))return "p0_success";
  if(strstr(line,"kernel page prepare mode=0"))return "fops_page_prepared";
  if(strstr(line,"durable log checkpoint stage=fops-page-held"))return "fops_page_held";
  if(strstr(line,"app fops slide route"))return "fops_route";
  if(strstr(line,"slide app bank selected")||strstr(line,"slide app stage=trigger"))return "race_route";
  if(strstr(line,"p0 physical write"))return "writer_result";
  if(strstr(line,"app fops stage=trigger-return"))return "fops_trigger_result";
  if(strstr(line,"pipe caches"))return "physrw_begin";
  if(strstr(line,"slide kaslr leak failed"))return "p0_failed";
  if(strstr(line,"writer route outcome"))return "unsafe_stop";
  if(strstr(line,"exploit completed attempt="))return "exploit_success";
  return NULL;
}

static void record_log_line(const char *line,uint64_t now_ms){track_marker_processes(line,now_ms);const char *marker=classify_marker(line);if(!marker)return;unsigned long long until=now_ms+OBS_BURST_WINDOW_MS,current=atomic_load_explicit(&observer_burst_until_ms,memory_order_relaxed);while(until>current&&!atomic_compare_exchange_weak_explicit(&observer_burst_until_ms,&current,until,memory_order_relaxed,memory_order_relaxed)){}char copy[256];size_t len=strlen(line);if(len>=sizeof(copy))len=sizeof(copy)-1;memcpy(copy,line,len);copy[len]='\0';sanitize_inline(copy);observer_append("RMG_OBSERVER_V2|event=marker|t_ms=%llu|name=%s|line=%s\n",(unsigned long long)now_ms,marker,copy);}

static void tail_payload_log(uint64_t now_ms){if(observer_log_fd<0)return;struct stat st;if(fstat(observer_log_fd,&st)==0&&st.st_size<observer_log_offset){observer_log_offset=0;observer_log_partial_length=0;}char chunk[2048];for(;;){ssize_t count=pread(observer_log_fd,chunk,sizeof(chunk),observer_log_offset);if(count<=0)break;observer_log_offset+=count;for(ssize_t idx=0;idx<count;++idx){char ch=chunk[idx];if(ch=='\n'){observer_log_partial[observer_log_partial_length]='\0';record_log_line(observer_log_partial,now_ms);observer_log_partial_length=0;}else if(ch!='\r'){if(observer_log_partial_length+1<sizeof(observer_log_partial))observer_log_partial[observer_log_partial_length++]=ch;else observer_log_partial_length=0;}}}}

struct observer_sched_setup{int nice_ret,nice_errno,scheduler_ret,scheduler_errno,affinity_ret,affinity_errno;long requested_cpu;char effective_cpus[128];};
static void format_cpu_set(const cpu_set_t *set,char *out,size_t size){if(!out||size==0)return;out[0]='\0';size_t used=0;int first=1;for(int cpu=0;cpu<CPU_SETSIZE;++cpu){if(!CPU_ISSET(cpu,set))continue;int n=snprintf(out+used,size-used,"%s%d",first?"":",",cpu);if(n<0||(size_t)n>=size-used)break;used+=(size_t)n;first=0;}if(out[0]=='\0')snprintf(out,size,"NA");}
static struct observer_sched_setup observer_pin_away_from_exploit(void){struct observer_sched_setup result;memset(&result,0,sizeof(result));result.requested_cpu=-1;snprintf(result.effective_cpus,sizeof(result.effective_cpus),"NA");errno=0;result.nice_ret=setpriority(PRIO_PROCESS,0,10);result.nice_errno=result.nice_ret==0?0:errno;
#ifdef SCHED_IDLE
struct sched_param idle={.sched_priority=0};errno=0;result.scheduler_ret=sched_setscheduler(0,SCHED_IDLE,&idle);result.scheduler_errno=result.scheduler_ret==0?0:errno;
#else
result.scheduler_ret=-1;result.scheduler_errno=ENOTSUP;
#endif
cpu_set_t allowed;CPU_ZERO(&allowed);errno=0;if(sched_getaffinity(0,sizeof(allowed),&allowed)==0){long selected=-1;for(int cpu=CPU_SETSIZE-1;cpu>=0;--cpu){if(CPU_ISSET(cpu,&allowed)){selected=cpu;break;}}if(selected>=0){cpu_set_t set;CPU_ZERO(&set);result.requested_cpu=selected;CPU_SET((int)selected,&set);errno=0;result.affinity_ret=sched_setaffinity(0,sizeof(set),&set);result.affinity_errno=result.affinity_ret==0?0:errno;}else{result.affinity_ret=-1;result.affinity_errno=ENOTSUP;}}else{result.affinity_ret=-1;result.affinity_errno=errno;}cpu_set_t effective;CPU_ZERO(&effective);if(sched_getaffinity(0,sizeof(effective),&effective)==0)format_cpu_set(&effective,result.effective_cpus,sizeof(result.effective_cpus));return result;}

static void *observer_main(void *unused){(void)unused;struct observer_sched_setup ss=observer_pin_away_from_exploit();observer_append("RMG_OBSERVER_V2|event=observer_sched|t_ms=%llu|policy=%d|nice=%d|cpu=%d|setpriority_ret=%d|setpriority_errno=%d|setscheduler_ret=%d|setscheduler_errno=%d|requested_cpu=%ld|setaffinity_ret=%d|setaffinity_errno=%d|effective_cpus=%s\n",(unsigned long long)boottime_ms(),sched_getscheduler(0),getpriority(PRIO_PROCESS,0),sched_getcpu(),ss.nice_ret,ss.nice_errno,ss.scheduler_ret,ss.scheduler_errno,ss.requested_cpu,ss.affinity_ret,ss.affinity_errno,ss.effective_cpus);probe_capabilities();uint64_t last_process=0,last_system=0,last_slow=0;int last_target=-1;while(atomic_load_explicit(&observer_running,memory_order_relaxed)){uint64_t now=boottime_ms();int target=atomic_load_explicit(&observer_target_pid,memory_order_relaxed);if(target!=last_target){observer_append("RMG_OBSERVER_V2|event=target|t_ms=%llu|pid=%d\n",(unsigned long long)now,target);last_target=target;}tail_payload_log(now);unsigned long long until=atomic_load_explicit(&observer_burst_until_ms,memory_order_relaxed);int burst=now<=until;uint64_t pi=burst?OBS_PROCESS_INTERVAL_BURST_MS:OBS_PROCESS_INTERVAL_IDLE_MS;if(target>0&&(last_process==0||now-last_process>=pi)){sample_process_tree((pid_t)target,now);last_process=now;}uint64_t si=burst?OBS_SYSTEM_INTERVAL_BURST_MS:OBS_SYSTEM_INTERVAL_IDLE_MS;if(last_system==0||now-last_system>=si){sample_system(now);last_system=now;}if(last_slow==0||now-last_slow>=OBS_SLOW_INTERVAL_MS){sample_slow(now);last_slow=now;}struct timespec sleep_for={.tv_sec=0,.tv_nsec=(long)OBS_POLL_INTERVAL_MS*1000000L};while(nanosleep(&sleep_for,&sleep_for)<0&&errno==EINTR){}}tail_payload_log(boottime_ms());return NULL;}

static void observer_reset(void){if(observer_log_fd>=0){close(observer_log_fd);observer_log_fd=-1;}free(observer_buffer);observer_buffer=NULL;observer_length=0;observer_dropped=0;observer_log_path[0]='\0';observer_log_offset=0;observer_log_partial_length=0;observer_seen_identity_count=0;memset(observer_seen_identities,0,sizeof(observer_seen_identities));pthread_mutex_lock(&observer_pid_mutex);observer_tracked_pid_count=0;memset(observer_tracked_pids,0,sizeof(observer_tracked_pids));pthread_mutex_unlock(&observer_pid_mutex);memset(&observer_caps,0,sizeof(observer_caps));atomic_store(&observer_burst_until_ms,0);atomic_store(&observer_target_pid,0);}

JNIEXPORT jboolean JNICALL Java_dev_busung_s25uroot_NativeProbe_observerStart(JNIEnv *env,jobject thiz,jstring log_path){(void)thiz;if(atomic_load(&observer_running))return JNI_TRUE;observer_reset();observer_buffer=malloc(OBS_BUFFER_CAPACITY);if(!observer_buffer)return JNI_FALSE;observer_buffer[0]='\0';if(log_path){const char *path=(*env)->GetStringUTFChars(env,log_path,NULL);if(path){snprintf(observer_log_path,sizeof(observer_log_path),"%s",path);(*env)->ReleaseStringUTFChars(env,log_path,path);observer_log_fd=open(observer_log_path,O_RDONLY|O_CLOEXEC);}}observer_append("RMG_OBSERVER_V2|event=start|t_ms=%llu|observer_pid=%d|poll_ms=%llu|proc_idle_ms=%llu|proc_burst_ms=%llu|system_idle_ms=%llu|system_burst_ms=%llu|burst_window_ms=%llu|slow_ms=%llu|log_tail=%d|buffer_bytes=%u\n",(unsigned long long)boottime_ms(),getpid(),(unsigned long long)OBS_POLL_INTERVAL_MS,(unsigned long long)OBS_PROCESS_INTERVAL_IDLE_MS,(unsigned long long)OBS_PROCESS_INTERVAL_BURST_MS,(unsigned long long)OBS_SYSTEM_INTERVAL_IDLE_MS,(unsigned long long)OBS_SYSTEM_INTERVAL_BURST_MS,(unsigned long long)OBS_BURST_WINDOW_MS,(unsigned long long)OBS_SLOW_INTERVAL_MS,observer_log_fd>=0,OBS_BUFFER_CAPACITY);atomic_store(&observer_running,1);if(pthread_create(&observer_thread,NULL,observer_main,NULL)!=0){atomic_store(&observer_running,0);observer_reset();return JNI_FALSE;}return JNI_TRUE;}

JNIEXPORT jboolean JNICALL Java_dev_busung_s25uroot_NativeProbe_observerAttachPid(JNIEnv *env,jobject thiz,jlong pid){(void)env;(void)thiz;if(!atomic_load(&observer_running)||pid<=0||pid>INT_MAX)return JNI_FALSE;char path[128],text[256];snprintf(path,sizeof(path),"/proc/%lld/stat",(long long)pid);int stat_access=read_text(path,text,sizeof(text))>0;uint64_t now=boottime_ms();observer_append("RMG_OBSERVER_V2|event=attach|t_ms=%llu|pid=%lld|stat_access=%d\n",(unsigned long long)now,(long long)pid,stat_access);observer_track_pid((pid_t)pid,"helper",now);atomic_store_explicit(&observer_target_pid,(int)pid,memory_order_relaxed);return JNI_TRUE;}

JNIEXPORT jboolean JNICALL Java_dev_busung_s25uroot_NativeProbe_observerMarker(JNIEnv *env,jobject thiz,jstring line){(void)thiz;if(!atomic_load(&observer_running)||!line)return JNI_FALSE;const char *text=(*env)->GetStringUTFChars(env,line,NULL);if(!text)return JNI_FALSE;record_log_line(text,boottime_ms());(*env)->ReleaseStringUTFChars(env,line,text);return JNI_TRUE;}

static int write_all(int fd,const char *data,size_t length){size_t written=0;while(written<length){ssize_t count=write(fd,data+written,length-written);if(count<0&&errno==EINTR)continue;if(count<=0)return 0;written+=(size_t)count;}return 1;}
JNIEXPORT jboolean JNICALL Java_dev_busung_s25uroot_NativeProbe_observerStop(JNIEnv *env,jobject thiz,jstring output_path){(void)thiz;if(!output_path)return JNI_FALSE;if(atomic_exchange(&observer_running,0))(void)pthread_join(observer_thread,NULL);if(!observer_buffer)return JNI_FALSE;observer_append("RMG_OBSERVER_V2|event=stop|t_ms=%llu|dropped=%lu|bytes=%zu\n",(unsigned long long)boottime_ms(),observer_dropped,observer_length);const char *path=(*env)->GetStringUTFChars(env,output_path,NULL);if(!path){observer_reset();return JNI_FALSE;}int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);int ok=fd>=0&&write_all(fd,observer_buffer,observer_length);if(fd>=0)close(fd);(*env)->ReleaseStringUTFChars(env,output_path,path);observer_reset();return ok?JNI_TRUE:JNI_FALSE;}
