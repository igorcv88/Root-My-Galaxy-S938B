#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


manual = "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt"
replace_once(
    manual,
    '''                    if (diagnosticSnapshot == null && supervisorAttempt != null &&
                        supervisorAttempt != previousSupervisorAttempt
                    ) {
                        checkpointSupervisorAttempt(
                            supervisorAttempt,
                            SystemClock.elapsedRealtime() - startedAt,
                        )
                    }''',
    '''                    supervisorAttempt?.let { attempt ->
                        if (diagnosticSnapshot == null && attempt != previousSupervisorAttempt) {
                            checkpointSupervisorAttempt(
                                attempt,
                                SystemClock.elapsedRealtime() - startedAt,
                            )
                        }
                    }''',
)
replace_once(
    manual,
    '''            if (diagnosticSnapshot == null && supervisorAttempt != null &&
                supervisorAttempt != previousSupervisorAttempt
            ) {
                checkpointSupervisorAttempt(
                    supervisorAttempt,
                    SystemClock.elapsedRealtime() - startedAt,
                )
            }''',
    '''            supervisorAttempt?.let { attempt ->
                if (diagnosticSnapshot == null && attempt != previousSupervisorAttempt) {
                    checkpointSupervisorAttempt(
                        attempt,
                        SystemClock.elapsedRealtime() - startedAt,
                    )
                }
            }''',
)
replace_once(
    manual,
    'exploitElapsedMillis = maxOf(entry.exploitElapsedMillis, elapsedMillis),',
    'exploitElapsedMillis = maxOf(entry.exploitElapsedMillis ?: 0L, elapsedMillis),',
)

auto = "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt"
replace_once(
    auto,
    '''            if (supervisorAttempt != null && supervisorAttempt != previousSupervisorAttempt) {
                onSupervisorAttempt(
                    supervisorAttempt,
                    SystemClock.elapsedRealtime() - spawnUptimeMillis,
                )
            }''',
    '''            supervisorAttempt?.let { attempt ->
                if (attempt != previousSupervisorAttempt) {
                    onSupervisorAttempt(
                        attempt,
                        SystemClock.elapsedRealtime() - spawnUptimeMillis,
                    )
                }
            }''',
)

service = "app/src/main/java/dev/busung/s25uroot/AutoRootService.kt"
replace_once(
    service,
    'exploitElapsedMillis = maxOf(entry.exploitElapsedMillis, elapsedMillis),',
    'exploitElapsedMillis = maxOf(entry.exploitElapsedMillis ?: 0L, elapsedMillis),',
)

diag = "app/src/main/java/dev/busung/s25uroot/ExploitDiagnostics.kt"
p = Path(diag)
text = p.read_text()
start = text.index('    private val childOutcome = Regex(')
end = text.index('\n\n    fun classify', start)
text = text[:start] + '''    private val childOutcome = Regex(
        """supervisor child outcome attempt=([0-9]+)/([0-9]+)[^\\n]*retry=([0-9]+)[^\\n]*reboot_required=([0-9]+)""",
    )''' + text[end:]
p.write_text(text)

