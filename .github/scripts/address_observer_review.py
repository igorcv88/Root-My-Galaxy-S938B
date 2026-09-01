#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    p = ROOT / path
    text = p.read_text()
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: regex expected one match, got {count}: {pattern[:120]!r}")
    p.write_text(updated)


# ---------------------------------------------------------------------------
# Observer controller: cancellation-safe startup/cleanup and marker streaming.
# ---------------------------------------------------------------------------
Path('app/src/main/java/dev/busung/s25uroot/ExploitObserver.kt').write_text(r'''package dev.busung.s25uroot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class ExploitObserverReport(
    val available: Boolean,
    val targetPid: Long?,
    val text: String,
)

internal data class ExploitObserverMarkerBatch(
    val lines: List<String>,
    val consumedCharacters: Int,
)

/** Extracts only complete, useful exploit marker lines from an append-only stream. */
internal object ExploitObserverMarkerParser {
    private val markerTokens = listOf(
        "exploit attempt=",
        "slide source mode=p0",
        "p0 pipe oracle prepared",
        "slide app bank selected",
        "slide app stage=trigger",
        "p0 physical write",
        "app fops stage=trigger-return",
        "pipe caches",
        "slide kaslr leak failed",
        "writer route outcome",
        "exploit completed attempt=",
    )

    fun parseNewLines(
        log: String,
        consumedCharacters: Int,
        includeTrailingLine: Boolean = false,
    ): ExploitObserverMarkerBatch {
        val start = consumedCharacters.takeIf { it in 0..log.length } ?: 0
        val lastNewline = log.lastIndexOf('\n')
        val end = if (includeTrailingLine) log.length else lastNewline + 1
        if (end <= start) return ExploitObserverMarkerBatch(emptyList(), start)
        val lines = log.substring(start, end)
            .lineSequence()
            .filter { line -> markerTokens.any(line::contains) }
            .toList()
        return ExploitObserverMarkerBatch(lines, end)
    }
}

/** Controller living in the normal app process; the sampler itself lives in :observer. */
internal class ExploitObserverSession private constructor(
    private val context: Context,
    private val outputFile: File,
    private val readyFile: File,
    private val doneFile: File,
    val available: Boolean,
    private var attachedPid: Long?,
) {
    private var stopped = false

    fun signalMarker(line: String) {
        if (!available || stopped || line.isBlank()) return
        runCatching {
            context.startService(
                Intent(context, ExploitObserverService::class.java)
                    .setAction(ExploitObserverService.ACTION_MARKER)
                    .putExtra(ExploitObserverService.EXTRA_MARKER, line),
            )
        }
    }

    suspend fun stopAndCollect(): ExploitObserverReport {
        if (stopped) {
            return ExploitObserverReport(available, attachedPid, readReport())
        }
        stopped = true
        if (!available) {
            cleanupControlFiles()
            return ExploitObserverReport(false, attachedPid, "")
        }

        runCatching {
            context.startService(
                Intent(context, ExploitObserverService::class.java)
                    .setAction(ExploitObserverService.ACTION_STOP),
            )
        }
        val deadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MILLIS
        while (!doneFile.exists() && SystemClock.elapsedRealtime() < deadline) {
            delay(CONTROL_POLL_MILLIS)
        }
        val doneStatus = doneFile.readTextIfPresent()
        val text = readReport()
        cleanupControlFiles()
        outputFile.delete()
        return ExploitObserverReport(doneStatus == "done", attachedPid, text)
    }

    private fun readReport(): String = outputFile.readTextIfPresent()

    private fun cleanupControlFiles() {
        readyFile.delete()
        doneFile.delete()
    }

    companion object {
        private const val READY_TIMEOUT_MILLIS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 3_000L
        private const val CONTROL_POLL_MILLIS = 20L

        suspend fun start(
            context: Context,
            runId: String,
            invocationMode: InvocationMode,
            transport: String,
            payloadLog: File?,
            attachController: Boolean,
        ): ExploitObserverSession {
            val directory = File(context.filesDir, "observer").apply { mkdirs() }
            val safeRunId = runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val output = File(directory, "$safeRunId.trace")
            val ready = File(directory, "$safeRunId.ready")
            val done = File(directory, "$safeRunId.done")
            output.delete()
            ready.delete()
            done.delete()

            payloadLog?.let { log ->
                runCatching {
                    log.parentFile?.mkdirs()
                    if (!log.exists()) log.createNewFile()
                }
            }

            var started = false
            try {
                started = runCatching {
                    context.startService(
                        Intent(context, ExploitObserverService::class.java)
                            .setAction(ExploitObserverService.ACTION_START)
                            .putExtra(ExploitObserverService.EXTRA_RUN_ID, runId)
                            .putExtra(ExploitObserverService.EXTRA_INVOCATION_MODE, invocationMode.wireValue)
                            .putExtra(ExploitObserverService.EXTRA_TRANSPORT, transport)
                            .putExtra(ExploitObserverService.EXTRA_LOG_PATH, payloadLog?.absolutePath)
                            .putExtra(ExploitObserverService.EXTRA_OUTPUT_PATH, output.absolutePath)
                            .putExtra(ExploitObserverService.EXTRA_READY_PATH, ready.absolutePath)
                            .putExtra(ExploitObserverService.EXTRA_DONE_PATH, done.absolutePath),
                    )
                }.isSuccess

                if (started) {
                    val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MILLIS
                    while (!ready.exists() && SystemClock.elapsedRealtime() < deadline) {
                        delay(CONTROL_POLL_MILLIS)
                    }
                }
                val available = started && ready.readTextIfPresent() == "ready"
                val controllerPid = if (available && attachController) {
                    android.os.Process.myPid().toLong()
                } else {
                    null
                }
                if (controllerPid != null) {
                    runCatching {
                        context.startService(
                            Intent(context, ExploitObserverService::class.java)
                                .setAction(ExploitObserverService.ACTION_ATTACH)
                                .putExtra(ExploitObserverService.EXTRA_PID, controllerPid),
                        )
                    }
                }
                if (!available && started) requestStop(context)
                return ExploitObserverSession(
                    context = context,
                    outputFile = output,
                    readyFile = ready,
                    doneFile = done,
                    available = available,
                    attachedPid = controllerPid,
                )
            } catch (cancel: CancellationException) {
                if (started) requestStop(context)
                throw cancel
            } catch (error: Throwable) {
                if (started) requestStop(context)
                throw error
            }
        }

        private fun requestStop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ExploitObserverService::class.java)
                        .setAction(ExploitObserverService.ACTION_STOP),
                )
            }
        }
    }
}

private fun File.readTextIfPresent(): String = runCatching {
    if (exists()) readText() else ""
}.getOrDefault("")
''')

