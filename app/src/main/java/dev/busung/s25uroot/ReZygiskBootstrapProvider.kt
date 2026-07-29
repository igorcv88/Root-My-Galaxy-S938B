package dev.busung.s25uroot

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ReZygiskBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        if (started.compareAndSet(false, true)) {
            Thread(
                { watchForVerifiedKernelSu(appContext) },
                "rmg-rezygisk-bootstrap",
            ).apply {
                isDaemon = true
                start()
            }
        }
        return true
    }

    private fun watchForVerifiedKernelSu(context: android.content.Context) {
        val bootId = currentBootId() ?: return
        val activation = context.getSharedPreferences(ACTIVATION_PREFS, android.content.Context.MODE_PRIVATE)
        if (activation.getString(ACTIVATION_BOOT_ID, null) == bootId) return

        repeat(POLL_LIMIT) {
            try {
                if (NativeProbe.isKernelSuActive() && installReceiptVerified(context, bootId)) {
                    when (scheduleActivation(context)) {
                        ActivationResult.NotInstalled -> Unit
                        is ActivationResult.Failed -> {
                            Log.e(TAG, "ReZygisk activation was not scheduled: ${scheduleActivation(context).message}")
                            return
                        }
                        is ActivationResult.Scheduled -> {
                            activation.edit()
                                .putString(ACTIVATION_BOOT_ID, bootId)
                                .putString(ACTIVATION_DETAIL, scheduleActivation(context).message)
                                .commit()
                            Log.i(TAG, "ReZygisk late-load activation scheduled")
                            return
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "ReZygisk activation probe failed", error)
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun installReceiptVerified(context: android.content.Context, bootId: String): Boolean {
        val receipt = context.getSharedPreferences(INSTALL_RECEIPT, android.content.Context.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootId &&
            receipt.getBoolean(RECEIPT_VERIFIED, false)
    }

    private fun scheduleActivation(context: android.content.Context): ActivationResult {
        val moduleProbe = runRootCommand(
            context,
            "[ -d /data/adb/modules/rezygisk ] && " +
                "[ ! -e /data/adb/modules/rezygisk/disable ] && " +
                "[ ! -e /data/adb/modules/rezygisk/remove ]",
        )
        if (moduleProbe.code != 0) return ActivationResult.NotInstalled

        val localScript = File(context.cacheDir, SCRIPT_NAME)
        context.assets.open(SCRIPT_NAME).use { input ->
            localScript.outputStream().use(input::copyTo)
        }
        require(localScript.setExecutable(true, true)) { "Unable to mark the ReZygisk bootstrap executable" }

        val stageCommand =
            "/system/bin/cp ${shellQuote(localScript.absolutePath)} $REMOTE_SCRIPT && " +
                "/system/bin/chmod 700 $REMOTE_SCRIPT && " +
                "$REMOTE_SCRIPT schedule"
        val result = runRootCommand(context, stageCommand)
        return if (result.code == 0 && result.output.contains(SCHEDULED_MARKER)) {
            ActivationResult.Scheduled(result.output)
        } else {
            ActivationResult.Failed(result.output.ifBlank { "bootstrap exited with ${result.code}" })
        }
    }

    private fun runRootCommand(context: android.content.Context, command: String): CommandResult {
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME)
        if (!helper.canExecute()) return CommandResult(126, "bootstrap helper is unavailable")
        val process = ProcessBuilder(helper.absolutePath, "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return CommandResult(process.waitFor(), output)
    }

    private fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class CommandResult(val code: Int, val output: String)

    private sealed interface ActivationResult {
        data object NotInstalled : ActivationResult
        data class Scheduled(val message: String) : ActivationResult
        data class Failed(val message: String) : ActivationResult
    }

    companion object {
        private const val TAG = "RmgReZygisk"
        private const val HELPER_NAME = "libcve43499root.so"
        private const val SCRIPT_NAME = "rezygisk-late-load.sh"
        private const val REMOTE_SCRIPT = "/data/local/tmp/rmg-rezygisk-late-load.sh"
        private const val SCHEDULED_MARKER = "RMG_REZYGISK_SCHEDULED=1"
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
        private const val ACTIVATION_PREFS = "rezygisk_activation"
        private const val ACTIVATION_BOOT_ID = "scheduled_boot_id"
        private const val ACTIVATION_DETAIL = "detail"
        private const val POLL_LIMIT = 900
        private const val POLL_INTERVAL_MILLIS = 1_000L
        private val started = AtomicBoolean(false)
    }
}
