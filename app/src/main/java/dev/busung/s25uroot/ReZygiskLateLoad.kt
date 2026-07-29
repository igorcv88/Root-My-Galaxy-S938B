package dev.busung.s25uroot

import android.content.Context
import java.io.File

internal sealed interface ReZygiskRuntimeState {
    data object None : ReZygiskRuntimeState
    data object Pending : ReZygiskRuntimeState
    data object Success : ReZygiskRuntimeState
    data class Failed(val message: String) : ReZygiskRuntimeState
}

internal data class ReZygiskBridgeSnapshot(
    val detected: Boolean,
    val monitorStarting: Boolean,
    val softRebootScheduled: Boolean,
    val success: Boolean,
    val failure: String?,
)

internal object ReZygiskLateLoad {
    const val ARMED_MARKER = "RMG_REZYGISK_BRIDGE_ARMED=1"

    private const val ARM_FILE = "/data/local/tmp/rmg-rezygisk-arm"
    private const val STATUS_FILE_NAME = "rezygisk-bridge-status"
    private const val BOOT_PREFIX = "BOOT_ID="
    private const val DETECTED_MARKER = "BRIDGE_DETECTED=1"
    private const val MONITOR_MARKER = "MONITOR_STARTING=1"
    private const val REBOOT_MARKER = "SOFT_REBOOT_SCHEDULED=1"
    private const val SUCCESS_MARKER = "SUCCESS=1"
    private const val FAILURE_PREFIX = "FAILURE="
    private const val ROLLBACK_PREFIX = "ROLLBACK="

    fun prepareArmCommand(context: Context, bootId: String): String {
        val status = statusFile(context)
        status.parentFile?.mkdirs()
        status.writeText("$BOOT_PREFIX$bootId\n")
        status.setReadable(true, true)
        status.setWritable(true, true)

        return "/system/bin/printf '%s\\n%s\\n' " +
            "${shellQuote(bootId)} ${shellQuote(status.absolutePath)} > $ARM_FILE && " +
            "/system/bin/chmod 600 $ARM_FILE && /system/bin/echo $ARMED_MARKER"
    }

    fun clearArmCommand(): String = "/system/bin/rm -f $ARM_FILE"

    fun snapshot(context: Context): ReZygiskBridgeSnapshot {
        val bootId = currentBootId()
        val lines = statusFile(context).readLinesIfPresent()
        if (bootId == null || lines.none { it == "$BOOT_PREFIX$bootId" }) {
            return ReZygiskBridgeSnapshot(false, false, false, false, null)
        }

        val failure = lines.asReversed().firstNotNullOfOrNull { line ->
            when {
                line.startsWith(FAILURE_PREFIX) -> line.removePrefix(FAILURE_PREFIX).trim()
                line.startsWith(ROLLBACK_PREFIX) -> line.removePrefix(ROLLBACK_PREFIX).trim()
                else -> null
            }
        }?.takeIf(String::isNotBlank)

        return ReZygiskBridgeSnapshot(
            detected = DETECTED_MARKER in lines,
            monitorStarting = MONITOR_MARKER in lines,
            softRebootScheduled = REBOOT_MARKER in lines,
            success = SUCCESS_MARKER in lines,
            failure = failure,
        )
    }

    fun runtimeState(context: Context): ReZygiskRuntimeState {
        val snapshot = snapshot(context)
        return when {
            snapshot.success -> ReZygiskRuntimeState.Success
            snapshot.failure != null -> ReZygiskRuntimeState.Failed(snapshot.failure)
            snapshot.detected || snapshot.monitorStarting || snapshot.softRebootScheduled -> {
                ReZygiskRuntimeState.Pending
            }
            else -> ReZygiskRuntimeState.None
        }
    }

    fun recordLocalFailure(context: Context, message: String) {
        runCatching { statusFile(context).appendText("$FAILURE_PREFIX$message\n") }
    }

    private fun statusFile(context: Context): File = File(context.filesDir, STATUS_FILE_NAME)

    private fun File.readLinesIfPresent(): List<String> = runCatching {
        if (isFile) readLines().map(String::trim).filter(String::isNotBlank) else emptyList()
    }.getOrDefault(emptyList())

    private fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