Path('app/src/main/java/dev/busung/s25uroot/ExploitObserverService.kt').write_text(r'''package dev.busung.s25uroot

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * Runs in the dedicated `:observer` process. Sampling is native and uses a
 * preallocated buffer; this service only handles lifecycle/control messages.
 */
class ExploitObserverService : Service() {
    private var runId: String = "unknown"
    private var invocationMode: String = "unknown"
    private var transport: String = "unknown"
    private var outputPath: String? = null
    private var donePath: String? = null
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_ATTACH -> attachTarget(intent)
            ACTION_MARKER -> recordMarker(intent)
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        runId = intent.getStringExtra(EXTRA_RUN_ID) ?: "unknown"
        invocationMode = intent.getStringExtra(EXTRA_INVOCATION_MODE) ?: "unknown"
        transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "unknown"
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        donePath = intent.getStringExtra(EXTRA_DONE_PATH)
        val readyPath = intent.getStringExtra(EXTRA_READY_PATH) ?: return
        val logPath = intent.getStringExtra(EXTRA_LOG_PATH)

        val started = runCatching { NativeProbe.observerStart(logPath) }.getOrDefault(false)
        active = started
        runCatching {
            File(readyPath).apply {
                parentFile?.mkdirs()
                writeText(if (started) "ready" else "failed")
            }
        }
        if (!started) stopSelf()
    }

    private fun attachTarget(intent: Intent) {
        if (!active) return
        val pid = intent.getLongExtra(EXTRA_PID, -1L)
        if (pid > 0) runCatching { NativeProbe.observerAttachPid(pid) }
    }

    private fun recordMarker(intent: Intent) {
        if (!active) return
        val marker = intent.getStringExtra(EXTRA_MARKER)?.takeIf(String::isNotBlank) ?: return
        runCatching { NativeProbe.observerMarker(marker) }
    }

    private fun stopSession() {
        val path = outputPath
        val stopped = if (active && path != null) {
            runCatching { NativeProbe.observerStop(path) }.getOrDefault(false)
        } else {
            false
        }
        active = false

        if (stopped && path != null) {
            runCatching {
                File(path).appendText(
                    "RMG_OBSERVER_V2|event=session|run=$runId|invocation_mode=$invocationMode|transport=$transport\n",
                )
            }
        }
        donePath?.let { pathDone ->
            runCatching {
                File(pathDone).apply {
                    parentFile?.mkdirs()
                    writeText(if (stopped) "done" else "failed")
                }
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (active) {
            outputPath?.let { path -> runCatching { NativeProbe.observerStop(path) } }
            active = false
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.busung.s25uroot.observer.START"
        const val ACTION_ATTACH = "dev.busung.s25uroot.observer.ATTACH"
        const val ACTION_MARKER = "dev.busung.s25uroot.observer.MARKER"
        const val ACTION_STOP = "dev.busung.s25uroot.observer.STOP"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_INVOCATION_MODE = "invocation_mode"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_LOG_PATH = "log_path"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_READY_PATH = "ready_path"
        const val EXTRA_DONE_PATH = "done_path"
        const val EXTRA_PID = "pid"
        const val EXTRA_MARKER = "marker"
    }
}
''')

