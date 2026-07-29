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
    private const val PRELOAD_LOG = "/data/local/tmp/rmg-rezygisk-preload.log"
    private const val PRELOAD_PID = "/data/local/tmp/rmg-rezygisk-preload.pid"
    private const val RESULT_FILE = "/data/local/tmp/rmg-rezygisk-result"
    private const val PREPARED_MARKER = "RMG_REZYGISK_PREPARED=1"
    private const val ACTIVATION_PREFS = "rezygisk_activation"
    private const val ACTIVATION_BOOT_ID = "scheduled_boot_id"
    private const val ACTIVATION_DETAIL = "detail"
    private const val ACTIVE_MARKER = "RMG_REZYGISK_ACTIVE="
    private const val PENDING_MARKER = "RMG_REZYGISK_PENDING="

    private val activationLock = Any()

    /**
     * Must run while the exploit's temporary root socket is still available, before --late-load.
     * The detached worker keeps uid 0, waits for KernelSU to appear, and performs the ReZygisk
     * monitor handoff without requiring a KernelSU superuser grant for this app.
     */
    fun prepareBeforeKernelSu(context: Context): ReZygiskActivationResult {
        val appContext = context.applicationContext
        val bootId = currentBootId()
            ?: return ReZygiskActivationResult.Failed("current boot ID is unavailable")

        return synchronized(activationLock) {
            val activation = appContext.getSharedPreferences(ACTIVATION_PREFS, Context.MODE_PRIVATE)
            if (activation.getString(ACTIVATION_BOOT_ID, null) == bootId) {
                val activeSchedule = runBootstrapRootCommand(
                    appContext,
                    """
                    if [ -r $PRELOAD_PID ]; then
                        worker_pid=${'$'}(/system/bin/cat $PRELOAD_PID 2>/dev/null)
                        [ -n "${'$'}worker_pid" ] && /system/bin/toybox kill -0 "${'$'}worker_pid" 2>/dev/null && exit 0
                    fi
                    [ -S /data/adb/rezygisk/init_monitor ] && exit 0
                    /system/bin/grep -qE '^(pending|success)$' $RESULT_FILE 2>/dev/null
                    """.trimIndent(),
                )
                if (activeSchedule.code == 0) {
                    return@synchronized ReZygiskActivationResult.AlreadyScheduled(
                        activation.getString(ACTIVATION_DETAIL, null)
                            ?: "ReZygisk bootstrap worker was already scheduled for this boot",
                    )
                }
                activation.edit().clear().commit()
            }

            when (val result = stageBootstrapWorker(appContext)) {
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
                            "unable to persist the ReZygisk bootstrap receipt",
                        )
                    }
                }
            }
        }
    }

    private fun stageBootstrapWorker(context: Context): ReZygiskActivationResult {
        val moduleProbe = discoverModule(context)
        if (moduleProbe.code != 0) {
            return ReZygiskActivationResult.Failed(
                moduleProbe.output.ifBlank {
                    "bootstrap root could not inspect /data/adb/modules " +
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
                        "it is not enabled in /data/adb/modules for this boot.",
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
        val stageCommand = """
            set -e
            /system/bin/cp ${shellQuote(localScript.absolutePath)} $REMOTE_SCRIPT
            /system/bin/chmod 700 $REMOTE_SCRIPT
            /system/bin/sed -i ${shellQuote(modulePathReplacement)} $REMOTE_SCRIPT
            /system/bin/rm -f $PRELOAD_LOG $PRELOAD_PID $RESULT_FILE
            TMP_PATH=/data/adb/rezygisk /system/bin/toybox setsid \
                $REMOTE_SCRIPT preload >>$PRELOAD_LOG 2>&1 </dev/null &
            worker_pid=${'$'}!
            echo "${'$'}worker_pid" > $PRELOAD_PID
            /system/bin/sleep 1
            if ! /system/bin/toybox kill -0 "${'$'}worker_pid" 2>/dev/null; then
                /system/bin/cat $PRELOAD_LOG 2>/dev/null
                exit 43
            fi
            echo "$PREPARED_MARKER"
        """.trimIndent()

        val result = runBootstrapRootCommand(context, stageCommand)
        return if (result.code == 0 && result.output.contains(PREPARED_MARKER)) {
            ReZygiskActivationResult.Scheduled(result.output)
        } else {
            ReZygiskActivationResult.Failed(
                result.output.ifBlank { "bootstrap worker exited with ${result.code}" },
            )
        }
    }

    private fun discoverModule(context: Context): CommandResult {
        val command = """
            uid=${'$'}(/system/bin/id -u 2>/dev/null)
            echo "RMG_ROOT_UID=${'$'}uid"
            if [ "${'$'}uid" != "0" ]; then
                echo "bootstrap command runner did not provide uid 0"
                exit 125
            fi
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
                        [ -x "${'$'}dir/bin/zygisk-ptrace64" ] || {
                            echo "ReZygisk tracer binary is missing at ${'$'}dir/bin/zygisk-ptrace64"
                            exit 44
                        }
                        echo "$ACTIVE_MARKER${'$'}dir"
                    else
                        echo "$PENDING_MARKER${'$'}dir"
                    fi
                done
            done
            exit 0
        """.trimIndent()
        return runBootstrapRootCommand(context, command)
    }

    private fun markerValue(output: String, marker: String): String? = output.lineSequence()
        .firstOrNull { it.startsWith(marker) }
        ?.removePrefix(marker)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun runBootstrapRootCommand(context: Context, command: String): CommandResult {
        val checkedCommand =
            "uid=${'$'}(/system/bin/id -u 2>/dev/null); " +
                "[ \"${'$'}uid\" = \"0\" ] || exit 125; " + command
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME)
        if (!helper.canExecute()) {
            return CommandResult(126, "bootstrap helper is unavailable")
        }

        return try {
            val process = ProcessBuilder(helper.absolutePath, "-c", checkedCommand)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            CommandResult(process.waitFor(), output)
        } catch (error: Throwable) {
            CommandResult(
                126,
                "bootstrap root command failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
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
