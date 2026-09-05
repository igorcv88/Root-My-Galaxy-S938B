package dev.busung.s25uroot

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


data class SoftRebootResult(
    val started: Boolean,
    val detail: String,
)

data class SoftRebootArmResult(
    val armed: Boolean,
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
 *
 * The release path mirrors KernelSU Manager's important property: soft reboot is
 * launched from a KernelSU global-mount root shell (`ksud debug su -g`). The app
 * does not call a second bootstrap `su` connection after late-load.
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
    ): SoftRebootArmResult = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext

        cancel()

        if (!useShizuku && !helper.canExecute()) {
            val detail = "Bootstrap root helper is unavailable"
            recordArmFailure(detail)
            return@withContext SoftRebootArmResult(false, detail)
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
            val detail = error.message ?: error.javaClass.simpleName
            recordArmFailure(detail)
            return@withContext SoftRebootArmResult(false, detail)
        }

        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val deadline = System.nanoTime() + ARM_HANDSHAKE_TIMEOUT_NANOS
        var armed = false

        try {
            while (System.nanoTime() < deadline && process.isAlive) {
                drainAvailable(process.inputStream, output)
                drainAvailable(process.errorStream, errorOutput)
                if (output.contains(ARM_MARKER)) {
                    armed = true
                    break
                }
                Thread.sleep(ARM_HANDSHAKE_POLL_MILLIS)
            }
            drainAvailable(process.inputStream, output)
            drainAvailable(process.errorStream, errorOutput)
            if (output.contains(ARM_MARKER)) armed = true

            if (!armed) {
                val detail = combinedOutput(output, errorOutput).ifBlank {
                    if (process.isAlive) {
                        "Bootstrap root soft-reboot arm handshake timed out"
                    } else {
                        val code = runCatching { process.exitValue() }.getOrDefault(-1)
                        "Bootstrap root soft-reboot arm exited $code"
                    }
                }
                destroy(process)
                recordArmFailure(detail)
                return@withContext SoftRebootArmResult(false, detail.take(400))
            }

            synchronized(lock) {
                armedProcess = process
                armFailure = null
            }
            scheduleExpiry(process)
            SoftRebootArmResult(true, "Bootstrap root soft-reboot session armed")
        } catch (error: Throwable) {
            destroy(process)
            val detail = error.message ?: error.javaClass.simpleName
            recordArmFailure(detail)
            SoftRebootArmResult(false, detail)
        }
    }

    /**
     * Release the root shell that was opened before KernelSU late-load.
     *
     * Writing the trigger alone is NOT success. After the trigger, this waits for
     * the launch marker/exit status and then looks for the exact state transition
     * performed at the start of KernelSU's soft_reboot(): sys.boot_completed=0.
     * If Android never begins that transition, the caller receives a failure with
     * the captured command output instead of a false "launched" result.
     */
    suspend fun request(context: Context): SoftRebootResult = withContext(Dispatchers.IO) {
        // Root acquisition is already complete here. Persist the launch intent now,
        // before userspace can be stopped, so History never ends at a misleading
        // bare "Installation complete" line again.
        recordHistoryEvent(context, "[*] Launching KernelSU soft reboot")

        val process: Process?
        val failure: String?
        synchronized(lock) {
            process = armedProcess
            armedProcess = null
            failure = armFailure
            armFailure = null
        }

        if (process == null) {
            val result = SoftRebootResult(
                false,
                failure ?: "Soft reboot root session was not armed before KernelSU late-load",
            )
            recordHistoryEvent(context, "[-] Soft reboot did not start: ${result.detail}")
            return@withContext result
        }

        recordHistoryEvent(context, "[+] Bootstrap root soft-reboot session was armed")

        val output = StringBuilder()
        val errorOutput = StringBuilder()
        drainAvailable(process.inputStream, output)
        drainAvailable(process.errorStream, errorOutput)

        val triggerFailure = runCatching {
            process.outputStream.use { input ->
                input.write(TRIGGER.toByteArray(Charsets.US_ASCII))
                input.flush()
            }
        }.exceptionOrNull()
        if (triggerFailure != null) {
            destroy(process)
            val result = SoftRebootResult(
                false,
                "Soft reboot trigger failed: ${triggerFailure.message ?: triggerFailure.javaClass.simpleName}",
            )
            recordHistoryEvent(context, "[-] Soft reboot did not start: ${result.detail}")
            return@withContext result
        }

        val deadline = System.nanoTime() + START_VERIFY_TIMEOUT_NANOS
        var sawExec = false
        var exitCode: Int? = null
        var lastBootCompleted: String? = null

        while (System.nanoTime() < deadline) {
            drainAvailable(process.inputStream, output)
            drainAvailable(process.errorStream, errorOutput)
            if (output.contains(EXEC_MARKER)) sawExec = true

            if (!process.isAlive && exitCode == null) {
                exitCode = runCatching { process.exitValue() }.getOrNull()
            }

            if (sawExec) {
                lastBootCompleted = readSystemPropertyBounded("sys.boot_completed")
                if (lastBootCompleted == "0") {
                    val result = SoftRebootResult(
                        true,
                        "KernelSU soft reboot started; sys.boot_completed reset to 0",
                    )
                    recordHistoryEvent(context, "[+] ${result.detail}")
                    return@withContext result
                }
            }

            if (exitCode != null && exitCode != 0) break
            Thread.sleep(START_VERIFY_POLL_MILLIS)
        }

        drainAvailable(process.inputStream, output)
        drainAvailable(process.errorStream, errorOutput)
        if (!process.isAlive && exitCode == null) {
            exitCode = runCatching { process.exitValue() }.getOrNull()
        }

        val captured = combinedOutput(output, errorOutput)
            .lineSequence()
            .filterNot { it == ARM_MARKER }
            .joinToString(" | ")
            .take(700)
        val detail = when {
            !sawExec -> buildString {
                append("KernelSU soft reboot command never reached the launch point")
                exitCode?.let { append(" (exit $it)") }
                if (captured.isNotBlank()) append(": $captured")
            }
            exitCode != null && exitCode != 0 -> buildString {
                append("KernelSU global-root soft reboot launcher exited $exitCode")
                if (captured.isNotBlank()) append(": $captured")
            }
            else -> buildString {
                append("KernelSU accepted the soft-reboot handoff")
                exitCode?.let { append(" (exit $it)") }
                append(", but sys.boot_completed never reset to 0")
                lastBootCompleted?.let { append(" (last=$it)") }
                if (captured.isNotBlank()) append(": $captured")
            }
        }

        if (process.isAlive) destroy(process)
        val result = SoftRebootResult(false, detail)
        recordHistoryEvent(context, "[-] Soft reboot did not start: $detail")
        result
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
            armFailure = detail.take(400)
        }
    }

    /**
     * Append post-root soft-reboot status to the just-completed successful run.
     * This is deliberately outside the exploit path and is best-effort: logging
     * can never turn a successful root into a failure.
     */
    private fun recordHistoryEvent(context: Context, line: String) {
        runCatching {
            val store = InstallHistoryStore(context.applicationContext)
            val now = System.currentTimeMillis()
            val entry = store.load().firstOrNull { candidate ->
                candidate.result == InstallRunResult.Succeeded &&
                    candidate.completedAtMillis?.let { completed ->
                        now >= completed && now - completed <= HISTORY_MATCH_WINDOW_MILLIS
                    } == true
            } ?: return@runCatching
            val updated = entry.copy(log = (entry.log + "\n" + line.trim()).trim())
            store.save(updated)
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

    /**
     * Read a property without the old blocking-read bug. We never read stdout
     * until getprop has exited; a stuck child is forcibly destroyed on deadline.
     */
    private fun readSystemPropertyBounded(name: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name)
            .redirectErrorStream(true)
            .start()
        val deadline = System.nanoTime() + PROPERTY_READ_TIMEOUT_NANOS
        while (process.isAlive && System.nanoTime() < deadline) {
            Thread.sleep(PROPERTY_READ_POLL_MILLIS)
        }
        if (process.isAlive) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()

    private fun combinedOutput(stdout: StringBuilder, stderr: StringBuilder): String =
        buildString {
            append(stdout.toString().trim())
            val error = stderr.toString().trim()
            if (error.isNotBlank()) {
                if (isNotBlank()) append("\n")
                append(error)
            }
        }

    private fun drainAvailable(stream: java.io.InputStream, output: StringBuilder) {
        val buffer = ByteArray(2048)
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
    private const val EXEC_MARKER = "RMG_SOFT_REBOOT_EXEC"
    private const val EXIT_MARKER = "RMG_SOFT_REBOOT_EXIT"
    private const val TRIGGER = "go\n"
    private const val ARM_HANDSHAKE_POLL_MILLIS = 20L
    private const val ARM_HANDSHAKE_TIMEOUT_NANOS = 3_000_000_000L
    private const val ARM_LIFETIME_MILLIS = 150_000L
    private const val START_VERIFY_POLL_MILLIS = 75L
    private const val START_VERIFY_TIMEOUT_NANOS = 8_000_000_000L
    private const val PROPERTY_READ_POLL_MILLIS = 10L
    private const val PROPERTY_READ_TIMEOUT_NANOS = 300_000_000L
    private const val HISTORY_MATCH_WINDOW_MILLIS = 5 * 60 * 1_000L

    private val ARM_SCRIPT = """
set -u
printf '%s\n' '$ARM_MARKER'
IFS= read -r trigger || exit 64
[ "${'$'}trigger" = "go" ] || exit 64
ksud=/data/local/tmp/ksud-s25u-kdp
if [ ! -x "${'$'}ksud" ]; then
  ksud=/data/adb/ksud
fi
if [ ! -x "${'$'}ksud" ]; then
  printf '%s code=45 reason=ksud-not-found\n' '$EXIT_MARKER'
  exit 45
fi
printf '%s ksud=%s\n' '$EXEC_MARKER' "${'$'}ksud"
printf 'exec %s soft-reboot\n' "${'$'}ksud" | "${'$'}ksud" debug su -g
rc=${'$'}?
printf '%s code=%s\n' '$EXIT_MARKER' "${'$'}rc"
exit "${'$'}rc"
""".trimIndent()
}