replace_once(
    'app/src/main/java/dev/busung/s25uroot/NativeProbe.kt',
    '''    /** Attaches the sampler to the launcher/helper PID after the exploit is spawned. */
    external fun observerAttachPid(pid: Long): Boolean

    /** Stops sampling and flushes the preallocated trace buffer after the exploit exits. */''',
    '''    /** Attaches the sampler to the launcher/helper PID after the exploit is spawned. */
    external fun observerAttachPid(pid: Long): Boolean

    /** Feeds a sparse marker from a remote/Shizuku output stream into the observer. */
    external fun observerMarker(line: String): Boolean

    /** Stops sampling and flushes the preallocated trace buffer after the exploit exits. */''',
)

# Native observer: marker delivery is cross-thread inside :observer, so protect
# the text buffer and make the burst deadline atomic.
regex_once(
    'app/src/main/cpp/external_observer.c',
    r'static void observer_append\(const char \*format, \.\.\.\) \{.*?\n\}\n\nstatic ssize_t read_text',
    r'''static pthread_mutex_t observer_buffer_mutex = PTHREAD_MUTEX_INITIALIZER;

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
                          OBS_BUFFER_CAPACITY - observer_length,
                          format, args);
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

static ssize_t read_text''',
)
replace_once(
    'app/src/main/cpp/external_observer.c',
    'static uint64_t observer_burst_until_ms;',
    'static atomic_ullong observer_burst_until_ms;',
)
replace_once(
    'app/src/main/cpp/external_observer.c',
    '''  uint64_t burst_until = now_ms + OBS_BURST_WINDOW_MS;
  if (burst_until > observer_burst_until_ms) observer_burst_until_ms = burst_until;''',
    '''  unsigned long long burst_until = now_ms + OBS_BURST_WINDOW_MS;
  unsigned long long current = atomic_load_explicit(
      &observer_burst_until_ms, memory_order_relaxed);
  while (burst_until > current &&
         !atomic_compare_exchange_weak_explicit(
             &observer_burst_until_ms, &current, burst_until,
             memory_order_relaxed, memory_order_relaxed)) {
  }''',
)
replace_once(
    'app/src/main/cpp/external_observer.c',
    '''    int burst = observer_log_fd >= 0 && now_ms <= observer_burst_until_ms;''',
    '''    unsigned long long burst_until = atomic_load_explicit(
        &observer_burst_until_ms, memory_order_relaxed);
    int burst = now_ms <= burst_until;''',
)
replace_once(
    'app/src/main/cpp/external_observer.c',
    '''  observer_burst_until_ms = 0;
  atomic_store(&observer_target_pid, 0);''',
    '''  atomic_store(&observer_burst_until_ms, 0);
  atomic_store(&observer_target_pid, 0);''',
)
replace_once(
    'app/src/main/cpp/external_observer.c',
    '''JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_observerAttachPid(JNIEnv *env, jobject thiz,
                                                       jlong pid) {
  (void)env;
  (void)thiz;
  if (!atomic_load(&observer_running) || pid <= 0 || pid > INT_MAX) return JNI_FALSE;
  atomic_store_explicit(&observer_target_pid, (int)pid, memory_order_relaxed);
  return JNI_TRUE;
}

static int write_all''',
    '''JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_observerAttachPid(JNIEnv *env, jobject thiz,
                                                       jlong pid) {
  (void)env;
  (void)thiz;
  if (!atomic_load(&observer_running) || pid <= 0 || pid > INT_MAX) return JNI_FALSE;
  atomic_store_explicit(&observer_target_pid, (int)pid, memory_order_relaxed);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_busung_s25uroot_NativeProbe_observerMarker(JNIEnv *env, jobject thiz,
                                                    jstring line) {
  (void)thiz;
  if (!atomic_load(&observer_running) || !line) return JNI_FALSE;
  const char *text = (*env)->GetStringUTFChars(env, line, NULL);
  if (!text) return JNI_FALSE;
  record_log_line(text, boottime_ms());
  (*env)->ReleaseStringUTFChars(env, line, text);
  return JNI_TRUE;
}

static int write_all''',
)

