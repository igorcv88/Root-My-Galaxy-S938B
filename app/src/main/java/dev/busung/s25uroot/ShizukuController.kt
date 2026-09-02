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

data class ShizukuPassiveState(
    val binderAlive: Boolean = false,
    val binderSeenUptimeMillis: Long? = null,
    val binderDeadUptimeMillis: Long? = null,
    val permissionGranted: Boolean? = null,
    val permissionObservedUptimeMillis: Long? = null,
)

object ShizukuController {
    private const val PERMISSION_REQUEST_CODE = 0x5352
    private val FILE_MODE_PATTERN = Regex("[0-7]{3,4}")
    private val stateLock = Any()

    @Volatile
    private var trackingInitialized = false

    @Volatile
    private var passiveState = ShizukuPassiveState()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val now = SystemClock.elapsedRealtime()
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrNull()
        synchronized(stateLock) {
            passiveState = passiveState.copy(
                binderAlive = true,
                binderSeenUptimeMillis = now,
                permissionGranted = granted,
                permissionObservedUptimeMillis = if (granted == null) {
                    passiveState.permissionObservedUptimeMillis
                } else {
                    now
                },
            )
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            passiveState = passiveState.copy(
                binderAlive = false,
                binderDeadUptimeMillis = now,
                permissionGranted = null,
                permissionObservedUptimeMillis = now,
            )
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            passiveState = passiveState.copy(
                permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED,
                permissionObservedUptimeMillis = now,
            )
        }
    }

    /**
     * Register lifecycle listeners once, as early as possible in the main app process.
     * The sticky received listener records an already-delivered Binder without requiring
     * a later ping from the exploit path.
     */
    fun initializePassiveTracking() {
        if (trackingInitialized) return
        synchronized(stateLock) {
            if (trackingInitialized) return
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            trackingInitialized = true
        }
    }

    /**
     * Pure cached read. This deliberately performs no Binder transaction so standalone
     * exploit preparation can observe Shizuku without perturbing its critical window.
     */
    fun passiveState(): ShizukuPassiveState = passiveState

    fun isRunning(): Boolean = passiveState.binderAlive

    fun isGranted(): Boolean = passiveState.permissionGranted == true

    /**
     * Active transport probe used only when the caller explicitly intends to use Shizuku.
     */
    private fun refreshActiveState(): ShizukuPassiveState {
        initializePassiveTracking()
        val now = SystemClock.elapsedRealtime()
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (running) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrNull()
        } else {
            null
        }
        synchronized(stateLock) {
            passiveState = passiveState.copy(
                binderAlive = running,
                binderSeenUptimeMillis = if (running) {
                    passiveState.binderSeenUptimeMillis ?: now
                } else {
                    passiveState.binderSeenUptimeMillis
                },
                binderDeadUptimeMillis = if (!running && passiveState.binderAlive) {
                    now
                } else {
                    passiveState.binderDeadUptimeMillis
                },
                permissionGranted = granted,
                permissionObservedUptimeMillis = if (granted == null) {
                    passiveState.permissionObservedUptimeMillis
                } else {
                    now
                },
            )
            return passiveState
        }
    }

    /**
     * The binder is delivered to the app asynchronously after the Shizuku service starts.
     * This active wait is only used by the explicit Shizuku transport path.
     */
    suspend fun pingUntilRunning(timeoutMillis: Long = 3_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (refreshActiveState().binderAlive) return true
            delay(100)
        }
        return refreshActiveState().binderAlive
    }

    /**
     * Boot-time health probe for callers that explicitly choose Shizuku.
     */
    suspend fun canRunUnattended(timeoutMillis: Long = 1_500): Boolean {
        val state = refreshActiveState()
        if (!state.binderAlive || state.permissionGranted != true) return false
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
        val state = refreshActiveState()
        if (state.permissionGranted == true) return true
        if (!state.binderAlive) return false
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    val now = SystemClock.elapsedRealtime()
                    synchronized(stateLock) {
                        passiveState = passiveState.copy(
                            permissionGranted = granted,
                            permissionObservedUptimeMillis = now,
                        )
                    }
                    continuation.resume(granted)
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
