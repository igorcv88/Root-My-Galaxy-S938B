package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SoftRebootResult(
    val started: Boolean,
    val detail: String,
)

object KernelSuSoftReboot {
    suspend fun request(context: Context): SoftRebootResult = withContext(Dispatchers.IO) {
        var lastFailure: SoftRebootResult? = null
        val directHelper = File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        if (directHelper.canExecute()) {
            val result = runCommand(arrayOf(directHelper.absolutePath, "-c", SOFT_REBOOT_SCRIPT))
            if (result.started) return@withContext result
            lastFailure = result
        }

        if (ShizukuController.isRunning() && ShizukuController.isGranted()) {
            val result = runShizuku(arrayOf(SHIZUKU_HELPER_PATH, "-c", SOFT_REBOOT_SCRIPT))
            if (result.started) return@withContext result
            lastFailure = result
        }

        lastFailure ?: SoftRebootResult(
            started = false,
            detail = "Bootstrap root helper is unavailable for the soft reboot",
        )
    }

    private suspend fun runCommand(command: Array<String>): SoftRebootResult = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        finish(process)
    }.getOrElse { error ->
        SoftRebootResult(false, error.message ?: error.javaClass.simpleName)
    }

    private suspend fun runShizuku(command: Array<String>): SoftRebootResult = runCatching {
        finish(ShizukuController.exec(command))
    }.getOrElse { error ->
        SoftRebootResult(false, error.message ?: error.javaClass.simpleName)
    }

    private suspend fun finish(process: Process): SoftRebootResult {
        val bootCompletedBefore = readBootCompleted()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            return SoftRebootResult(false, "KernelSU soft reboot command timed out")
        }
        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .trim()
        val code = process.exitValue()
        if (code != 0) {
            return SoftRebootResult(
                started = false,
                detail = output.ifBlank { "KernelSU soft reboot returned $code" },
            )
        }

        // `ksud soft-reboot` daemonizes and its launcher can return 0 before
        // Android userspace has actually stopped.  Do not mistake a successful
        // launcher exit (or KernelSU's historical UAPI-mismatch no-op) for a
        // real restart.  The native implementation resets sys.boot_completed
        // to 0 immediately before `stop`, so observing that transition is a
        // reliable acknowledgement while this app process is still alive.
        if (bootCompletedBefore == "1") {
            repeat(BOOT_RESET_POLL_COUNT) {
                if (readBootCompleted() == "0") {
                    return SoftRebootResult(
                        started = true,
                        detail = output.ifBlank { "KernelSU userspace restart acknowledged" },
                    )
                }
                delay(BOOT_RESET_POLL_MILLIS)
            }
            return SoftRebootResult(
                started = false,
                detail = listOf(
                    output.takeIf(String::isNotBlank),
                    "KernelSU command exited 0 but sys.boot_completed never reset to 0",
                ).filterNotNull().joinToString("\n"),
            )
        }

        // BOOT_COMPLETED was not 1 before the request, so the property cannot
        // be used as an edge detector.  Preserve the command result rather than
        // inventing a failure in this unusual state.
        return SoftRebootResult(
            started = true,
            detail = output.ifBlank { "KernelSU soft reboot command accepted" },
        )
    }

    private fun readBootCompleted(): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", "sys.boot_completed")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor(2, TimeUnit.SECONDS)
        output
    }.getOrDefault("")

    private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/ksu-helper"
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val BOOT_RESET_POLL_COUNT = 40
    private const val BOOT_RESET_POLL_MILLIS = 100L

    private val SOFT_REBOOT_SCRIPT = """
set -u
trace=/data/local/tmp/rmg-soft-reboot-last
: > "${'$'}trace" 2>/dev/null || true
log() {
  printf '%s\n' "${'$'}1"
  printf '%s\n' "${'$'}1" >> "${'$'}trace" 2>/dev/null || true
}
log "RMG_SOFT_REBOOT_V1|stage=preflight|boot_completed=${'$'}(getprop sys.boot_completed 2>/dev/null)"
group=/sys/fs/cgroup/rmg-keepers
hold_seen=0
for comm in /proc/[0-9]*/comm; do
  [ -r "${'$'}comm" ] || continue
  name=${'$'}(cat "${'$'}comm" 2>/dev/null) || continue
  [ "${'$'}name" = "cve43499-hold" ] || continue
  hold_seen=${'$'}((hold_seen + 1))
  pid=${'$'}{comm#/proc/}
  pid=${'$'}{pid%/comm}
  [ -r "/proc/${'$'}pid/cgroup" ] || {
    log "RMG_SOFT_REBOOT_V1|stage=keeper|status=proc-missing|pid=${'$'}pid"
    exit 41
  }
  grep -Fq '/rmg-keepers' "/proc/${'$'}pid/cgroup" || {
    log "RMG_SOFT_REBOOT_V1|stage=keeper|status=wrong-cgroup|pid=${'$'}pid"
    exit 42
  }
  log "RMG_SOFT_REBOOT_V1|stage=keeper|status=ok|pid=${'$'}pid"
done
[ "${'$'}hold_seen" -gt 0 ] || {
  log "RMG_SOFT_REBOOT_V1|stage=keeper|status=not-found"
  exit 43
}
[ -d "${'$'}group" ] || {
  log "RMG_SOFT_REBOOT_V1|stage=keeper|status=group-missing"
  exit 44
}
ksud=/data/adb/ksud
if [ ! -x "${'$'}ksud" ]; then
  ksud=/data/local/tmp/ksud-s25u-kdp
fi
[ -x "${'$'}ksud" ] || {
  log "RMG_SOFT_REBOOT_V1|stage=ksud|status=missing"
  exit 45
}
log "RMG_SOFT_REBOOT_V1|stage=ksud|status=found|path=${'$'}ksud"
info=${'$'}("${'$'}ksud" debug info 2>&1)
info_rc=${'$'}?
log "RMG_SOFT_REBOOT_V1|stage=ksud-info|rc=${'$'}info_rc|detail=${'$'}(printf '%s' "${'$'}info" | tr '\n|' '  ' | cut -c1-320)"
[ "${'$'}info_rc" -eq 0 ] || exit 46

# Match the KernelSU Manager's successful path: create a new KernelSU root
# shell in the global mount namespace, then execute the soft-reboot command
# from inside that shell.  This avoids relying solely on the bootstrap
# helper's post-late-load security context.
log "RMG_SOFT_REBOOT_V1|stage=launch|route=ksu-debug-su"
if printf 'exec "%s" soft-reboot\n' "${'$'}ksud" | "${'$'}ksud" debug su -g; then
  log "RMG_SOFT_REBOOT_V1|stage=launcher-exit|route=ksu-debug-su|rc=0"
  exit 0
fi
rc=${'$'}?
log "RMG_SOFT_REBOOT_V1|stage=launcher-exit|route=ksu-debug-su|rc=${'$'}rc"

# Conservative fallback for devices/builds where debug-su is unavailable to a
# bootstrap-root caller.  `soft_reboot()` itself detaches into init's process
# group/cgroups and mount namespace before stopping Android userspace.
log "RMG_SOFT_REBOOT_V1|stage=launch|route=direct"
"${'$'}ksud" soft-reboot
rc=${'$'}?
log "RMG_SOFT_REBOOT_V1|stage=launcher-exit|route=direct|rc=${'$'}rc"
exit "${'$'}rc"
""".trimIndent()
}