# ---------------------------------------------------------------------------
# Manual runner: start observer only after uptime gate; Shizuku is system-scope
# with sparse markers. Cleanup always runs NonCancellable and never swallows
# CancellationException.
# ---------------------------------------------------------------------------
manual = 'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt'
replace_once(
    manual,
    '''import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay''',
    '''import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay''',
)
replace_once(
    manual,
    '''import kotlinx.coroutines.launch
import java.io.File''',
    '''import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File''',
)
replace_once(
    manual,
    '''        val transport = if (shizuku) "shizuku" else "standalone"
        val externalObserverMode = profile.profileId == CZG3_PROFILE_ID
        val observer = if (externalObserverMode) {
            ExploitObserverSession.start(
                context = app,
                runId = runId,
                invocationMode = invocationMode,
                transport = transport,
                payloadLog = if (shizuku) null else logFile,
            )
        } else {
            null
        }
        observer?.let {
            appendLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport",
            )
        }
        val minimumUptimeSeconds''',
    '''        val transport = if (shizuku) "shizuku" else "standalone"
        val externalObserverMode = profile.profileId == CZG3_PROFILE_ID
        val minimumUptimeSeconds''',
)
replace_once(
    manual,
    '''        val process = try {
            ExploitRunControl.start(''',
    '''        val observer = if (externalObserverMode) {
            ExploitObserverSession.start(
                context = app,
                runId = runId,
                invocationMode = invocationMode,
                transport = transport,
                payloadLog = if (shizuku) null else logFile,
                attachController = !shizuku,
            )
        } else {
            null
        }
        observer?.let {
            appendLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport|scope=${if (shizuku) "system_remote_markers" else "process_tree_system"}",
            )
        }
        val process = try {
            ExploitRunControl.start(''',
)
replace_once(
    manual,
    '''        } catch (error: Throwable) {
            observer?.stopAndCollect()
            throw error
        }''',
    '''        } catch (error: Throwable) {
            stopObserver(observer)
            throw error
        }''',
)
replace_once(
    manual,
    '''            var consumedDiagnosticCharacters = 0
            var supervisorAttempt: Int? = null''',
    '''            var consumedDiagnosticCharacters = 0
            var consumedObserverCharacters = 0
            var supervisorAttempt: Int? = null''',
)
replace_once(
    manual,
    '''                    val parsed = SupervisorAttemptParser.parseNewEvents(
                        rawLog,
                        consumedDiagnosticCharacters,
                        supervisorAttempt,
                    )''',
    '''                    if (externalObserverMode && shizuku) {
                        val markerBatch = ExploitObserverMarkerParser.parseNewLines(
                            rawLog,
                            consumedObserverCharacters,
                        )
                        consumedObserverCharacters = markerBatch.consumedCharacters
                        markerBatch.lines.forEach { observer?.signalMarker(it) }
                    }
                    val parsed = SupervisorAttemptParser.parseNewEvents(
                        rawLog,
                        consumedDiagnosticCharacters,
                        supervisorAttempt,
                    )''',
)
replace_once(
    manual,
    '''            val parsed = SupervisorAttemptParser.parseNewEvents(
                rawLog,
                consumedDiagnosticCharacters,
                supervisorAttempt,
                includeTrailingLine = true,
            )''',
    '''            if (externalObserverMode && shizuku) {
                val markerBatch = ExploitObserverMarkerParser.parseNewLines(
                    rawLog,
                    consumedObserverCharacters,
                    includeTrailingLine = true,
                )
                consumedObserverCharacters = markerBatch.consumedCharacters
                markerBatch.lines.forEach { observer?.signalMarker(it) }
            }
            val parsed = SupervisorAttemptParser.parseNewEvents(
                rawLog,
                consumedDiagnosticCharacters,
                supervisorAttempt,
                includeTrailingLine = true,
            )''',
)
regex_once(
    manual,
    r'''            try \{\n                observer\?\.stopAndCollect\(\)\?\.let \{ report ->\n                    appendLog\(\n                        "RMG_OBSERVER_V2\|event=controller_stop\|available=\$\{report\.available\}\|" \+\n                            "target_pid=\$\{report\.targetPid \?: -1\}",\n                    \)\n                    if \(report\.text\.isNotBlank\(\)\) appendLog\(report\.text\.trimEnd\(\)\)\n                \}\n            \} catch \(observerError: Throwable\) \{\n                appendLog\(\n                    "RMG_OBSERVER_V2\|event=controller_error\|message=" \+\n                        \(observerError\.message \?: observerError\.javaClass\.simpleName\),\n                \)\n            \}''',
    '''            stopObserver(observer)''',
)
replace_once(
    manual,
    '''        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private fun applyDiagnosticEvent(''',
    '''        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private suspend fun stopObserver(observer: ExploitObserverSession?) {
        if (observer == null) return
        val report = try {
            withContext(NonCancellable) { observer.stopAndCollect() }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            appendLog(
                "RMG_OBSERVER_V2|event=controller_error|message=" +
                    (error.message ?: error.javaClass.simpleName),
            )
            return
        }
        appendLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )
        if (report.text.isNotBlank()) appendLog(report.text.trimEnd())
    }

    private fun applyDiagnosticEvent(''',
)

