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

/**
 * Minimal post-root restart path.
 *
 * The bootstrap helper already executes commands as root, so there is no
 * second KernelSU `debug su`, Manager binary, Shizuku route, observer, keeper
 * scan, or diagnostic trace here. We call the installed/staged ksud directly.
 */
object KernelSuSoftReboot {
    suspend fun request(context: Context): SoftRebootResult = withContext(Dispatchers.IO) {
        val helper = File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        if (!helper.canExecute()) {
            return@withContext SoftRebootResult(false, "Bootstrap root helper is unavailable")
        }

        runCatching {
            val process = ProcessBuilder(helper.absolutePath, "-c", SOFT_REBOOT_SCRIPT)
                .redirectErrorStream(true)
                .start()
            finish(process)
        }.getOrElse { error ->
            SoftRebootResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun finish(process: Process): SoftRebootResult {
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            return SoftRebootResult(false, "KernelSU soft reboot command timed out")
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrDefault("")
        val code = process.exitValue()
        return SoftRebootResult(
            started = code == 0,
            detail = output.ifBlank { "KernelSU soft reboot returned $code" },
        )
    }

    private const val COMMAND_TIMEOUT_SECONDS = 10L

    private val SOFT_REBOOT_SCRIPT = """
set -eu
ksud=/data/adb/ksud
if [ ! -x "${'$'}ksud" ]; then
  ksud=/data/local/tmp/ksud-s25u-kdp
fi
[ -x "${'$'}ksud" ] || exit 45
exec "${'$'}ksud" soft-reboot
""".trimIndent()
}
