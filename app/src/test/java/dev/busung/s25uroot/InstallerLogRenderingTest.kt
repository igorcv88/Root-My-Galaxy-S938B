package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerLogRenderingTest {
    @Test
    fun shortLogIsUnchanged() {
        assertEquals("abc", installerLogForDisplay("abc", 8))
    }

    @Test
    fun longLogKeepsOnlyBoundedTailForUi() {
        val raw = "0123456789abcdef"
        val visible = installerLogForDisplay(raw, 8)
        assertTrue(visible.startsWith("…\n"))
        assertTrue(visible.endsWith("89abcdef"))
        assertEquals(10, visible.length)
    }
}
