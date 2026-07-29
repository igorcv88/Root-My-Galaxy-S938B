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

internal sealed interface ReZygiskRuntimeState {
    data object None : ReZygiskRuntimeState
    data object Pending : ReZygiskRuntimeState
    data object Success : ReZygiskRuntimeState
    data class Failed(val message: String) : ReZygiskRuntimeState
}

internal object ReZygiskLateLoad {
    private const val HELPER_NAME = "libcve43499root.so"
    private const val SCRIPT_NAME = "rezygisk-late-load.sh"
    private const val REMOTE_SCRIPT = "/data/local/tmp/rmg-rezygisk-late-load.sh"
    private const val RESULT_FILE = "/data/local/tmp/rmg-rezygisk-result"
    private const val SCHEDULED_MARKER = "RMG_REZYGISK_SCHEDULED=1"
    private const val ROOT_READY_MARKER = "RMG_AUTHORIZED_ROOT=1"
    private const val ACTIVE_MARKER = "RMG_REZYGISK_ACTIVE="
    private const val PENDING_MARKER = "RMG_REZYGISK_PENDING="

    fun requestRootAuthorization(context: Context) {
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME)
        if (!helper.canExecute()) return
        runCatching {
            val process = ProcessBuilder(
                helper.absolutePath,
                "-c",
                "/system/bin/id -u >/dev/null 2>&1",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        }
    }

    fun hasAuthorizedRoot(context: Context): Boolean {
        val result = runAuthorizedCommand(context, ":")
        return result.code == 0
    }

    fun runtimeState(context: Context): ReZygiskRuntimeState {
        val result = runAuthorizedCommand(
            context,
            """
            if [ -r $RESULT_FILE ]; then
                value=${'$'}(/system/bin/cat $RESULT_FILE 2>/dev/null)
                echo "RMG_RESULT=${'$'}value"
            elif [ -S /data/adb/rezygisk/init_monitor ]; then
                echo "RMG_RESULT=pending"
            else
                echo "RMG_RESULT=none"
            fi
            """.trimIndent(),
        )
        if (result.code != 0) return ReZygiskRuntimeState.None
        val value = result.output.lineSequence()
            .firstOrNull { it.startsWith("RMG_RESULT=") }
            ?.removePrefix("RMG_RESULT=")
            ?.trim()
            .orEmpty()
        return when {
            value == "success" -> ReZygiskRuntimeState.Success
            value == "pending" -> ReZygiskRuntimeState.Pending
            value.startsWith("failure:") || value.startsWith("rollback") -> {
                ReZygiskRuntimeState.Failed(value)
            }
            else -> ReZygiskRuntimeState.None
        }
    }

