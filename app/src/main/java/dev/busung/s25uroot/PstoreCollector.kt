package dev.busung.s25uroot

import java.io.File

data class KernelCrashRecord(val status: KernelCrashRecordStatus, val content: String? = null)

object PstoreCollector {
    fun collect(_shizukuAlreadyAuthorized: Boolean): KernelCrashRecord {
        val direct = readDirect()
        if (direct?.status == KernelCrashRecordStatus.Found ||
            direct?.status == KernelCrashRecordStatus.NoneFound
        ) return requireNotNull(direct)

        // After a reboot, current authorization is what determines whether the
        // already-running Shizuku service can read pstore. Never prompt or start it.
        if (ShizukuController.isRunning() && ShizukuController.isGranted()) {
            readWithShizuku()?.let { return it }
        }
        return direct ?: KernelCrashRecord(
            if (PSTORE_PATHS.any { File(it).exists() }) {
                KernelCrashRecordStatus.NotAccessible
            } else {
                KernelCrashRecordStatus.NoneFound
            },
        )
    }

    private fun readDirect(): KernelCrashRecord? = runCatching {
        var sawDirectory = false
        var sawReadableDirectory = false
        var sawInaccessibleContent = false
        val sections = mutableListOf<String>()

        PSTORE_PATHS.forEach pathLoop@ { path ->
            val directory = File(path)
            if (!directory.isDirectory) return@pathLoop
            sawDirectory = true
            if (!directory.canRead()) {
                sawInaccessibleContent = true
                return@pathLoop
            }
            val files = directory.listFiles()
            if (files == null) {
                sawInaccessibleContent = true
                return@pathLoop
            }
            sawReadableDirectory = true
            files.filter { it.isFile }.sortedBy { it.name }.forEach fileLoop@ { file ->
                if (!file.canRead()) {
                    sawInaccessibleContent = true
                    return@fileLoop
                }
                val text = runCatching { file.readText().take(MAX_CRASH_CHARS) }.getOrNull()
                if (text == null) {
                    sawInaccessibleContent = true
                } else {
                    sections += "== ${file.name} ==\n$text"
                }
            }
        }

        when {
            sections.isNotEmpty() -> KernelCrashRecord(
                KernelCrashRecordStatus.Found,
                sections.joinToString("\n").take(MAX_CRASH_CHARS),
            )
            sawInaccessibleContent || (sawDirectory && !sawReadableDirectory) ->
                KernelCrashRecord(KernelCrashRecordStatus.NotAccessible)
            sawReadableDirectory -> KernelCrashRecord(KernelCrashRecordStatus.NoneFound)
            else -> null
        }
    }.getOrNull()

    private fun readWithShizuku(): KernelCrashRecord? = runCatching {
        val command = "{ for d in ${PSTORE_PATHS.joinToString(" ")}; do if [ -d \"\$d\" ]; then for f in \"\$d\"/*; do [ -f \"\$f\" ] || continue; echo \"== \${f##*/} ==\"; head -c $MAX_CRASH_CHARS \"\$f\"; echo; done; fi; done; } | head -c $MAX_CRASH_CHARS"
        val process = ShizukuController.exec(arrayOf("/system/bin/sh", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }.take(MAX_CRASH_CHARS)
        if (process.waitFor() == 0) {
            KernelCrashRecord(
                if (output.isBlank()) KernelCrashRecordStatus.NoneFound else KernelCrashRecordStatus.Found,
                output.takeIf(String::isNotBlank),
            )
        } else {
            null
        }
    }.getOrNull()

    private val PSTORE_PATHS = listOf("/sys/fs/pstore", "/proc/fs/pstore")
    private const val MAX_CRASH_CHARS = 64 * 1024
}
