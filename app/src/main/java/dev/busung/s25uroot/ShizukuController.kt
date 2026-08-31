package dev.busung.s25uroot

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuController {
    private const val PERMISSION_REQUEST_CODE = 0x5352
    private const val FALLBACK_CHUNK_BYTES = 12 * 1024

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

    /**
     * Stage a file through Shizuku. The normal path streams bytes to a remote `cat` process.
     * On some Samsung builds the exploit can invalidate that remote stdin pipe while the
     * Shizuku binder itself remains usable, which surfaces as EPIPE during KernelSU staging.
     *
     * If the streaming path fails, retry without using the remote process stdin at all:
     * send bounded base64 chunks as command arguments and decode/append them remotely.
     * The fallback always truncates the destination first and removes a partial file on error.
     */
    fun writeFile(remotePath: String, mode: String, source: InputStream) {
        val bytes = source.use { it.readBytes() }
        val streamingFailure = runCatching {
            streamFile(remotePath, mode, bytes)
        }.exceptionOrNull()
        if (streamingFailure == null) return

        check(isRunning() && isGranted()) {
            "Shizuku became unavailable while staging $remotePath: ${streamingFailure.message.orEmpty()}"
        }

        try {
            writeFileWithoutRemoteStdin(remotePath, mode, bytes)
        } catch (fallbackError: Throwable) {
            runCatching { runShellChecked("rm -f '${shellEscape(remotePath)}'") }
            throw IllegalStateException(
                "Failed to stage $remotePath after Shizuku stream failure: " +
                    "${streamingFailure.message.orEmpty()}; fallback: ${fallbackError.message.orEmpty()}",
                fallbackError,
            )
        }
    }

    private fun streamFile(remotePath: String, mode: String, bytes: ByteArray) {
        val escapedPath = shellEscape(remotePath)
        val process = exec(arrayOf("sh", "-c", "cat > '$escapedPath' && chmod $mode '$escapedPath'"))
        val exitCode = try {
            process.outputStream.use { output -> bytes.inputStream().use { it.copyTo(output, DEFAULT_BUFFER_SIZE) } }
            process.waitFor()
        } finally {
            if (process.isAlive) process.destroy()
        }
        check(exitCode == 0) { "Failed to stage $remotePath (exit $exitCode)" }
    }

    private fun writeFileWithoutRemoteStdin(remotePath: String, mode: String, bytes: ByteArray) {
        val escapedPath = shellEscape(remotePath)
        runShellChecked(": > '$escapedPath'")

        var offset = 0
        while (offset < bytes.size) {
            check(isRunning() && isGranted()) { "Shizuku became unavailable during fallback staging" }
            val count = minOf(FALLBACK_CHUNK_BYTES, bytes.size - offset)
            val encoded = Base64.encodeToString(bytes, offset, count, Base64.NO_WRAP)
            runShellChecked("printf '%s' '$encoded' | base64 -d >> '$escapedPath'")
            offset += count
        }

        runShellChecked("chmod $mode '$escapedPath'")
        val remoteSize = capture(arrayOf("sh", "-c", "wc -c < '$escapedPath'"))
            .trim()
            .toLongOrNull()
        check(remoteSize == bytes.size.toLong()) {
            "Staged size mismatch for $remotePath: $remoteSize != ${bytes.size}"
        }
    }

    private fun runShellChecked(command: String) {
        val process = exec(arrayOf("sh", "-c", command))
        val stderr: String
        val exitCode = try {
            stderr = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
        } finally {
            if (process.isAlive) process.destroy()
        }
        check(exitCode == 0) {
            "Shizuku shell command failed (exit $exitCode): ${stderr.trim()}"
        }
    }

    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

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
