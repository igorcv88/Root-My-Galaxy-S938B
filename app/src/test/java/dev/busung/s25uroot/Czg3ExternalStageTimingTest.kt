package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Czg3ExternalStageTimingTest {
    @Test
    fun attemptingRaceTimingUsesFirstNativeAttemptMarker() {
        val log = """
            RMG_BOOT_V1|configured_min_uptime_sec=120|payload_release_uptime_ms=120100|invocation_mode=auto_root
            RMG_OBSERVER_V2|event=controller_start|available=true|transport=shizuku|scope=system_remote_markers
            RMG_OBSERVER_V2|event=start|t_ms=120090|observer_pid=10
            [+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            RMG_OBSERVER_V2|event=marker|t_ms=120125|name=attempt_begin|line=[+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            [*] app fops slide route parent=ffffff8000000000 target=ffffff802a64d7f0 effective_consume_usec=70000
            RMG_OBSERVER_V2|event=marker|t_ms=140000|name=fops_page_held|line=[*] durable log checkpoint stage=fops-page-held
            RMG_OBSERVER_V2|event=stop|t_ms=141000|dropped=0|bytes=1000
        """.trimIndent()
        val entry = InstallHistoryEntry(
            id = "timing-anchor",
            startedAtMillis = 1L,
            completedAtMillis = 2L,
            result = InstallRunResult.Failed,
            log = log,
            profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
        )

        val normalized = normalizeCzg3ExternalHistory(entry)
        val raceTiming = normalized.stageTimings.single { it.stage == ExploitStage.AttemptingRace }

        assertEquals(ExploitStage.AttemptingRace, normalized.stage)
        assertEquals(20_900L, normalized.exploitElapsedMillis)
        assertEquals(25L, raceTiming.elapsedMillis)
        assertEquals(1, raceTiming.attempt)
        assertTrue(raceTiming.elapsedMillis < normalized.exploitElapsedMillis!!)
    }

    @Test
    fun nativeAttemptAnchorReplacesStaleTerminalRaceTiming() {
        val log = """
            RMG_BOOT_V1|configured_min_uptime_sec=120|payload_release_uptime_ms=120100|invocation_mode=auto_root
            RMG_OBSERVER_V2|event=controller_start|available=true|transport=shizuku|scope=system_remote_markers
            RMG_OBSERVER_V2|event=start|t_ms=120090|observer_pid=10
            [+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            RMG_OBSERVER_V2|event=marker|t_ms=120125|name=attempt_begin|line=[+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            [*] app fops slide route parent=ffffff8000000000 target=ffffff802a64d7f0 effective_consume_usec=70000
            RMG_OBSERVER_V2|event=marker|t_ms=140000|name=fops_page_held|line=[*] durable log checkpoint stage=fops-page-held
            RMG_OBSERVER_V2|event=stop|t_ms=141000|dropped=0|bytes=1000
        """.trimIndent()
        val entry = InstallHistoryEntry(
            id = "replace-stale-timing",
            startedAtMillis = 1L,
            completedAtMillis = 2L,
            result = InstallRunResult.Failed,
            log = log,
            profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
            stage = ExploitStage.AttemptingRace,
            attemptCount = 1,
            exploitElapsedMillis = 20_900L,
            stageTimings = listOf(StageTiming(ExploitStage.AttemptingRace, 20_900L, 1)),
        )

        val normalized = normalizeCzg3ExternalHistory(entry)
        val raceTimings = normalized.stageTimings.filter { it.stage == ExploitStage.AttemptingRace }

        assertEquals(listOf(StageTiming(ExploitStage.AttemptingRace, 25L, 1)), raceTimings)
    }

    @Test
    fun nativeAttemptAnchorPreservesSupervisorAttemptProgression() {
        val log = """
            RMG_BOOT_V1|configured_min_uptime_sec=120|payload_release_uptime_ms=120100|invocation_mode=auto_root
            RMG_OBSERVER_V2|event=controller_start|available=true|transport=shizuku|scope=system_remote_markers
            RMG_OBSERVER_V2|event=start|t_ms=120090|observer_pid=10
            [+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            RMG_OBSERVER_V2|event=marker|t_ms=120125|name=attempt_begin|line=[+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            [+] exploit attempt=2/24 pid=201 delay=25000 p0_offset=scan
            RMG_OBSERVER_V2|event=marker|t_ms=129600|name=attempt_begin|line=[+] exploit attempt=2/24 pid=201 delay=25000 p0_offset=scan
            [*] app fops slide route parent=ffffff8000000000 target=ffffff802a64d7f0 effective_consume_usec=70000
            RMG_OBSERVER_V2|event=marker|t_ms=140000|name=fops_page_held|line=[*] durable log checkpoint stage=fops-page-held
            RMG_OBSERVER_V2|event=stop|t_ms=141000|dropped=0|bytes=1000
        """.trimIndent()
        val entry = InstallHistoryEntry(
            id = "preserve-attempt-progression",
            startedAtMillis = 1L,
            completedAtMillis = 2L,
            result = InstallRunResult.Failed,
            log = log,
            profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
            stage = ExploitStage.AttemptingRace,
            attemptCount = 2,
            exploitElapsedMillis = 20_900L,
            stageTimings = listOf(
                StageTiming(ExploitStage.AttemptingRace, 30L, 1),
                StageTiming(ExploitStage.AttemptingRace, 9_500L, 2),
                StageTiming(ExploitStage.AttemptingRace, 20_900L, 2),
            ),
        )

        val normalized = normalizeCzg3ExternalHistory(entry)
        val raceTimings = normalized.stageTimings.filter { it.stage == ExploitStage.AttemptingRace }

        assertEquals(
            listOf(
                StageTiming(ExploitStage.AttemptingRace, 25L, 1),
                StageTiming(ExploitStage.AttemptingRace, 9_500L, 2),
            ),
            raceTimings,
        )
        assertEquals(2, normalized.attemptCount)
    }

    @Test
    fun nativeAttemptAnchorSurvivesSuccessfulPostExploitStages() {
        val log = """
            RMG_BOOT_V1|configured_min_uptime_sec=120|payload_release_uptime_ms=120100|invocation_mode=manual_online
            RMG_OBSERVER_V2|event=controller_start|available=true|transport=standalone|scope=process_tree_system
            RMG_OBSERVER_V2|event=start|t_ms=120090|observer_pid=10
            [+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            RMG_OBSERVER_V2|event=marker|t_ms=120125|name=attempt_begin|line=[+] exploit attempt=1/24 pid=200 delay=25000 p0_offset=scan
            [*] app fops slide route parent=ffffff8000000000 target=ffffff802a64d7f0 effective_consume_usec=70000
            RMG_OBSERVER_V2|event=marker|t_ms=132000|name=fops_page_held|line=[*] durable log checkpoint stage=fops-page-held
            RMG_OBSERVER_V2|event=stop|t_ms=132124|dropped=0|bytes=1000
        """.trimIndent()
        val entry = InstallHistoryEntry(
            id = "success-post-root-stages",
            startedAtMillis = 1L,
            completedAtMillis = 2L,
            result = InstallRunResult.Succeeded,
            log = log,
            profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
            stage = ExploitStage.VerifyingKernelSu,
            attemptCount = 1,
            exploitElapsedMillis = 12_024L,
            stageTimings = listOf(
                StageTiming(ExploitStage.StagingKernelSu, 12_024L),
                StageTiming(ExploitStage.LateLoadingKernelSu, 12_024L),
                StageTiming(ExploitStage.VerifyingKernelSu, 12_024L),
            ),
        )

        val normalized = normalizeCzg3ExternalHistory(entry)

        assertEquals(ExploitStage.VerifyingKernelSu, normalized.stage)
        assertEquals(12_024L, normalized.exploitElapsedMillis)
        assertEquals(
            listOf(
                StageTiming(ExploitStage.AttemptingRace, 25L, 1),
                StageTiming(ExploitStage.StagingKernelSu, 12_024L),
                StageTiming(ExploitStage.LateLoadingKernelSu, 12_024L),
                StageTiming(ExploitStage.VerifyingKernelSu, 12_024L),
            ),
            normalized.stageTimings,
        )
    }
}
