package dev.busung.s25uroot

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

enum class AutoRootStage {
    CheckingFirmware,
    PreparingExploit,
    RunningExploit,
    LoadingKernelSu,
    VerifyingRoot,
    RootRestored,
}

class AutoRootRunner(
    context: Context,
    private val onStage: (AutoRootStage) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val app = context.applicationContext
    private val repository = PayloadRepository(app)

    suspend fun run() {
        onStage(AutoRootStage.CheckingFirmware)
        val profile = repository.resolveCachedTarget(DeviceSnapshot.current())
        log(app.getString(R.string.log_profile, profile.profileId))

        onStage(AutoRootStage.PreparingExploit)
        val payloads = repository.cachedPayloads(profile) { log("[*] $it") }

        onStage(AutoRootStage.RunningExploit)
        executeExploit(payloads.exploit)

        onStage(AutoRootStage.LoadingKernelSu)
        stageKernelSu(payloads.kernelSu)

        onStage(AutoRootStage.VerifyingRoot)
        verifyKernelSu()

        onStage(AutoRootStage.RootRestored)
    }

    private suspend fun executeExploit(payload: File) {
        val logFile = File(app.filesDir, "auto-root-exploit.log")
        logFile.delete()
        val helper = helperFile()
        require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
        val bootToken = currentBootToken()
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
            cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
        }
        val process = processBuilder.start()
        val captured = StringBuilder()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                drainProcessOutput(process, captured)
                val rawLog = logFile.readTextIfPresent()
                if (rawLog != lastRawLog) {
                    cacheP0Offset(bootToken, rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(LOG_POLL_INTERVAL)
            }

            drainProcessOutput(process, captured)
            val exitCode = process.waitFor()
            val rawLog = logFile.readTextIfPresent()
            cacheP0Offset(bootToken, rawLog)
            val earlyOutput = captured.toString().trim()
            require(exitCode == 0) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
                app.getString(R.string.error_success_marker)
            }
            log(app.getString(R.string.log_bootstrap_root))
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private suspend fun stageKernelSu(kernelSu: File) {
        val source = shellQuote(kernelSu.absolutePath)
        val stageCommand =
            "/system/bin/cp $source $KSUD_PATH && " +
                "/system/bin/cp $source $KSUD_STAGE_PATH && " +
                "/system/bin/chmod 755 $KSUD_PATH $KSUD_STAGE_PATH"
        val stage = runHelper("-c", stageCommand)
        require(stage.code == 0) { app.getString(R.string.error_ksu_stage, stage.output) }
        log(app.getString(R.string.log_ksu_staged))
    }

    private suspend fun verifyKernelSu() {
        val lateLoad = runHelper("--late-load")
        require(lateLoad.code == 0) {
            app.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
        }
        if (lateLoad.output.isNotBlank()) log(lateLoad.output)
        storeInstallReceipt()
        log(app.getString(R.string.log_ksu_control_verified))
        log(app.getString(R.string.log_install_complete))
    }

    private suspend fun runHelper(vararg arguments: String): CommandResult {
        val helper = helperFile()
        val process = ProcessBuilder(listOf(helper.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val captured = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (process.isAlive) {
                drainProcessOutput(process, captured)
                require(SystemClock.elapsedRealtime() - startedAt < HELPER_TIMEOUT_MILLIS) {
                    app.getString(
                        R.string.error_helper_timeout,
                        captured.toString().trim().takeIf(String::isNotBlank)
                            ?.let { ": $it" } ?: "",
                    )
                }
                delay(HELPER_POLL_INTERVAL)
            }
            drainProcessOutput(process, captured)
            val exitCode = process.waitFor()
            return CommandResult(exitCode, stripAnsi(captured.toString().trim()))
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun drainProcessOutput(process: Process, buffer: StringBuilder) {
        runCatching { drainStream(process.inputStream, buffer) }
        runCatching { drainStream(process.errorStream, buffer) }
    }

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        val data = ByteArray(4096)
        while (stream.available() > 0) {
            val count = stream.read(data)
            if (count <= 0) break
            buffer.append(String(data, 0, count, Charsets.UTF_8))
        }
    }

    private fun storeInstallReceipt() {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        val stored = app.getSharedPreferences(INSTALL_RECEIPT, Context.MODE_PRIVATE)
            .edit()
            .putString(RECEIPT_BOOT_TOKEN, bootToken)
            .putBoolean(RECEIPT_VERIFIED, true)
            .commit()
        require(stored) { app.getString(R.string.error_receipt) }
    }

    private fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun cachedP0Offset(bootToken: String?): String? {
        if (bootToken == null) return null
        val stored = app.getSharedPreferences(P0_CACHE, Context.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(P0_CACHE_OFFSET, null)
    }

    private fun cacheP0Offset(bootToken: String?, log: String) {
        if (bootToken == null) return
        val match = P0_OFFSET_PATTERN.findAll(log).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        val value = "0x${offset.toString(16)}"
        val stored = app.getSharedPreferences(P0_CACHE, Context.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) == bootToken &&
            stored.getString(P0_CACHE_OFFSET, null) == value
        ) return
        stored.edit()
            .putString(P0_CACHE_BOOT_TOKEN, bootToken)
            .putString(P0_CACHE_OFFSET, value)
            .apply()
    }

    private fun helperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

    private fun log(line: String) {
        val clean = stripAnsi(line).trim()
        if (clean.isNotBlank()) onLog(clean)
    }

    private data class CommandResult(val code: Int, val output: String)

    companion object {
        private const val EXPLOIT_ATTEMPTS = "24"
        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"
        private const val EXPLOIT_ATTEMPT_TIMEOUT_SEC = "120"
        private const val EXPLOIT_STALL_MILLIS = 90_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val HELPER_TIMEOUT_MILLIS = 120_000L
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
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

        private fun stripAnsi(value: String): String =
            ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
