package dev.busung.s25uroot

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Reads only newly appended bytes from a stable exploit log path. */
internal class IncrementalFileLogReader(private val file: File) : Closeable {
    private var reader: RandomAccessFile? = null
    private var offset = 0L
    private val accumulated = StringBuilder()
    private val scratch = ByteArray(DEFAULT_BUFFER_SIZE)

    fun snapshot(): String {
        val input = reader ?: runCatching { RandomAccessFile(file, "r") }
            .getOrNull()
            ?.also { reader = it }
            ?: return accumulated.toString()
        val length = runCatching { input.length() }.getOrDefault(offset)
        if (length < offset) {
            offset = 0L
            accumulated.clear()
        }
        runCatching { input.seek(offset) }.getOrElse { return accumulated.toString() }
        while (true) {
            val count = runCatching { input.read(scratch) }.getOrDefault(-1)
            if (count <= 0) break
            accumulated.append(String(scratch, 0, count, Charsets.UTF_8))
            offset += count
        }
        return accumulated.toString()
    }

    override fun close() {
        runCatching { reader?.close() }
        reader = null
    }
}
