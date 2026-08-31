package dev.busung.s25uroot

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuController {
    private const val PERMISSION_REQUEST_CODE = 0x5352
    private val FILE_MODE_PATTERN = Regex("[0-7]{3,4}")

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /**
     * The binder is delivered to the app asynchronously after the Shizuku service starts.
     * Wait a short while in case the service is already up but the binder has not arrived yet.
     */
    suspend fun pingUntilRunning(timeoutMillis: Long = 3_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRunning()) return true
            delay(100)
        }
        return isRunning()
    }

    fun isGranted(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Boot-time Auto Root must not treat a merely-present Binder as a healthy transport.
     * Exercise remote process creation and the stdin pipe with a tiny command. This catches
     * stale/half-started Shizuku sessions and broken Binder streams before an exploit attempt
     * is claimed, allowing the caller to fall back to standalone instead of failing mid-run.
     */
    suspend fun canRunUnattended(timeoutMillis: Long = 1_500): Boolean {
        if (!isGranted()) return false
        val process = try {
            exec(arrayOf("/system/bin/sh", "-c", "cat >/dev/null"))
        } catch (_: Throwable) {
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        return try {
            try {
                process.outputStream.use { output ->
                    output.write(byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'G'.code.toByte()))
                    output.flush()
                }
            } catch (_: Throwable) {
                return false
            }

            while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
                delay(50)
            }
            if (process.isAlive) {
                false
            } else {
                runCatching { process.exitValue() == 0 }.getOrDefault(false)
            }
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    suspend fun requestPermission(): Boolean {
        if (isGranted()) return true
        if (!isRunning()) return false
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (error: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                continuation.resumeWithException(error)
            }
        }
    }

    fun exec(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku binder is not available")
        return RemoteProcess(IShizukuService.Stub.asInterface(binder).newProcess(cmd, env, dir))
    }

    /**
     * Runs a short command and returns its combined output. Used to read files the app
     * process cannot access directly because SELinux confines app UIDs away from the
     * shell-owned /data/local/tmp directory.
     */
    fun capture(cmd: Array<String>): String {
        val process = exec(cmd)
        return try {
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) stdout + stderr else ""
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    fun writeFile(remotePath: String, mode: String, source: InputStream) {
        require(FILE_MODE_PATTERN.matches(mode)) { "Invalid file mode: $mode" }

        // Never truncate the final path directly. A previous standalone/root run can
        // leave it owned by root and non-writable by Shizuku's shell UID. Upload into
        // a shell-owned temporary path first, then publish it in a separate command
        // only after the Binder stream and remote `cat` have both completed cleanly.
        // Keeping upload and publication separate also guarantees that a partial read
        // or EPIPE cannot turn a normal EOF in `cat` into a truncated final payload.
        val tempPath = "$remotePath.shizuku-${UUID.randomUUID()}.tmp"
        val quotedPath = shellQuote(remotePath)
        val quotedTemp = shellQuote(tempPath)
        val uploadCommand = "rm -f $quotedTemp && cat > $quotedTemp"
        val upload = exec(arrayOf("/system/bin/sh", "-c", uploadCommand))

        val bytesCopied = try {
            source.use { input ->
                upload.outputStream.use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
        } catch (error: Throwable) {
            if (upload.isAlive) upload.destroy()
            runCatching { upload.waitFor() }
            val stderr = readStderr(upload)
            cleanupTemp(quotedTemp)
            val detail = stderr.ifBlank { error.message ?: error.javaClass.simpleName }
            throw IllegalStateException(
                "Failed to stage $remotePath during upload: $detail",
                error,
            )
        }

        try {
            val uploadExit = upload.waitFor()
            val uploadStderr = readStderr(upload)
            if (uploadExit != 0) {
                val detail = uploadStderr.ifBlank { "exit $uploadExit" }
                throw IllegalStateException(
                    "Failed to stage $remotePath during upload (exit $uploadExit): $detail",
                )
            }

            val finalizeCommand = """
                target=$quotedPath
                tmp=$quotedTemp
                cleanup() { rm -f "${'$'}tmp"; }
                trap cleanup EXIT HUP INT TERM
                actual=$(/system/bin/wc -c < "${'$'}tmp") || exit 1
                if [ "${'$'}actual" -ne $bytesCopied ]; then
                    echo "staged size mismatch: expected $bytesCopied, got ${'$'}actual" >&2
                    exit 1
                fi
                chmod $mode "${'$'}tmp" &&
                mv -f "${'$'}tmp" "${'$'}target"
            """.trimIndent()

            val finalize = exec(arrayOf("/system/bin/sh", "-c", finalizeCommand))
            try {
                val finalizeExit = finalize.waitFor()
                val finalizeStderr = readStderr(finalize)
                if (finalizeExit != 0) {
                    val detail = finalizeStderr.ifBlank { "exit $finalizeExit" }
                    throw IllegalStateException(
                        "Failed to publish $remotePath (exit $finalizeExit): $detail",
                    )
                }
            } finally {
                if (finalize.isAlive) finalize.destroy()
            }
        } finally {
            if (upload.isAlive) upload.destroy()
            cleanupTemp(quotedTemp)
        }
    }

    private fun readStderr(process: Process): String = runCatching {
        process.errorStream.bufferedReader().use { it.readText() }.trim()
    }.getOrDefault("")

    private fun cleanupTemp(quotedTemp: String) {
        runCatching {
            val cleanup = exec(arrayOf("/system/bin/sh", "-c", "rm -f $quotedTemp"))
            try {
                cleanup.waitFor()
            } finally {
                if (cleanup.isAlive) cleanup.destroy()
            }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private class RemoteProcess(private val remote: IRemoteProcess) : Process() {
        private val input by lazy { ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream()) }
        private val output by lazy { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()) }
        private val error by lazy { ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream()) }

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int = remote.waitFor()
        override fun exitValue(): Int = remote.exitValue()

        override fun destroy() {
            runCatching { remote.destroy() }
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = remote.alive()
    }
}
