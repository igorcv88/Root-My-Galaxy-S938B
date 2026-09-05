package dev.busung.s25uroot

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal enum class AutoRootStage {
    PreparingExploit,
    RunningExploit,
    LoadingKernelSu,
    VerifyingRoot,
}

private data class AutoRootCommandResult(val code: Int, val output: String)

/**
 * Auto Root executes the last-known-good payload in a fresh standalone process.
 * The payload spawn deliberately mirrors the working Manual standalone path for
 * timeout variables and the per-boot P0 offset hint, while keeping all persistence
 * outside the active race window.
 */
internal class AutoRootRunner(
    private val context: Context,
    private val onStage: (AutoRootStage) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    suspend fun run(payloads: VerifiedPayloads, bootToken: String) {
        require(payloads.source == PayloadSource.Offline) {
            "Auto Root requires the last-known-good offline payload"
        }

        onStage(AutoRootStage.PreparingExploit)
        onLog("[*] profile=${payloads.profile.profileId} transport=standalone source=offline")

        onStage(AutoRootStage.RunningExploit)
        executeExploit(payloads.exploit, bootToken)

        onStage(AutoRootStage.LoadingKernelSu)
        stageKernelSu(payloads)

        if (AppPreferences.softRebootAfterRoot(context)) {
            val arm = KernelSuSoftReboot.arm(context, helperFile(), false)
            if (arm.armed) {
                onLog("[+] ${arm.detail}")
            } else {
                onLog("[!] Soft reboot arm failed: ${arm.detail}")
            }
        }

        onStage(AutoRootStage.VerifyingRoot)
        val lateLoad = runHelper("--late-load")
        require(lateLoad.code == 0) {
            context.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
        }
        if (lateLoad.output.isNotBlank()) onLog(lateLoad.output)
        onLog(context.getString(R.string.log_ksu_control_verified))
    }

    private suspend fun executeExploit(payload: File, bootToken: String) {
        val logFile = File(context.filesDir, "autoroot-exploit.log")
        logFile.delete()

        val helper = helperFile()
        require(helper.canExecute()) { context.getString(R.string.error_helper_unavailable) }

        val processBuilder = ProcessBuilder(
            helper.absolutePath,
            "--run-payload",
            payload.absolutePath,
            helper.absolutePath,
            logFile.absolutePath,
        ).redirectErrorStream(true)
        processBuilder.environment().apply {
            put("EXPLOIT_ATTEMPTS", EXPLOIT_ATTEMPTS)
            put("P0_ATTEMPT_TIMEOUT_SEC", P0_ATTEMPT_TIMEOUT_SEC)
            put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", EXPLOIT_ATTEMPT_TIMEOUT_SEC)
            // This is the same safe pre-spawn hint used by the working Manual
            // standalone path. It is ignored when no P0 result exists for this
            // exact kernel boot.
            cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
        }

        val originalThreadPriority = runCatching {
            Process.getThreadPriority(Process.myTid())
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)

        // Raise only the fork/exec window so the child inherits the best public
        // app-side nice value. Lower the polling thread after the child exists.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val process = try {
            processBuilder.start().also {
                val lowered = runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                }.isSuccess
                if (!lowered) {
                    runCatching { Process.setThreadPriority(originalThreadPriority) }
                }
            }
        } catch (error: Throwable) {
            runCatching { Process.setThreadPriority(originalThreadPriority) }
            throw error
        }

        val captured = StringBuilder()
        fun readLog(): String {
            drainProcessOutput(process, captured)
            return logFile.readTextIfPresent()
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    publishExploitLog(rawLog)
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
                delay(LOG_POLL_INTERVAL)
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            publishExploitLog(rawLog)

            // Preserve the useful P0 discovery only after the payload process is
            // completely out of the race. No SharedPreferences write is performed
            // while the exploit is active.
            cacheP0Offset(bootToken, rawLog)

            val earlyOutput = captured.toString().trim()
            require(exitCode == 0) {
                context.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
                context.getString(R.string.error_success_marker)
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            runCatching { Process.setThreadPriority(originalThreadPriority) }
        }
        onLog(context.getString(R.string.log_bootstrap_root))
    }

    private suspend fun stageKernelSu(payloads: VerifiedPayloads) {
        val source = shellQuote(payloads.kernelSu.absolutePath)
        val stageCommand =
            "/system/bin/cp $source $KSUD_PATH && " +
                "/system/bin/cp $source $KSUD_STAGE_PATH && " +
                "/system/bin/chmod 755 $KSUD_PATH $KSUD_STAGE_PATH"
        val stage = runHelper("-c", stageCommand)
        require(stage.code == 0) { context.getString(R.string.error_ksu_stage, stage.output) }
        onLog(context.getString(R.string.log_ksu_staged))
    }

    private fun helperFile() =
        File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private suspend fun runHelper(vararg arguments: String): AutoRootCommandResult {
        val process = ProcessBuilder(listOf(helperFile().absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
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

    private fun drainProcessOutput(process: java.lang.Process, buffer: StringBuilder) {
        try {
            drainStream(process.inputStream, buffer)
            drainStream(process.errorStream, buffer)
        } catch (_: Throwable) {
            // The on-disk exploit log remains the source of truth for the payload.
        }
    }

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        val data = ByteArray(4096)
        while (stream.available() > 0) {
            val count = stream.read(data)
            if (count <= 0) break
            buffer.append(String(data, 0, count, Charsets.UTF_8))
        }
    }

    private fun publishExploitLog(rawLog: String) {
        val clean = stripAnsi(rawLog).trim()
        if (clean.isNotBlank()) onLog(clean)
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

    companion object {
        private const val EXPLOIT_ATTEMPTS = "24"
        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"
        private const val EXPLOIT_ATTEMPT_TIMEOUT_SEC = "120"
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val HELPER_TIMEOUT_MILLIS = 120_000L
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_ENV = "SLIDE_P0_OFFSET"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val HELPER_POLL_INTERVAL = 250.milliseconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
