package dev.busung.s25uroot

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal enum class AutoRootStage {
    PreparingExploit,
    RunningExploit,
    LoadingKernelSu,
    VerifyingRoot,
}

private data class AutoRootCommandResult(val code: Int, val output: String)

internal class AutoRootRunner(
    private val context: Context,
    private val useShizuku: Boolean,
    private val onStage: (AutoRootStage) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onDiagnostic: (ExploitDiagnosticSnapshot) -> Unit = {},
    private val onSupervisorAttempt: (Int, Long) -> Unit = { _, _ -> },
) {
    suspend fun run(payloads: VerifiedPayloads, bootToken: String, runId: String) {
        val runnerInvokedAt = SystemClock.elapsedRealtime()
        val runnerInvocationContext = AndroidRunContext.snapshot(
            context,
            "autoroot_runner_invocation",
            runnerInvokedAt,
        )
        val requestShizukuRunning = ShizukuController.isRunning()
        val requestShizukuGranted = ShizukuController.isGranted()
        if (useShizuku) {
            require(ShizukuController.isRunning() && ShizukuController.isGranted()) {
                context.getString(R.string.error_shizuku_unavailable)
            }
        }

        onStage(AutoRootStage.PreparingExploit)
        onLog("[*] profile=${payloads.profile.profileId} transport=${if (useShizuku) "shizuku" else "standalone"}")
        onLog("[*] boot_id=$bootToken")
        onLog("[*] exploit_sha256=${payloads.exploit.sha256()}")
        onLog("[*] helper_sha256=${nativeHelperFile().sha256()}")
        onLog(runnerInvocationContext)

        onStage(AutoRootStage.RunningExploit)
        executeExploit(
            payloads,
            bootToken,
            runId,
            runnerInvokedAt,
            runnerInvocationContext,
            requestShizukuRunning,
            requestShizukuGranted,
        )

        onStage(AutoRootStage.LoadingKernelSu)
        stageKernelSu(payloads)

        onStage(AutoRootStage.VerifyingRoot)
        val lateLoad = runHelper("--late-load")
        if (lateLoad.code != 0) throw ExploitRunException(
            ExploitFailureClass.KernelSuVerification,
            ExploitSafety.DoNotRetry,
            context.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output),
        )
        if (lateLoad.output.isNotBlank()) onLog(lateLoad.output)
        onLog(context.getString(R.string.log_ksu_control_verified))
    }

    private suspend fun executeExploit(
        payloads: VerifiedPayloads,
        bootToken: String,
        runId: String,
        requestedAtUptimeMillis: Long,
        requestContext: String,
        requestShizukuRunning: Boolean,
        requestShizukuGranted: Boolean,
    ) {
        val payload = payloads.exploit
        val logFile = if (useShizuku) File(SHIZUKU_LOG_PATH) else File(context.filesDir, "autoroot-exploit.log")
        if (useShizuku) {
            ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
        } else {
            logFile.delete()
        }

        val helper = helperFile()
        if (!useShizuku) {
            require(helper.canExecute()) { context.getString(R.string.error_helper_unavailable) }
        }

        val stagedPayload = if (useShizuku) {
            shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
        } else {
            payload
        }
        val transport = if (useShizuku) "shizuku" else "standalone"
        val externalObserverMode = payloads.profile.profileId == CZG3_PROFILE_ID
        val minimumUptimeSeconds = if (payloads.profile.profileId == CZG3_PROFILE_ID) {
            AppPreferences.czg3BootMinUptimeSeconds(context)
        } else {
            null
        }
        val launchWindow = ExploitRunControl.waitForLaunchWindow(
            requestedAtUptimeMillis,
            minimumUptimeSeconds,
        )
        onLog(
            ExploitRunControl.contextRecord(
                requestContext.replace(
                    "event=autoroot_runner_invocation",
                    "event=autoroot_root_request",
                ),
                InvocationMode.AutoRoot,
                minimumUptimeSeconds,
                launchWindow.waited,
                launchWindow.actualWaitMillis,
                requestShizukuRunning,
                requestShizukuGranted,
                transport,
            ),
        )
        val spawnUptimeMillis = SystemClock.elapsedRealtime()
        onLog(
            ExploitRunControl.contextRecord(
                AndroidRunContext.snapshot(context, "payload_launch", spawnUptimeMillis),
                InvocationMode.AutoRoot,
                minimumUptimeSeconds,
                launchWindow.waited,
                launchWindow.actualWaitMillis,
                ShizukuController.isRunning(),
                ShizukuController.isGranted(),
                transport,
            ),
        )
        val environment = ExploitRunControl.environment(
            ExploitEnvironmentRequest(
                InvocationMode.AutoRoot,
                minimumUptimeSeconds,
                launchWindow,
                spawnUptimeMillis,
                runId,
                cachedP0Offset(bootToken),
            ),
        )
        val observer = if (externalObserverMode) {
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
        val process = try {
            observer?.let {
                onLog(
                    "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                        "transport=$transport|scope=${if (useShizuku) "system_remote_markers" else "process_tree_system"}",
                )
            }
            ExploitRunControl.start(
                useShizuku = useShizuku,
                helper = helper,
                payload = payload,
                logFile = logFile,
                environmentVariables = environment,
                shizukuPayloadPath = stagedPayload.absolutePath,
            )
        } catch (error: Throwable) {
            stopObserver(observer)
            throw error
        }

        val output = ProcessOutputCollector(process)
        val incrementalLog = if (externalObserverMode && !useShizuku) {
            IncrementalFileLogReader(logFile)
        } else {
            null
        }
        val readLog: () -> String = if (useShizuku) {
            { output.snapshot() + readShizukuLog() }
        } else {
            { output.snapshot(); incrementalLog?.snapshot() ?: logFile.readTextIfPresent() }
        }
        var publishedLog = ""
        var consumedDiagnosticCharacters = 0
        var consumedObserverCharacters = 0
        var supervisorAttempt: Int? = null
        var diagnosticSnapshot: ExploitDiagnosticSnapshot? = null
        fun publishNewLog(rawLog: String, terminal: Boolean = false) {
            val clean = stripAnsi(rawLog).trim()
            if ((!externalObserverMode || terminal) && clean.isNotBlank() && clean != publishedLog) {
                val addition = if (clean.startsWith(publishedLog)) {
                    clean.substring(publishedLog.length).trim()
                } else {
                    clean
                }
                if (addition.isNotBlank()) onLog(addition)
                publishedLog = clean
            }

            // Human-readable Shizuku output is a composite of stdout and the
            // helper log file. That composite is not append-only because stdout
            // grows in front of already-read file content. Machine diagnostics
            // therefore consume only the authoritative append-only stdout stream
            // under Shizuku; standalone mode consumes the payload log file.
            val diagnosticLog = if (useShizuku) output.snapshot() else rawLog
            if (externalObserverMode && useShizuku) {
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
            )
            consumedDiagnosticCharacters = parsed.consumedCharacters
            val previousSupervisorAttempt = supervisorAttempt
            supervisorAttempt = parsed.supervisorAttempt
            supervisorAttempt?.let { attempt ->
                if (attempt != previousSupervisorAttempt) {
                    onSupervisorAttempt(
                        attempt,
                        SystemClock.elapsedRealtime() - spawnUptimeMillis,
                    )
                }
            }
            parsed.events.forEach { (event, eventSupervisorAttempt) ->
                val updated = (diagnosticSnapshot ?: ExploitDiagnosticSnapshot(runId)).apply(event)
                    .withSupervisorAttempt(eventSupervisorAttempt)
                diagnosticSnapshot = updated
                onDiagnostic(updated)
            }
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    if (!externalObserverMode) cacheP0Offset(bootToken, rawLog)
                    publishNewLog(rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    context.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    context.getString(R.string.error_exploit_timeout)
                }
                delay(
                    if (useShizuku || externalObserverMode) {
                        SHIZUKU_LOG_POLL_INTERVAL
                    } else {
                        LOG_POLL_INTERVAL
                    },
                )
            }

            val exitCode = process.waitFor()
            output.awaitCompletion()
            val rawLog = readLog()
            cacheP0Offset(bootToken, rawLog)
            publishNewLog(rawLog, terminal = true)
            onLog("[*] stage=RunningExploit exit_code=$exitCode elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            validateTerminalExploit(
                diagnosticSnapshot,
                exitCode,
                rawLog,
                payloads.profile.profileId == CZG3_PROFILE_ID,
            )
        } finally {
            incrementalLog?.close()
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            output.awaitCompletion()
            stopObserver(observer)
        }
        onLog(context.getString(R.string.log_bootstrap_root))
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
        currentCoroutineContext().ensureActive()
        onLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )
        if (report.text.isNotBlank()) onLog(report.text.trimEnd())
    }

    private suspend fun stageKernelSu(payloads: VerifiedPayloads) {
        if (useShizuku) {
            shizukuStage(payloads.kernelSu, SHIZUKU_KSUD_PATH, "755")
            shizukuStage(payloads.kernelSu, SHIZUKU_KSUD_STAGE_PATH, "755")
        } else {
            val source = shellQuote(payloads.kernelSu.absolutePath)
            val stageCommand =
                "/system/bin/cp $source $SHIZUKU_KSUD_PATH && " +
                    "/system/bin/cp $source $SHIZUKU_KSUD_STAGE_PATH && " +
                    "/system/bin/chmod 755 $SHIZUKU_KSUD_PATH $SHIZUKU_KSUD_STAGE_PATH"
            val stage = runHelper("-c", stageCommand)
            if (stage.code != 0) throw ExploitRunException(
                ExploitFailureClass.KernelSuStaging,
                ExploitSafety.DoNotRetry,
                context.getString(R.string.error_ksu_stage, stage.output),
            )
        }
        onLog(context.getString(R.string.log_ksu_staged))
    }

    private fun helperFile(): File =
        if (useShizuku) {
            shizukuStage(nativeHelperFile(), SHIZUKU_HELPER_PATH, "755")
        } else {
            nativeHelperFile()
        }

    private fun nativeHelperFile() = File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private fun shizukuStage(source: File, target: String, mode: String): File {
        val staged = File(target)
        if (stagedFileIsCurrent(staged, source)) return staged
        try {
            ShizukuController.writeFile(target, mode, source.inputStream())
        } catch (error: Throwable) {
            throw IllegalStateException(
                context.getString(R.string.error_shizuku_stage, target, error.message.orEmpty()),
                error,
            )
        }
        return staged
    }

    private suspend fun runHelper(vararg arguments: String): AutoRootCommandResult {
        val helper = helperFile()
        val process = if (useShizuku) {
            ShizukuController.exec(arrayOf(helper.absolutePath) + arguments)
        } else {
            ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        }
        val captured = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (process.isAlive) {
                drainProcessOutput(process, captured)
                require(SystemClock.elapsedRealtime() - startedAt < HELPER_TIMEOUT_MILLIS) {
                    context.getString(
                        R.string.error_helper_timeout,
                        captured.toString().trim().takeIf(String::isNotBlank)
                            ?.let { ": $it" } ?: "",
                    )
                }
                delay(HELPER_POLL_INTERVAL)
            }
            drainProcessOutput(process, captured)
            return AutoRootCommandResult(process.waitFor(), stripAnsi(captured.toString().trim()))
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun drainProcessOutput(process: Process, buffer: StringBuilder): String {
        return try {
            drainStream(process.inputStream, buffer)
            drainStream(process.errorStream, buffer)
            buffer.toString()
        } catch (_: Throwable) {
            buffer.toString()
        }
    }

    private fun readShizukuLog(): String = runCatching {
        val log = ShizukuController.exec(arrayOf("/system/bin/cat", SHIZUKU_LOG_PATH))
        log.inputStream.bufferedReader().use { it.readText() }.also { log.waitFor() }
    }.getOrDefault("")

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        val data = ByteArray(4096)
        while (stream.available() > 0) {
            val count = stream.read(data)
            if (count <= 0) break
            buffer.append(String(data, 0, count, Charsets.UTF_8))
        }
    }

    private fun cachedP0Offset(bootToken: String): String? {
        val stored = context.getSharedPreferences(P0_CACHE, Context.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(P0_CACHE_OFFSET, null)
    }

    private fun cacheP0Offset(bootToken: String, log: String) {
        val match = P0_OFFSET_PATTERN.findAll(log).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        val value = "0x${offset.toString(16)}"
        val stored = context.getSharedPreferences(P0_CACHE, Context.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) == bootToken &&
            stored.getString(P0_CACHE_OFFSET, null) == value
        ) return
        stored.edit()
            .putString(P0_CACHE_BOOT_TOKEN, bootToken)
            .putString(P0_CACHE_OFFSET, value)
            .apply()
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val data = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(data)
            if (count < 0) break
            digest.update(data, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Drains both remote pipes continuously; `available()` can miss their final bytes. */
    private class ProcessOutputCollector(process: Process) {
        private val buffer = StringBuilder()
        private val readers = listOf(process.inputStream, process.errorStream).mapIndexed { index, stream ->
            Thread({
                runCatching {
                    val data = ByteArray(4096)
                    while (true) {
                        val count = stream.read(data)
                        if (count < 0) break
                        synchronized(buffer) { buffer.append(String(data, 0, count, Charsets.UTF_8)) }
                    }
                }
            }, "autoroot-output-$index").apply { start() }
        }

        fun snapshot(): String = synchronized(buffer) { buffer.toString() }

        fun awaitCompletion() = readers.forEach { reader ->
            runCatching { reader.join(2_000) }
        }
    }

    companion object {
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val HELPER_TIMEOUT_MILLIS = 120_000L
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val CZG3_PROFILE_ID = "pa3q-S938BXXSBCZG3"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val SHIZUKU_LOG_PATH = "/data/local/tmp/ksu-exploit.log"
        private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/ksu-helper"
        private const val SHIZUKU_PAYLOAD_PATH = "/data/local/tmp/ksu-payload"
        private const val SHIZUKU_KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val HELPER_POLL_INTERVAL = 250.milliseconds
        private val SHIZUKU_LOG_POLL_INTERVAL = 1.seconds
        private val ANSI_ESCAPE = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
