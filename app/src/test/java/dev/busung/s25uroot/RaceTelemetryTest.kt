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

    @Test fun preservesSignedCrossThreadOrdering() {
        val log = trace(pselectReturn = 100_100_000, ret = 7, window = 1)
            .replace("event=writer_enter|ts_raw_ns=99900000", "event=writer_enter|ts_raw_ns=69000000")
        val result = RaceTelemetryParser.analyze(log)
        assertEquals(-1_000L, result.measurementsMicros["consumer_action_to_writer_enter_us"])
    }

    @Test fun externalObserverUsesLightweightRaceTimings() {
        val log = """
            RMG_OBSERVER_V2|event=controller_start|available=true|transport=shizuku|scope=system_remote_markers
            RMG_OBSERVER_V2|event=start|t_ms=120000|observer_pid=10
            RMG_OBSERVER_V2|event=marker|t_ms=120010|name=attempt_begin|line=[+] exploit attempt=1/24 pid=200
            RMG_RACE_LIGHT_V1|attempt=1|counter_hz=19200000|pselect_duration_us=100100|consumer_arm_to_action_us=69900|consumer_action_to_readiness_us=30000|readiness_to_pselect_return_us=100|writer_enter_to_return_us=110|consumer_action_to_writer_enter_us=29900|writer_enter_to_pselect_return_us=200
            RMG_OBSERVER_V2|event=stop|t_ms=120200|dropped=0|bytes=100
        """.trimIndent()
        val report = raceAnalysisReport(log)
        assertTrue(report.contains("source=external_observer_v2"))
        assertTrue(report.contains("pselect_duration_us=100100"))
        assertTrue(report.contains("consumer_arm_to_action_us=69900"))
        assertTrue(report.contains("consumer_action_to_readiness_us=30000"))
        assertTrue(report.contains("readiness_to_pselect_return_us=100"))
        assertTrue(report.contains("writer_enter_to_return_us=110"))
        assertTrue(report.contains("consumer_action_to_writer_enter_us=29900"))
        assertTrue(report.contains("writer_enter_to_pselect_return_us=200"))
        assertTrue(report.contains("trace_complete=true"))
    }

    @Test fun toleratesMissingOptionalAndMalformedTelemetry() {
        val result = RaceTelemetryParser.analyze("RMG_RACE_V1|truncated\n")
        assertNull(result.measurementsMicros["pselect_duration_us"])
        assertEquals(1, result.malformedRecords)
    }

    @Test fun usesOnlyTheCompletedRunAttemptAndRace() {
        val priorRun = trace(pselectReturn = 500_000, ret = 0, window = 0)
            .replace("run=0123456789abcdef", "run=fedcba9876543210")
            .replace("attempt=1|race=42", "attempt=2|race=42")
            .replace("trace_complete=1", "trace_complete=0")
        val priorAttempt = trace(pselectReturn = 700_000, ret = 0, window = 0)
            .replace("trace_complete=1", "trace_complete=0")
        val completedAttempt = trace(pselectReturn = 100_100_000, ret = 7, window = 1)
            .replace("attempt=1|race=42", "attempt=2|race=42")
        val result = RaceTelemetryParser.analyze("$priorRun\n$priorAttempt\n$completedAttempt")

        assertEquals(100_000L, result.measurementsMicros["pselect_duration_us"])
        assertTrue(result.traceComplete == true)
    }

    @Test fun schedulerDeltasUseOnlyTheCompletedRun() {
        val log = """
            RMG_SYS_V1|run=fedcba9876543210|phase=pre|kind=parent_sched|available=1|line=nr_migrations : 100
            RMG_SYS_V1|run=fedcba9876543210|phase=post|kind=parent_sched|available=1|line=nr_migrations : 120
            RMG_SYS_V1|run=0123456789abcdef|phase=pre|kind=parent_sched|available=1|line=nr_migrations : 5
            RMG_SYS_V1|run=0123456789abcdef|phase=post|kind=parent_sched|available=1|line=nr_migrations : 7
            ${trace(pselectReturn = 100_100_000, ret = 7, window = 1)}
        """.trimIndent()
        val result = RaceTelemetryParser.analyze(log)

        assertEquals(2L, result.schedulerDeltas["parent"])
    }

    @Test fun historyLogIsNotTruncated() {
        val log = "x".repeat(1_000_001)
        assertEquals(log, historyLogForStorage(log))
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
