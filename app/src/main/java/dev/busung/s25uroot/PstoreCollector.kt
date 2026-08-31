package dev.busung.s25uroot

import java.io.File

data class KernelCrashRecord(val status: KernelCrashRecordStatus, val content: String? = null)

object PstoreCollector {
    fun collect(shizukuAlreadyAuthorized: Boolean): KernelCrashRecord {
        readDirect()?.let { return it }
        if (shizukuAlreadyAuthorized && ShizukuController.isRunning() && ShizukuController.isGranted()) readWithShizuku()?.let { return it }
        return KernelCrashRecord(if (PSTORE_PATHS.any { File(it).exists() }) KernelCrashRecordStatus.NotAccessible else KernelCrashRecordStatus.NoneFound)
    }

    private fun readDirect(): KernelCrashRecord? = runCatching {
        PSTORE_PATHS.firstNotNullOfOrNull { path ->
            val directory = File(path)
            if (!directory.isDirectory || !directory.canRead()) return@firstNotNullOfOrNull null
            val files = directory.listFiles() ?: return@firstNotNullOfOrNull null
            val content = files.filter { it.isFile && it.canRead() }.sortedBy { it.name }
                .joinToString("\n") { file -> "== ${file.name} ==\n${file.readText().take(MAX_CRASH_CHARS)}" }.takeIf(String::isNotBlank)
            KernelCrashRecord(if (content == null) KernelCrashRecordStatus.NoneFound else KernelCrashRecordStatus.Found, content)
        }
    }.getOrNull()

    private fun readWithShizuku(): KernelCrashRecord? = runCatching {
        val command = "{ for d in ${PSTORE_PATHS.joinToString(" ")}; do if [ -d \"\$d\" ]; then for f in \"\$d\"/*; do [ -f \"\$f\" ] || continue; echo \"== \${f##*/} ==\"; head -c $MAX_CRASH_CHARS \"\$f\"; echo; done; fi; done; } | head -c $MAX_CRASH_CHARS"
        val process = ShizukuController.exec(arrayOf("/system/bin/sh", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }.take(MAX_CRASH_CHARS)
        if (process.waitFor() == 0) {
            KernelCrashRecord(if (output.isBlank()) KernelCrashRecordStatus.NoneFound else KernelCrashRecordStatus.Found, output.takeIf(String::isNotBlank))
        } else null
    }.getOrNull()

    private val PSTORE_PATHS = listOf("/sys/fs/pstore", "/proc/fs/pstore")
    private const val MAX_CRASH_CHARS = 64 * 1024
}
