package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceTelemetryTest {
    @Test fun derivesSuccessfulRaceMetrics() {
        val result = RaceTelemetryParser.analyze(trace(pselectReturn = 100_100_000, ret = 7, window = 1))
        assertEquals(100_000L, result.measurementsMicros["pselect_duration_us"])
        assertEquals(100L, result.measurementsMicros["readiness_to_pselect_return_us"])
        assertTrue(result.traceComplete == true)
    }

    @Test fun derivesTimeoutRaceMetrics() {
        val result = RaceTelemetryParser.analyze(trace(pselectReturn = 100_135_000, ret = 0, window = 0))
        assertEquals(135L, result.measurementsMicros["readiness_to_pselect_return_us"])
        assertEquals(0L, result.droppedEvents)
    }

    @Test fun toleratesMissingOptionalAndMalformedTelemetry() {
        val result = RaceTelemetryParser.analyze("RMG_RACE_V1|truncated\n")
        assertNull(result.measurementsMicros["pselect_duration_us"])
        assertEquals(1, result.malformedRecords)
    }

    @Test fun historyLogBoundIsExplicit() {
        assertTrue(InstallHistoryStore.MAX_LOG_CHARS >= 1_000_000)
        assertFalse(raceAnalysisReport("none").length > 4_096)
    }

    private fun trace(pselectReturn: Long, ret: Int, window: Int) = """
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=parent|event=pselect_enter|ts_raw_ns=100000|arg0=0|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=consumer|event=consumer_armed|ts_raw_ns=110000|arg0=1|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=consumer|event=consumer_action_begin|ts_raw_ns=70000000|arg0=1|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=consumer|event=readiness_operation_complete|ts_raw_ns=100000000|arg0=0|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=parent|event=pselect_return|ts_raw_ns=$pselectReturn|arg0=$ret|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=waiter|event=writer_enter|ts_raw_ns=99900000|arg0=0|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=waiter|event=writer_return|ts_raw_ns=100010000|arg0=$window|arg1=0
        RMG_RACE_V1|run=0123456789abcdef|attempt=1|race=42|role=parent|event=trace_status|ts_raw_ns=100200000|trace_complete=1|dropped_events=0
    """.trimIndent()
}
