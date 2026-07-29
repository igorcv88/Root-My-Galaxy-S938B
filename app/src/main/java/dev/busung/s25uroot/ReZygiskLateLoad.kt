package dev.busung.s25uroot

import android.content.Context
import java.io.File

internal sealed interface ReZygiskActivationResult {
    data object NotInstalled : ReZygiskActivationResult
    data class Scheduled(val message: String) : ReZygiskActivationResult
    data class AlreadyScheduled(val message: String) : ReZygiskActivationResult
    data class Failed(val message: String) : ReZygiskActivationResult
}

internal object ReZygiskLateLoad {
    private const val HELPER_NAME = "libcve43499root.so"
    private const val SCRIPT_NAME = "rezygisk-late-load.sh"
    private const val REMOTE_SCRIPT = "/data/local/tmp/rmg-rezygisk-late-load.sh"
    private const val SCHEDULED_MARKER = "RMG_REZYGISK_SCHEDULED=1"
    private const val ACTIVATION_PREFS = "rezygisk_activation"
    private const val ACTIVATION_BOOT_ID = "scheduled_boot_id"
    private const val ACTIVATION_DETAIL = "detail"
    private const val ACTIVE_MARKER = "RMG_REZYGISK_ACTIVE="
    private const val PENDING_MARKER = "RMG_REZYGISK_PENDING="

    private val activationLock = Any()

    fun scheduleAfterKernelSu(context: Context): ReZygiskActivationResult {
        val appContext = context.applicationContext
        val bootId = currentBootId()
            ?: return ReZygiskActivationResult.Failed("current boot ID is unavailable")

        return synchronized(activationLock) {
            val activation = appContext.getSharedPreferences(ACTIVATION_PREFS, Context.MODE_PRIVATE)
            if (activation.getString(ACTIVATION_BOOT_ID, null) == bootId) {
                return@synchronized ReZygiskActivationResult.AlreadyScheduled(
                    activation.getString(ACTIVATION_DETAIL, null)
                        ?: "ReZygisk activation was already scheduled for this boot",
                )
            }

            when (val result = scheduleActivation(appContext)) {
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

    private fun scheduleActivation(context: Context): ReZygiskActivationResult {
        val moduleProbe = discoverModule(context)
        if (moduleProbe.code != 0) {
            return ReZygiskActivationResult.Failed(
                moduleProbe.output.ifBlank {
                    "unable to inspect /data/adb/modules (probe exited with ${moduleProbe.code})"
                },
            )
        }

        val activePath = markerValue(moduleProbe.output, ACTIVE_MARKER)
        if (activePath == null) {
            val pendingPath = markerValue(moduleProbe.output, PENDING_MARKER)
            return if (pendingPath != null) {
                ReZygiskActivationResult.Failed(
                    "ReZygisk is installed but still pending at $pendingPath. " +
                        "KernelSU late-load did not promote it into /data/adb/modules for this boot.",
                )
            } else {
                ReZygiskActivationResult.NotInstalled
            }
        }

        val localScript = File(context.cacheDir, SCRIPT_NAME)
        context.assets.open(SCRIPT_NAME).use { input ->
            localScript.outputStream().use { output -> input.copyTo(output) }
        }
        require(localScript.setExecutable(true, true)) {
            "Unable to mark the ReZygisk bootstrap executable"
        }

        val modulePathReplacement = "s|^MODULE_DIR=.*|MODULE_DIR=${escapeSedReplacement(activePath)}|"
        val stageCommand =
            "/system/bin/cp ${shellQuote(localScript.absolutePath)} $REMOTE_SCRIPT && " +
                "/system/bin/chmod 700 $REMOTE_SCRIPT && " +
                "/system/bin/sed -i ${shellQuote(modulePathReplacement)} $REMOTE_SCRIPT && " +
                "TMP_PATH=/data/adb/rezygisk $REMOTE_SCRIPT schedule"
        val result = runRootCommand(context, stageCommand)
        return if (result.code == 0 && result.output.contains(SCHEDULED_MARKER)) {
            ReZygiskActivationResult.Scheduled(result.output)
        } else {
            ReZygiskActivationResult.Failed(
                result.output.ifBlank { "bootstrap exited with ${result.code}" },
            )
        }
    }

    private fun discoverModule(context: Context): CommandResult {
        val command = """
            if ! /system/bin/ls /data/adb/modules >/dev/null 2>&1; then
                echo "ReZygisk probe cannot read /data/adb/modules"
                exit 41
            fi
            for root in /data/adb/modules /data/adb/modules_update; do
                [ -d "${'$'}root" ] || continue
                for dir in "${'$'}root"/*; do
                    [ -d "${'$'}dir" ] || continue
                    prop="${'$'}dir/module.prop"
                    [ -f "${'$'}prop" ] || continue
                    module_id=${'$'}(/system/bin/sed -n 's/^id=//p' "${'$'}prop" | /system/bin/head -n 1)
                    module_name=${'$'}(/system/bin/sed -n 's/^name=//p' "${'$'}prop" | /system/bin/head -n 1)
                    if [ "${'$'}module_id" = "rezygisk" ] || [ "${'$'}module_name" = "ReZygisk" ]; then
                        if [ "${'$'}root" = "/data/adb/modules" ]; then
                            echo "$ACTIVE_MARKER${'$'}dir"
                        else
                            echo "$PENDING_MARKER${'$'}dir"
                        fi
                    fi
                done
            done
            exit 0
        """.trimIndent()
        return runRootCommand(context, command)
    }

    private fun markerValue(output: String, marker: String): String? = output.lineSequence()
        .firstOrNull { it.startsWith(marker) }
        ?.removePrefix(marker)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun runRootCommand(context: Context, command: String): CommandResult {
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

    private fun escapeSedReplacement(value: String): String = value
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("&", "\\&")

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class CommandResult(val code: Int, val output: String)
}
