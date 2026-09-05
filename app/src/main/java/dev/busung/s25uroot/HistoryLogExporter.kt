package dev.busung.s25uroot

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pure post-run History export. It is never referenced by the exploit path. */
internal object HistoryLogExporter {
    fun createZipDocumentIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/zip"
        putExtra(Intent.EXTRA_TITLE, zipFileName())
    }

    suspend fun saveZip(
        context: Context,
        uri: Uri,
        entries: List<InstallHistoryEntry>,
    ): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0
        var saved = 0
        val wrote = runCatching {
            context.contentResolver
                .openOutputStream(uri, "w")
                ?.buffered(EXPORT_BUFFER_BYTES)
                ?.use { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.setLevel(Deflater.BEST_SPEED)
                        val usedNames = mutableSetOf<String>()
                        entries.forEach { entry ->
                            val name = uniqueName(entry, usedNames)
                            zip.putNextEntry(ZipEntry(name))
                            try {
                                val content = entry.log.ifBlank { "No log was recorded for this run." }
                                zip.write(content.toByteArray(Charsets.UTF_8))
                                saved++
                            } finally {
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: error("Unable to open export document")
        }.isSuccess
        if (wrote) saved else 0
    }

    private fun uniqueName(entry: InstallHistoryEntry, used: MutableSet<String>): String {
        val base = "RootMyGalaxy-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(entry.startedAtMillis)) +
            "-${entry.result.name.lowercase(Locale.US)}"
        var candidate = "$base.log"
        if (used.add(candidate)) return candidate
        candidate = "$base-${entry.id.take(8)}.log"
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "$base-${entry.id.take(8)}-$suffix.log"
            suffix++
        }
        return candidate
    }

    private fun zipFileName(): String =
        "RootMyGalaxy-logs-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            ".zip"

    private const val EXPORT_BUFFER_BYTES = 64 * 1024
}
