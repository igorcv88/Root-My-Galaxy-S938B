package dev.busung.s25uroot

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


data class SoftRebootResult(
    val started: Boolean,
    val detail: String,
)

/**
 * Soft reboot handoff that keeps the bootstrap-root connection open across
 * KernelSU late-load.
 *
 * The bootstrap helper is a client of /data/local/tmp/temp_su.sock; it is not
 * itself a permanently-root process. Once KernelSU late-load changes the live
 * security state, a fresh app -> bootstrap-daemon connection can be denied.
 * Therefore the root shell is opened before late-load, left blocked on stdin,
 * and released only after the caller has verified KernelSU and persisted the
 * successful-root receipt.
 */
object KernelSuSoftReboot {
    private val lock = Any()
    private var armedProcess: Process? = null
    private var armFailure: String? = null

    /**
     * Open a bootstrap-root shell while the original daemon is still reachable.
     * This is intentionally called after ksud staging but before --late-load.
     * Failure to arm does not fail root acquisition; request() will surface the
     * reason after KernelSU has otherwise completed successfully.
     */
    suspend fun arm(
        context: Context,
        helper: File,
        useShizuku: Boolean,
    ) = withContext(Dispatchers.IO) {
        cancel()

        if (!useShizuku && !helper.canExecute()) {
            recordArmFailure("Bootstrap root helper is unavailable")
            return@withContext
        }

        val process = runCatching {
            if (useShizuku) {
                ShizukuController.exec(
                    arrayOf(helper.absolutePath, "-c", ARM_SCRIPT),
                )
            } else {
                ProcessBuilder(helper.absolutePath, "-c", ARM_SCRIPT)
                    .redirectErrorStream(true)
                    .start()
            }
        }.getOrElse { error ->
            recordArmFailure(error.message ?: error.javaClass.simpleName)
            return@withContext
        }

        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val deadline = System.nanoTime() + ARM_HANDSHAKE_TIMEOUT_NANOS
        var armed = false

        try {
            while (System.nanoTime() < deadline && process.isAlive) {
                drainAvailable(process.inputStream, output)
                if (useShizuku) drainAvailable(process.errorStream, errorOutput)
                if (output.contains(ARM_MARKER)) {
                    armed = true
                    break
                }
                Thread.sleep(ARM_HANDSHAKE_POLL_MILLIS)
            }
            drainAvailable(process.inputStream, output)
            if (useShizuku) drainAvailable(process.errorStream, errorOutput)
            if (output.contains(ARM_MARKER)) armed = true

            if (!armed) {
                val detail = buildString {
                    append(output.toString().trim())
                    val stderr = errorOutput.toString().trim()
                    if (stderr.isNotBlank()) {
                        if (isNotBlank()) append("; ")
                        append(stderr)
                    }
                }.ifBlank {
                    if (process.isAlive) {
                        "Bootstrap root soft-reboot arm handshake timed out"
                    } else {
                        val code = runCatching { process.exitValue() }.getOrDefault(-1)
                        "Bootstrap root soft-reboot arm exited $code"
                    }
                }
                destroy(process)
                recordArmFailure(detail)
                return@withContext
            }

            synchronized(lock) {
                armedProcess = process
                armFailure = null
            }
            scheduleExpiry(process)
        } catch (error: Throwable) {
            destroy(process)
            recordArmFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Release the root shell that was opened before KernelSU late-load. At this
     * point the caller has already verified KernelSU and persisted success, so
     * no second connection to the bootstrap daemon is needed.
     */
    suspend fun request(context: Context): SoftRebootResult = withContext(Dispatchers.IO) {
        val process: Process?
        val failure: String?
        synchronized(lock) {
            process = armedProcess
            armedProcess = null
            failure = armFailure
            armFailure = null
        }

        if (process == null) {
            return@withContext SoftRebootResult(
                false,
                failure ?: "Soft reboot root session was not armed before KernelSU late-load",
            )
        }

        return@withContext runCatching {
            process.outputStream.use { input ->
                input.write(TRIGGER.toByteArray(Charsets.US_ASCII))
                input.flush()
            }
            SoftRebootResult(true, "KernelSU soft reboot trigger delivered")
        }.getOrElse { error ->
            destroy(process)
            SoftRebootResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    fun cancel() {
        val process = synchronized(lock) {
            val current = armedProcess
            armedProcess = null
            armFailure = null
            current
        }
        if (process != null) destroy(process)
    }

    private fun recordArmFailure(detail: String) {
        synchronized(lock) {
            armedProcess = null
            armFailure = detail.take(240)
        }
    }

    private fun scheduleExpiry(process: Process) {
        Thread({
            try {
                Thread.sleep(ARM_LIFETIME_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val expired = synchronized(lock) {
                if (armedProcess === process) {
                    armedProcess = null
                    armFailure = "Soft reboot root session expired before KernelSU verification"
                    true
                } else {
                    false
                }
            }
            if (expired) destroy(process)
        }, "rmg-soft-reboot-arm").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainAvailable(stream: java.io.InputStream, output: StringBuilder) {
        val buffer = ByteArray(1024)
        while (stream.available() > 0) {
            val count = stream.read(buffer)
            if (count <= 0) break
            output.append(String(buffer, 0, count, Charsets.UTF_8))
        }
    }

    private fun destroy(process: Process) {
        runCatching { process.outputStream.close() }
        if (process.isAlive) process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    private const val ARM_MARKER = "RMG_SOFT_REBOOT_ARMED"
    private const val TRIGGER = "go\n"
    private const val ARM_HANDSHAKE_POLL_MILLIS = 20L
    private const val ARM_HANDSHAKE_TIMEOUT_NANOS = 3_000_000_000L
    private const val ARM_LIFETIME_MILLIS = 150_000L

    private val ARM_SCRIPT = """
set -eu
printf '%s\n' '$ARM_MARKER'
IFS= read -r trigger || exit 64
[ "${'$'}trigger" = "go" ] || exit 64
ksud=/data/local/tmp/ksud-s25u-kdp
if [ ! -x "${'$'}ksud" ]; then
  ksud=/data/adb/ksud
fi
[ -x "${'$'}ksud" ] || exit 45
exec "${'$'}ksud" soft-reboot
""".trimIndent()
}
