package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPayloadRolloutTest {
    @Test
    fun debugBuildUsesCzg3DiagnosticPayloadFromMain() {
        assertTrue(BuildConfig.CZG3_DIAGNOSTIC_PAYLOAD)
        assertEquals("main", BuildConfig.PAYLOAD_REF)
    }
}