    fun scheduleAfterAuthorization(
        context: Context,
        onStage: (ReZygiskActivationStage) -> Unit = {},
    ): ReZygiskActivationResult {
        val existing = runAuthorizedCommand(
            context,
            "[ -S /data/adb/rezygisk/init_monitor ] || " +
                "/system/bin/grep -qE '^(pending|success)$' $RESULT_FILE 2>/dev/null",
        )
        if (existing.code == 0) {
            return ReZygiskActivationResult.AlreadyScheduled(
                "ReZygisk activation is already running for this boot",
            )
        }

        val moduleProbe = discoverModule(context)
        if (moduleProbe.code != 0) {
            return ReZygiskActivationResult.Failed(
                moduleProbe.output.ifBlank {
                    "authorized root could not inspect /data/adb/modules " +
                        "(probe exited with ${moduleProbe.code})"
                },
            )
        }

        val activePath = markerValue(moduleProbe.output, ACTIVE_MARKER)
        if (activePath == null) {
            val pendingPath = markerValue(moduleProbe.output, PENDING_MARKER)
            return if (pendingPath != null) {
                ReZygiskActivationResult.Failed(
                    "ReZygisk is installed but still pending at $pendingPath; " +
                        "enable it in KernelSU before retrying.",
                )
            } else {
                ReZygiskActivationResult.NotInstalled
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

        val modulePathReplacement = "s|^MODULE_DIR=.*|MODULE_DIR=${escapeSedReplacement(activePath)}|"
        val stageCommand =
            "/system/bin/cp ${shellQuote(localScript.absolutePath)} $REMOTE_SCRIPT && " +
                "/system/bin/chmod 700 $REMOTE_SCRIPT && " +
                "/system/bin/sed -i ${shellQuote(modulePathReplacement)} $REMOTE_SCRIPT && " +
                "TMP_PATH=/data/adb/rezygisk $REMOTE_SCRIPT schedule"
        onStage(ReZygiskActivationStage.StartingMonitor)
        val result = runAuthorizedCommand(context, stageCommand)
        return if (result.code == 0 && result.output.contains(SCHEDULED_MARKER)) {
            onStage(ReZygiskActivationStage.SoftRebootScheduled)
            ReZygiskActivationResult.Scheduled(result.output)
        } else {
            ReZygiskActivationResult.Failed(
                result.output.ifBlank { "bootstrap exited with ${result.code}" },
            )
        }
    }

    private fun discoverModule(context: Context): CommandResult {
        val command = """
            for root in /data/adb/modules /data/adb/modules_update; do
                [ -d "${'$'}root" ] || continue
                for dir in "${'$'}root"/*; do
                    [ -d "${'$'}dir" ] || continue
                    prop="${'$'}dir/module.prop"
                    [ -f "${'$'}prop" ] || continue
                    module_id=${'$'}(/system/bin/toybox sed -n 's/^id=//p' "${'$'}prop" | /system/bin/toybox head -n 1)
                    module_name=${'$'}(/system/bin/toybox sed -n 's/^name=//p' "${'$'}prop" | /system/bin/toybox head -n 1)
                    match=0
                    [ "${'$'}dir" = "/data/adb/modules/rezygisk" ] && match=1
                    [ "${'$'}module_id" = "rezygisk" ] && match=1
                    case "${'$'}module_name" in ReZygisk*) match=1 ;; esac
                    [ "${'$'}match" = "1" ] || continue
                    if [ "${'$'}root" = "/data/adb/modules" ]; then
                        [ -e "${'$'}dir/disable" ] && continue
                        [ -e "${'$'}dir/remove" ] && continue
                        echo "$ACTIVE_MARKER${'$'}dir"
                    else
                        echo "$PENDING_MARKER${'$'}dir"
                    fi
                done
            done
            exit 0
        """.trimIndent()
        return runAuthorizedCommand(context, command)
    }

    private fun runAuthorizedCommand(context: Context, command: String): CommandResult {
        val checkedCommand = """
            uid=${'$'}(/system/bin/id -u 2>/dev/null)
            [ "${'$'}uid" = "0" ] || exit 125
            /system/bin/ls /data/adb/modules >/dev/null 2>&1 || exit 126
            echo "$ROOT_READY_MARKER"
            $command
        """.trimIndent()
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME)
        val candidates = buildList {
            if (helper.canExecute()) add(listOf(helper.absolutePath, "-c", checkedCommand))
            add(listOf("/system/bin/su", "-c", checkedCommand))
            add(listOf("/system/xbin/su", "-c", checkedCommand))
            add(listOf("su", "-c", checkedCommand))
        }
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

            if (result.output.contains(ROOT_READY_MARKER)) {
                return result.copy(output = result.output.replace(ROOT_READY_MARKER, "").trim())
            }
            failures += "${candidate.first()}: ${result.output.ifBlank { "exit ${result.code}" }}"
        }

        return CommandResult(
            126,
            "KernelSU root permission is unavailable (${failures.joinToString("; ")})",
        )
    }

    private fun markerValue(output: String, marker: String): String? = output.lineSequence()
        .firstOrNull { it.startsWith(marker) }
        ?.removePrefix(marker)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun escapeSedReplacement(value: String): String = value
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("&", "\\&")

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class CommandResult(val code: Int, val output: String)
}
