package dev.busung.s25uroot

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
        // leave it owned by root and non-writable by Shizuku's shell UID, causing the
        // remote `cat` to exit immediately while the app is still writing stdin. The
        // resulting client-side symptom is EPIPE (Broken pipe), which hides the actual
        // permission failure. Stage to a shell-owned temporary file and atomically
        // replace the destination instead; directory ownership lets shell replace an
        // older root-owned entry in /data/local/tmp.
        val quotedPath = shellQuote(remotePath)
        val command = """
            target=$quotedPath
            tmp="${'$'}target.shizuku.${'$'}${'$'}"
            cleanup() { rm -f "${'$'}tmp"; }
            trap cleanup EXIT HUP INT TERM
            rm -f "${'$'}tmp" &&
            cat > "${'$'}tmp" &&
            chmod $mode "${'$'}tmp" &&
            mv -f "${'$'}tmp" "${'$'}target"
        """.trimIndent()

        val process = exec(arrayOf("/system/bin/sh", "-c", command))
        var writeFailure: Throwable? = null
        try {
            try {
                source.use { input ->
                    process.outputStream.use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
            } catch (error: Throwable) {
                writeFailure = error
            }

            val exitCode = process.waitFor()
            val stderr = runCatching {
                process.errorStream.bufferedReader().use { it.readText() }.trim()
            }.getOrDefault("")

            if (exitCode != 0 || writeFailure != null) {
                val detail = when {
                    stderr.isNotBlank() -> stderr
                    writeFailure != null -> writeFailure.message ?: writeFailure.javaClass.simpleName
                    else -> "exit $exitCode"
                }
                throw IllegalStateException(
                    "Failed to stage $remotePath (exit $exitCode): $detail",
                    writeFailure,
                )
            }
        } finally {
            if (process.isAlive) process.destroy()
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