# ---------------------------------------------------------------------------
# Auto Root runner: same lifecycle/cancellation rules and Shizuku marker bridge.
# ---------------------------------------------------------------------------
auto = 'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt'
replace_once(
    auto,
    '''import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds''',
    '''import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds''',
)
replace_once(
    auto,
    '''        val transport = if (useShizuku) "shizuku" else "standalone"
        val externalObserverMode = payloads.profile.profileId == CZG3_PROFILE_ID
        val observer = if (externalObserverMode) {
            ExploitObserverSession.start(
                context = context,
                runId = runId,
                invocationMode = InvocationMode.AutoRoot,
                transport = transport,
                payloadLog = if (useShizuku) null else logFile,
            )
        } else {
            null
        }
        observer?.let {
            onLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport",
            )
        }
        val minimumUptimeSeconds''',
    '''        val transport = if (useShizuku) "shizuku" else "standalone"
        val externalObserverMode = payloads.profile.profileId == CZG3_PROFILE_ID
        val minimumUptimeSeconds''',
)
replace_once(
    auto,
    '''        val process = try {
            ExploitRunControl.start(''',
    '''        val observer = if (externalObserverMode) {
            ExploitObserverSession.start(
                context = context,
                runId = runId,
                invocationMode = InvocationMode.AutoRoot,
                transport = transport,
                payloadLog = if (useShizuku) null else logFile,
                attachController = !useShizuku,
            )
        } else {
            null
        }
        observer?.let {
            onLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport|scope=${if (useShizuku) "system_remote_markers" else "process_tree_system"}",
            )
        }
        val process = try {
            ExploitRunControl.start(''',
)
replace_once(
    auto,
    '''        } catch (error: Throwable) {
            observer?.stopAndCollect()
            throw error
        }''',
    '''        } catch (error: Throwable) {
            stopObserver(observer)
            throw error
        }''',
)
replace_once(
    auto,
    '''        var consumedDiagnosticCharacters = 0
        var supervisorAttempt: Int? = null''',
    '''        var consumedDiagnosticCharacters = 0
        var consumedObserverCharacters = 0
        var supervisorAttempt: Int? = null''',
)
replace_once(
    auto,
    '''            val parsed = SupervisorAttemptParser.parseNewEvents(
                diagnosticLog,
                consumedDiagnosticCharacters,
                supervisorAttempt,
                includeTrailingLine = !process.isAlive,
            )''',
    '''            if (externalObserverMode && useShizuku) {
                val markerBatch = ExploitObserverMarkerParser.parseNewLines(
                    diagnosticLog,
                    consumedObserverCharacters,
                    includeTrailingLine = !process.isAlive,
                )
                consumedObserverCharacters = markerBatch.consumedCharacters
                markerBatch.lines.forEach { observer?.signalMarker(it) }
            }
            val parsed = SupervisorAttemptParser.parseNewEvents(
                diagnosticLog,
                consumedDiagnosticCharacters,
                supervisorAttempt,
                includeTrailingLine = !process.isAlive,
            )''',
)
regex_once(
    auto,
    r'''            try \{\n                observer\?\.stopAndCollect\(\)\?\.let \{ report ->\n                    onLog\(\n                        "RMG_OBSERVER_V2\|event=controller_stop\|available=\$\{report\.available\}\|" \+\n                            "target_pid=\$\{report\.targetPid \?: -1\}",\n                    \)\n                    if \(report\.text\.isNotBlank\(\)\) onLog\(report\.text\.trimEnd\(\)\)\n                \}\n            \} catch \(observerError: Throwable\) \{\n                onLog\(\n                    "RMG_OBSERVER_V2\|event=controller_error\|message=" \+\n                        \(observerError\.message \?: observerError\.javaClass\.simpleName\),\n                \)\n            \}''',
    '''            stopObserver(observer)''',
)
replace_once(
    auto,
    '''        onLog(context.getString(R.string.log_bootstrap_root))
    }

    private suspend fun stageKernelSu(''',
    '''        onLog(context.getString(R.string.log_bootstrap_root))
    }

    private suspend fun stopObserver(observer: ExploitObserverSession?) {
        if (observer == null) return
        val report = try {
            withContext(NonCancellable) { observer.stopAndCollect() }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            onLog(
                "RMG_OBSERVER_V2|event=controller_error|message=" +
                    (error.message ?: error.javaClass.simpleName),
            )
            return
        }
        onLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )
        if (report.text.isNotBlank()) onLog(report.text.trimEnd())
    }

    private suspend fun stageKernelSu(''',
)

