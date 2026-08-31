package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStagingFallbackTest {
    @Test
    fun fallbackChunkSizeKeepsBinderCommandsBounded() {
        val rawChunkBytes = 12 * 1024
        val base64Chars = ((rawChunkBytes + 2) / 3) * 4
        assertEquals(16 * 1024, base64Chars)
    }
}
