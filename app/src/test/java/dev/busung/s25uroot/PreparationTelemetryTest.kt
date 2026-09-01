package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationTelemetryTest {
    @Test
    fun calculatesPreparationDurationsAndCounters() {
        val log = listOf(
            record("p0", "preparation_begin", 0),
            record("p0", "preparation_context", 0)
                .replace("|arg0=0|arg1=0", "|arg0=2|arg1=0"),
            record("p0", "kernelsnitch_setup", 100),
            record("p0", "initial_prep_allocations", 200),
            record("p0", "spray_allocations", 300),
            record("p0", "collision_ready", 50),
            record("p0", "kernelsnitch_bruteforce", 400),
            record("p0", "sk_buff_reclaim", 80)
                .replace("|arg0=0|arg1=0", "|arg0=4|arg1=3"),
            record("p0", "total", 1_500, "|object_index=9|trace_complete=1"),
        ).joinToString("\n")

        val analysis = PreparationTelemetryParser.analyze(log)
        val p0 = analysis.scopes.getValue("p0")
        assertEquals(1_500L, p0.totalMicros)
        assertEquals(0L, p0.mode)
        assertEquals(2L, p0.preparationAttempt)
        assertEquals(500L, p0.allocationsMicros)
        assertEquals(400L, p0.bruteForceMicros)
        assertEquals(9L, p0.objectIndex)
        assertEquals(4L, p0.skBuffSendsRequested)
        assertEquals(3L, p0.skBuffSendsCompleted)
        assertTrue(p0.traceComplete == true)
    }

    @Test
    fun malformedRecordsDoNotCorruptValidAnalysis() {
        val log = "RMG_PREP_V1|broken\n${record("fops", "total", 777, "|object_index=2|trace_complete=0")}"
        val analysis = PreparationTelemetryParser.analyze(log)
        assertEquals(1, analysis.malformedRecords)
        assertEquals(777L, analysis.scopes.getValue("fops").totalMicros)
        assertFalse(analysis.scopes.getValue("fops").traceComplete!!)
    }

    @Test
    fun returnsLastValidCheckpoint() {
        val log = """
            RMG_PREP_CHECKPOINT_V1|run=aa|attempt=1|scope=p0|event=collision_ready|uptime_ms=123
            RMG_PREP_CHECKPOINT_V1|bad
            RMG_PREP_CHECKPOINT_V1|run=aa|attempt=1|scope=fops|event=reclaim_complete|uptime_ms=456
        """.trimIndent()
        assertEquals(
            PreparationCheckpoint("fops", "reclaim_complete", 456),
            PreparationTelemetryParser.lastCheckpoint(log),
        )
    }

    private fun record(scope: String, event: String, duration: Long, suffix: String = "") =
        "RMG_PREP_V1|run=aa|attempt=1|scope=$scope|event=$event|ts_raw=1|duration_us=$duration|result=ok|arg0=0|arg1=0$suffix"
}