# Pure unit tests for the append-only remote marker bridge.
Path('app/src/test/java/dev/busung/s25uroot/ExploitObserverTest.kt').write_text(r'''package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploitObserverTest {
    @Test
    fun markerParserKeepsOnlyRelevantCompleteLines() {
        val log = "noise\n[*] exploit attempt=1/24\npartial p0 pipe oracle"
        val batch = ExploitObserverMarkerParser.parseNewLines(log, 0)
        assertEquals(listOf("[*] exploit attempt=1/24"), batch.lines)
        assertEquals(log.indexOf("partial"), batch.consumedCharacters)
    }

    @Test
    fun markerParserCarriesCursorAcrossPollingBatches() {
        val first = "[*] exploit attempt=1/24\npartial"
        val firstBatch = ExploitObserverMarkerParser.parseNewLines(first, 0)
        val completed = first + " p0 pipe oracle prepared\n"
        val secondBatch = ExploitObserverMarkerParser.parseNewLines(
            completed,
            firstBatch.consumedCharacters,
        )
        assertEquals(listOf("partial p0 pipe oracle prepared"), secondBatch.lines)
        assertEquals(completed.length, secondBatch.consumedCharacters)
    }

    @Test
    fun markerParserCanConsumeTerminalLineWithoutNewline() {
        val log = "[*] exploit completed attempt=1"
        val batch = ExploitObserverMarkerParser.parseNewLines(
            log,
            0,
            includeTrailingLine = true,
        )
        assertEquals(listOf(log), batch.lines)
        assertTrue(batch.consumedCharacters == log.length)
    }
}
''')
