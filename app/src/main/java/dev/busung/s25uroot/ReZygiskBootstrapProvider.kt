package dev.busung.s25uroot

import android.content.Context
import java.io.File

internal sealed interface ReZygiskActivationResult {
    data object NotInstalled : ReZygiskActivationResult
    data class Scheduled(val message: String) : ReZygiskActivationResult
    data class AlreadyScheduled(val message: String) : ReZygiskActivationResult
    data class Failed(val message: String) : ReZygiskActivationResult
}

internal enum class ReZygiskActivationStage {
    Detected,
    StartingMonitor,
    SoftRebootScheduled,
}

internal object ReZygiskLateLoad {
    private const val SCRIPT_NAME = "rezygisk-late-load.sh"
    private const val REMOTE_SCRIPT = "/data/local/tmp/rmg-rezygisk-late-load.sh"
    private const val SCHEDULED_MARKER = "RMG_REZYGISK_SCHEDULED=1"
    private const val MODULE_NOT_FOUND_MARKER = "RMG_REZYGISK_NOT_FOUND=1"
    private const val ACTIVATION_PREFS = "rezygisk_activation"
    private const val ACTIVATION_BOOT_ID = "scheduled_boot_id"
    private const val ACTIVATION_DETAIL = "detail"

    private val activationLock = Any()

    fun scheduleAfterKernelSu(
        context: Context,
        onStage: (ReZygiskActivationStage) -> Unit = {},
    ): ReZygiskActivationResult {
        val appContext = context.applicationContext
        val bootId = currentBootId()
            ?: return ReZygiskActivationResult.Failed("current boot ID is unavailable")

        return synchronized(activationLock) {
            val activation = appContext.getSharedPreferences(ACTIVATION_PREFS, Context.MODE_PRIVATE)
            if (activation.getString(ACTIVATION_BOOT_ID, null) == bootId) {
                val activeSchedule = runKernelSuCommand(
                    "[ -S /data/adb/rezygisk/init_monitor ] || " +
                        "/system/bin/grep -qE '^(pending|success)$' " +
                        "/data/local/tmp/rmg-rezygisk-result 2>/dev/null",
                )
                if (activeSchedule.code == 0) {
                    return@synchronized ReZygiskActivationResult.AlreadyScheduled(
                        activation.getString(ACTIVATION_DETAIL, null)
                            ?: "ReZygisk activation was already scheduled for this boot",
                    )
                }
                activation.edit().clear().commit()
            }

            when (val result = scheduleActivation(appContext, onStage)) {
                ReZygiskActivationResult.NotInstalled -> result
                is ReZygiskActivationResult.Failed -> result
                is ReZygiskActivationResult.AlreadyScheduled -> result
                is ReZygiskActivationResult.Scheduled -> {
                    val stored = activation.edit()
                        .putString(ACTIVATION_BOOT_ID, bootId)
                        .putString(ACTIVATION_DETAIL, result.message)
                        .commit()
                    if (stored) {
                        result
                    } else {
                        ReZygiskActivationResult.Failed(
                            "unable to persist the ReZygisk activation receipt",
                        )
                    }
                }
            }
        }
    }

    private fun scheduleActivation(
        context: Context,
        onStage: (ReZygiskActivationStage) -> Unit,
    ): ReZygiskActivationResult {
        val moduleProbe = runKernelSuCommand(MODULE_PROBE_COMMAND)
        if (moduleProbe.code != 0) {
            return if (moduleProbe.output.contains(MODULE_NOT_FOUND_MARKER)) {
                ReZygiskActivationResult.NotInstalled
            } else {
                ReZygiskActivationResult.Failed(
                    moduleProbe.output.ifBlank {
                        "ReZygisk module probe exited with ${moduleProbe.code}"
                    },
                )
            }
        }
        onStage(ReZygiskActivationStage.Detected)

        val localScript = File(context.cacheDir, SCRIPT_NAME)
        context.assets.open(SCRIPT_NAME).use { input ->
            localScript.outputStream().use { output -> input.copyTo(output) }
        }
        require(localScript.setExecutable(true, true)) {
            "Unable to mark the ReZygisk bootstrap executable"
        }

        val stageCommand =
            "/system/bin/cp ${shellQuote(localScript.absolutePath)} $REMOTE_SCRIPT && " +
                "/system/bin/chmod 700 $REMOTE_SCRIPT && " +
                "TMP_PATH=/data/adb/rezygisk $REMOTE_SCRIPT schedule"
        onStage(ReZygiskActivationStage.StartingMonitor)
        val result = runKernelSuCommand(stageCommand)
        return if (result.code == 0 && result.output.contains(SCHEDULED_MARKER)) {
            onStage(ReZygiskActivationStage.SoftRebootScheduled)
            ReZygiskActivationResult.Scheduled(result.output)
        } else {
            ReZygiskActivationResult.Failed(
                result.output.ifBlank { "bootstrap exited with ${result.code}" },
            )
        }
    }

    private fun runKernelSuCommand(command: String): CommandResult {
        val candidates = listOf(
            listOf("/system/bin/su", "-c", command),
            listOf("/system/xbin/su", "-c", command),
            listOf("su", "-c", command),
        )
        val failures = mutableListOf<String>()

        for (candidate in candidates) {
            val result = try {
                val process = ProcessBuilder(candidate)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                CommandResult(process.waitFor(), output)
            } catch (error: Throwable) {
                failures += "${candidate.first()}: ${error.message ?: error.javaClass.simpleName}"
                null
            } ?: continue

            if (result.code != 126 && result.code != 127) return result
            failures += "${candidate.first()}: ${result.output.ifBlank { "exit ${result.code}" }}"
        }

        return CommandResult(
            126,
            "KernelSU su shell is unavailable (${failures.joinToString("; ")})",
        )
    }

    private fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class CommandResult(val code: Int, val output: String)

    private const val MODULE_PROBE_COMMAND =
        "uid=\$(/system/bin/id -u 2>/dev/null); echo RMG_ROOT_UID=\$uid; " +
            "if [ \"\$uid\" != 0 ]; then exit 125; fi; " +
            "if [ -d /data/adb/modules/rezygisk ] && " +
            "[ -f /data/adb/modules/rezygisk/module.prop ] && " +
            "[ ! -e /data/adb/modules/rezygisk/disable ] && " +
            "[ ! -e /data/adb/modules/rezygisk/remove ]; then " +
            "echo RMG_REZYGISK_MODULE=/data/adb/modules/rezygisk; exit 0; fi; " +
            "echo RMG_REZYGISK_NOT_FOUND=1; exit 44"
}
