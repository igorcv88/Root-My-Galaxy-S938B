package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLogRenderingTest {
    @Test
    fun chunksPreserveTheRawLogExactly() {
        val log = buildString {
            repeat(10_000) { index ->
                append("RMG_RACE_V1|event=")
                append(index)
                append('|')
                append("value=abcdefghijklmnopqrstuvwxyz\n")
            }
        }

        val chunks = historyLogChunks(log)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= HISTORY_LOG_RENDER_CHUNK_CHARS })
        assertEquals(log, chunks.joinToString(separator = ""))
    }

    @Test
    fun chunksDoNotSplitSurrogatePairs() {
        val log = "1234567😀abcdefgh"
        val chunks = historyLogChunks(log, maxChunkChars = 8)

        assertEquals(log, chunks.joinToString(separator = ""))
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(chunk.isEmpty() || !Character.isHighSurrogate(chunk.last()))
        }
    }
}
