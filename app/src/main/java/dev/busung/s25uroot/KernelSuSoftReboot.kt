package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SoftRebootResult(
    val started: Boolean,
    val detail: String,
)

object KernelSuSoftReboot {
    suspend fun request(context: Context): SoftRebootResult = withContext(Dispatchers.IO) {
        val directHelper = File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        if (directHelper.canExecute()) {
            runCommand(arrayOf(directHelper.absolutePath, "-c", SOFT_REBOOT_SCRIPT)).let { result ->
                if (result.started) return@withContext result
            }
        }

        if (ShizukuController.isRunning() && ShizukuController.isGranted()) {
            val stagedHelper = File(SHIZUKU_HELPER_PATH)
            if (stagedHelper.exists()) {
                runShizuku(arrayOf(stagedHelper.absolutePath, "-c", SOFT_REBOOT_SCRIPT)).let { result ->
                    if (result.started) return@withContext result
                    return@withContext result
                }
            }
        }

        SoftRebootResult(
            started = false,
            detail = "Bootstrap root helper is unavailable for the soft reboot",
        )
    }

    private fun runCommand(command: Array<String>): SoftRebootResult = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        finish(process)
    }.getOrElse { error ->
        SoftRebootResult(false, error.message ?: error.javaClass.simpleName)
    }

    private fun runShizuku(command: Array<String>): SoftRebootResult = runCatching {
        finish(ShizukuController.exec(command))
    }.getOrElse { error ->
        SoftRebootResult(false, error.message ?: error.javaClass.simpleName)
    }

    private fun finish(process: Process): SoftRebootResult {
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            return SoftRebootResult(false, "KernelSU soft reboot command timed out")
        }
        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .trim()
        val code = process.exitValue()
        return SoftRebootResult(
            started = code == 0,
            detail = output.ifBlank { "KernelSU soft reboot returned $code" },
        )
    }

    private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/ksu-helper"
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    private const val SOFT_REBOOT_SCRIPT = """
set -eu
group=/sys/fs/cgroup/rmg-keepers
hold_seen=0
for comm in /proc/[0-9]*/comm; do
  [ -r "$comm" ] || continue
  name=$(cat "$comm" 2>/dev/null) || continue
  [ "$name" = "cve43499-hold" ] || continue
  hold_seen=$((hold_seen + 1))
  pid=${comm#/proc/}
  pid=${pid%/comm}
  [ -r "/proc/$pid/cgroup" ] || exit 41
  grep -Fq '/rmg-keepers' "/proc/$pid/cgroup" || exit 42
done
[ "$hold_seen" -gt 0 ] || exit 43
[ -d "$group" ] || exit 44
[ -x /data/local/tmp/ksud-s25u-kdp ] || exit 45
exec /data/local/tmp/ksud-s25u-kdp soft-reboot
"""
}
