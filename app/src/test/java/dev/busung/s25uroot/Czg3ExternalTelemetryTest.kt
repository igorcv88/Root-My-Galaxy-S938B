package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Czg3ExternalTelemetryTest {
    private val log = """
        RMG_OBSERVER_V2|event=start|t_ms=120086|observer_pid=27157|poll_ms=25
        RMG_OBSERVER_V2|event=controller_attach|attached=true|target_pid=27197
        RMG_OBSERVER_V2|event=attach|t_ms=120110|pid=27197|stat_access=1
        RMG_OBSERVER_V2|event=target|t_ms=120120|pid=27197
        [+] exploit attempt=1/24 pid=27203 delay=25000 p0_offset=scan
        [*] kernel page prepare mode=1 attempt=1/2 elapsed_ms=2923 base=ffffff8844f38000
        [-] exploit attempt=1/24 failed status=255
        [+] exploit attempt=9/24 pid=27042 delay=25000 p0_offset=scan
        [*] mm leaked=ffffff8818720000 base=ffffff8818720000 object_index=0
        [*] sk_buff reclaim sends=4/4 mode=1
        [*] kernel page prepare mode=1 attempt=1/2 elapsed_ms=3030 base=ffffff8818720000
        [+] slide-kaslr-ok source=physical pid=27042 base=ffffffc080140000 slide=0000000000140000 data_mode=physical-alias
        [*] mm leaked=ffffff8047f7d500 base=ffffff8047f78000 object_index=17
        [*] sk_buff reclaim sends=4/4 mode=0
        [*] kernel page prepare mode=0 attempt=1/2 elapsed_ms=3580 base=ffffff8047f78000
        [*] durable log checkpoint stage=fops-page-held
        [*] app fops slide route parent=ffffff8047f79180 target=ffffff802a5bd7f0 effective_consume_usec=70000
        [*] slide pselect returned nfds=320 pad=0 ret=0 errno=0 elapsed_usec=100128 ready=1
        [*] app fops stage=trigger-return attempt=1 triggered=0
        [!] writer route outcome is mutation-uncertain; refusing retry on this boot (reboot required)
        RMG_OBSERVER_V2|event=marker|t_ms=123030|name=p0_page_prepared|line=[*] kernel page prepare mode=1 attempt=1/2 elapsed_ms=2923 base=ffffff8844f38000
        RMG_OBSERVER_V2|event=marker|t_ms=209900|name=p0_success|line=[+] slide-kaslr-ok
        RMG_OBSERVER_V2|event=marker|t_ms=213400|name=fops_page_held|line=[*] durable log checkpoint stage=fops-page-held
        RMG_OBSERVER_V2|event=proc|t_ms=120120|pid=27197|ppid=1|comm=helper|state=S|cpu=3|threads=1|utime=1|stime=1|runtime_ns=100|wait_ns=20|slices=2
        RMG_OBSERVER_V2|event=proc|t_ms=213500|pid=27197|ppid=1|comm=helper|state=S|cpu=7|threads=1|utime=2|stime=2|runtime_ns=900|wait_ns=120|slices=9
        RMG_OBSERVER_V2|event=stop|t_ms=214219|dropped=0|bytes=179490
    """.trimIndent()

    @Test
    fun parsesExternalObserverRunWithoutInventingUnavailableFields() {
        val value = Czg3ExternalTelemetryParser.parse(log)
        assertEquals(9, value.maxSupervisorAttempt)
        assertEquals(94_133L, value.exploitElapsedMillis)
        assertEquals(27197L, value.observerTargetPid)
        assertTrue(value.observerAttached)
        assertEquals(0L, value.observerDroppedEvents)
        assertTrue(value.p0Succeeded)
        assertTrue(value.fopsReached)
        assertEquals(false, value.fopsTriggered)
        assertTrue(value.unsafeStop)
        assertEquals("fops/page-held", value.lastCheckpoint)
        assertEquals(213400L, value.lastCheckpointUptimeMillis)
        assertEquals(100128L, value.lastPselectDurationMicros)
        assertEquals(70000L, value.fopsEffectiveConsumeMicros)
        assertEquals(1, value.targetCpuChangesObserved)
        assertEquals(800L, value.targetRuntimeDeltaNanos)
        assertEquals(100L, value.targetWaitDeltaNanos)
        assertEquals(7L, value.targetSlicesDelta)
    }

    @Test
    fun controllerQueueSuccessDoesNotOverrideFailedNativeAttach() {
        val failedAttachLog = """
            RMG_OBSERVER_V2|event=start|t_ms=100|observer_pid=10
            RMG_OBSERVER_V2|event=controller_attach|attached=true|target_pid=4242
            RMG_OBSERVER_V2|event=attach|t_ms=110|pid=4242|stat_access=0
            RMG_OBSERVER_V2|event=stop|t_ms=200|dropped=0|bytes=100
        """.trimIndent()
        val value = Czg3ExternalTelemetryParser.parse(failedAttachLog)
        assertEquals(4242L, value.observerTargetPid)
        assertFalse(value.observerAttached)
        assertTrue(raceAnalysisReport(failedAttachLog).contains("trace_complete=false"))
    }

    @Test
    fun missingNativeAttachAcknowledgementIsNotVerified() {
        val queuedOnlyLog = """
            RMG_OBSERVER_V2|event=start|t_ms=100|observer_pid=10
            RMG_OBSERVER_V2|event=controller_attach|attached=true|target_pid=4242
            RMG_OBSERVER_V2|event=stop|t_ms=200|dropped=0|bytes=100
        """.trimIndent()
        val value = Czg3ExternalTelemetryParser.parse(queuedOnlyLog)
        assertEquals(null, value.observerTargetPid)
        assertFalse(value.observerAttached)
    }

    @Test
    fun normalizesHistoryFromRawExternalLog() {
        val entry = InstallHistoryEntry(
            id = "test-run",
            startedAtMillis = 1L,
            completedAtMillis = 2L,
            result = InstallRunResult.Failed,
            log = log,
            profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
            attemptCount = 0,
            exploitElapsedMillis = null,
        )
        val normalized = normalizeCzg3ExternalHistory(entry)
        assertEquals(9, normalized.attemptCount)
        assertEquals(94_133L, normalized.exploitElapsedMillis)
        assertEquals("fops/page-held", normalized.lastPrepCheckpoint)
        assertEquals(213400L, normalized.lastPrepCheckpointUptimeMillis)
        assertEquals(ExploitStage.AttemptingRace, normalized.stage)
        assertFalse(normalized.stageTimings.isEmpty())
    }

    @Test
    fun normalizationNeverRegressesKernelSuStagesOrAddsRaceTiming() {
        listOf(
            ExploitStage.StagingKernelSu,
            ExploitStage.LateLoadingKernelSu,
            ExploitStage.VerifyingKernelSu,
        ).forEach { advancedStage ->
            val existingTiming = StageTiming(advancedStage, 120_000L, 9)
            val entry = InstallHistoryEntry(
                id = "advanced-${advancedStage.name}",
                startedAtMillis = 1L,
                completedAtMillis = 2L,
                result = InstallRunResult.Succeeded,
                log = log,
                profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
                stage = advancedStage,
                attemptCount = 9,
                exploitElapsedMillis = 120_000L,
                stageTimings = listOf(existingTiming),
            )

            val normalized = normalizeCzg3ExternalHistory(entry)

            assertEquals(advancedStage, normalized.stage)
            assertEquals(120_000L, normalized.exploitElapsedMillis)
            assertEquals(listOf(existingTiming), normalized.stageTimings)
        }
    }

    @Test
    fun externalReportsMarkUnavailableRoleTimingsExplicitlyAndIgnoreMarkerCopies() {
        val race = raceAnalysisReport(log)
        val prep = preparationAnalysisReport(log)
        assertTrue(race.contains("source=external_observer_v2"))
        assertTrue(race.contains("consumer_arm_to_action_us=unavailable"))
        assertTrue(race.contains("pselect_duration_us=100128"))
        assertTrue(prep.contains("source=external_observer_v2"))
        assertTrue(prep.contains("object_index=0"))
        assertTrue(prep.contains("result=mutation_uncertain"))
        assertTrue(prep.contains("preparation_cycles=2"))
        assertFalse(prep.contains("preparation_cycles=3"))
    }
}