observer = "app/src/main/cpp/external_observer.c"
replace_once(
    observer,
    '''#define OBS_FAST_INTERVAL_MS 50ULL
#define OBS_SYSTEM_INTERVAL_ACTIVE_MS 250ULL
#define OBS_SYSTEM_INTERVAL_IDLE_MS 500ULL
#define OBS_SLOW_INTERVAL_MS 1000ULL''',
    '''#define OBS_POLL_INTERVAL_MS 25ULL
#define OBS_PROCESS_INTERVAL_BURST_MS 25ULL
#define OBS_PROCESS_INTERVAL_IDLE_MS 250ULL
#define OBS_SYSTEM_INTERVAL_BURST_MS 200ULL
#define OBS_SYSTEM_INTERVAL_IDLE_MS 500ULL
#define OBS_SLOW_INTERVAL_MS 1000ULL
#define OBS_BURST_WINDOW_MS 2000ULL''',
)
replace_once(
    observer,
    '''static pid_t observer_seen_pids[OBS_MAX_SEEN_PIDS];
static size_t observer_seen_pid_count;''',
    '''static pid_t observer_seen_pids[OBS_MAX_SEEN_PIDS];
static size_t observer_seen_pid_count;
static uint64_t observer_burst_until_ms;''',
)
replace_once(
    observer,
    '''  for (size_t i = 0; i < count; ++i) {
    struct proc_sample sample;
    memset(&sample, 0, sizeof(sample));''',
    '''  for (size_t i = 0; i < count; ++i) {
    if (i == 0) {
      if (!pid_seen(pids[i])) sample_process_metadata(pids[i], now_ms);
      continue;
    }
    struct proc_sample sample;
    memset(&sample, 0, sizeof(sample));''',
)
replace_once(
    observer,
    '''  const char *marker = classify_marker(line);
  if (!marker) return;
  char copy[256];''',
    '''  const char *marker = classify_marker(line);
  if (!marker) return;
  uint64_t burst_until = now_ms + OBS_BURST_WINDOW_MS;
  if (burst_until > observer_burst_until_ms) observer_burst_until_ms = burst_until;
  char copy[256];''',
)
replace_once(
    observer,
    '''  uint64_t last_system = 0;
  uint64_t last_slow = 0;
  int last_target = -1;''',
    '''  uint64_t last_process = 0;
  uint64_t last_system = 0;
  uint64_t last_slow = 0;
  int last_target = -1;''',
)
replace_once(
    observer,
    '''    tail_payload_log(now_ms);
    if (target > 0) sample_process_tree((pid_t)target, now_ms);

    uint64_t system_interval = target > 0 ? OBS_SYSTEM_INTERVAL_ACTIVE_MS
                                          : OBS_SYSTEM_INTERVAL_IDLE_MS;
    if (last_system == 0 || now_ms - last_system >= system_interval) {''',
    '''    tail_payload_log(now_ms);
    int burst = observer_log_fd >= 0 && now_ms <= observer_burst_until_ms;
    uint64_t process_interval = burst ? OBS_PROCESS_INTERVAL_BURST_MS
                                      : OBS_PROCESS_INTERVAL_IDLE_MS;
    if (target > 0 &&
        (last_process == 0 || now_ms - last_process >= process_interval)) {
      sample_process_tree((pid_t)target, now_ms);
      last_process = now_ms;
    }

    uint64_t system_interval = burst ? OBS_SYSTEM_INTERVAL_BURST_MS
                                     : OBS_SYSTEM_INTERVAL_IDLE_MS;
    if (last_system == 0 || now_ms - last_system >= system_interval) {''',
)
replace_once(
    observer,
    '''    struct timespec sleep_for = {
        .tv_sec = 0,
        .tv_nsec = (long)(target > 0 ? OBS_FAST_INTERVAL_MS : 100ULL) * 1000000L,
    };''',
    '''    struct timespec sleep_for = {
        .tv_sec = 0,
        .tv_nsec = (long)OBS_POLL_INTERVAL_MS * 1000000L,
    };''',
)
replace_once(
    observer,
    '''  observer_seen_pid_count = 0;
  atomic_store(&observer_target_pid, 0);''',
    '''  observer_seen_pid_count = 0;
  observer_burst_until_ms = 0;
  atomic_store(&observer_target_pid, 0);''',
)
replace_once(
    observer,
    '''      "RMG_OBSERVER_V2|event=start|t_ms=%llu|observer_pid=%d|fast_ms=%llu|system_ms=%llu|slow_ms=%llu|"
      "log_tail=%d|buffer_bytes=%u\\n",
      (unsigned long long)boottime_ms(), getpid(),
      (unsigned long long)OBS_FAST_INTERVAL_MS,
      (unsigned long long)OBS_SYSTEM_INTERVAL_ACTIVE_MS,
      (unsigned long long)OBS_SLOW_INTERVAL_MS,''',
    '''      "RMG_OBSERVER_V2|event=start|t_ms=%llu|observer_pid=%d|poll_ms=%llu|proc_idle_ms=%llu|"
      "proc_burst_ms=%llu|system_idle_ms=%llu|system_burst_ms=%llu|burst_window_ms=%llu|slow_ms=%llu|"
      "log_tail=%d|buffer_bytes=%u\\n",
      (unsigned long long)boottime_ms(), getpid(),
      (unsigned long long)OBS_POLL_INTERVAL_MS,
      (unsigned long long)OBS_PROCESS_INTERVAL_IDLE_MS,
      (unsigned long long)OBS_PROCESS_INTERVAL_BURST_MS,
      (unsigned long long)OBS_SYSTEM_INTERVAL_IDLE_MS,
      (unsigned long long)OBS_SYSTEM_INTERVAL_BURST_MS,
      (unsigned long long)OBS_BURST_WINDOW_MS,
      (unsigned long long)OBS_SLOW_INTERVAL_MS,''',
)
