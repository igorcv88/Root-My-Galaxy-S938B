package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticHistoryTest {
    @Test
    fun changedBootIdClassifiesUnexpectedRebootWithoutClaimingKernelPanic() {
        val recovered = recoverInterruptedEntry(entry("a", bootId = "boot-a"), "boot-b", 500)
        assertEquals(InstallRunResult.UnexpectedReboot, recovered.result)
        assertTrue(recovered.unexpectedReboot)
        assertTrue(recovered.log.contains("unexpected reboot"))
        assertFalse(recovered.log.contains("kernel panic", ignoreCase = true))
    }

    @Test
    fun sameOrUnknownBootIdRemainsGenericInterruptedFailure() {
        assertEquals(InstallRunResult.Failed, recoverInterruptedEntry(entry("a", "boot-a"), "boot-a", 500).result)
        assertEquals(InstallRunResult.Failed, recoverInterruptedEntry(entry("a", null), "boot-b", 500).result)
    }

    @Test
    fun boundedHistoryPrunesOnlyOldestEntries() {
        val entries = (1..55).map { entry(it.toString(), "boot", started = it.toLong()) }
        assertEquals((1..5).map(Int::toString).toSet(), historyIdsToPrune(entries, 50))
    }

    @Test
    fun pruningReadsPersistedStartTimeInsteadOfFilesystemMtime() {
        assertEquals(
            123456L,
            historyStartedAtMillisFromPrefix(
                "{\"schemaVersion\":2,\"id\":\"x\",\"startedAtMillis\":123456,\"result\":\"Failed\"}",
            ),
        )
        assertNull(historyStartedAtMillisFromPrefix("{\"id\":\"x\"}"))
    }

    @Test
    fun recentObserverHistoryDoesNotNeedExpensiveRenormalization() {
        assertFalse(historyNeedsExternalNormalization(0, "0.3.38", CZG3_PROFILE_ID_FOR_DIAGNOSTICS, true))
        assertFalse(historyNeedsExternalNormalization(HISTORY_SCHEMA_VERSION, null, CZG3_PROFILE_ID_FOR_DIAGNOSTICS, true))
        assertTrue(historyNeedsExternalNormalization(0, "0.3.37", CZG3_PROFILE_ID_FOR_DIAGNOSTICS, true))
        assertFalse(historyNeedsExternalNormalization(0, "0.3.37", "other-profile", false))
    }

    @Test
    fun externalObserverNormalizationRunsOnlyForTerminalWrites() {
        val running = entry("observer", "boot").copy(
            profileId = CZG3_PROFILE_ID_FOR_DIAGNOSTICS,
            log = "RMG_OBSERVER_V2|event=start|t_ms=100",
        )
        assertFalse(shouldNormalizeHistoryBeforeTerminalSave(running))
        assertTrue(
            shouldNormalizeHistoryBeforeTerminalSave(
                running.copy(
                    completedAtMillis = 2,
                    result = InstallRunResult.Succeeded,
                ),
            ),
        )
    }

    @Test
    fun historyRecoveryRecognizesAtomicBackupsAndQuarantineFiles() {
        assertTrue(isRecoverableHistoryArtifactName("run.json.bak"))
        assertTrue(isRecoverableHistoryArtifactName("run.json.corrupt"))
        assertTrue(isRecoverableHistoryArtifactName("run.json.123456.corrupt"))
        assertFalse(isRecoverableHistoryArtifactName("run.json"))
        assertFalse(isRecoverableHistoryArtifactName("notes.corrupt"))
    }

    @Test
    fun aggregatesLatencyAttemptsAndCoarseUptimeBuckets() {
        val entries = listOf(
            terminal("a", true, 100, 2, 5 * 60_000L),
            terminal("b", true, 300, 4, 2 * 60 * 60_000L),
            terminal("c", false, 900, 8, 7 * 60 * 60_000L),
        )
        val stats = aggregateDiagnostics(entries)
        assertEquals(3, stats.totalRuns)
        assertEquals(2.0 / 3.0, stats.successRate, 0.0001)
        assertEquals(100L, stats.medianAcquisitionMillis)
        assertEquals(300L, stats.p90AcquisitionMillis)
        assertEquals(4.0, stats.medianAttemptCount)
        assertEquals(1 to 1, stats.uptimeBuckets["<10m"])
        assertEquals(1 to 0, stats.uptimeBuckets[">=6h"])
    }

    private fun entry(id: String, bootId: String?, started: Long = 1) = InstallHistoryEntry(id, started, null, InstallRunResult.Running, "", bootId = bootId)
    private fun terminal(id: String, success: Boolean, elapsed: Long, attempts: Int, uptime: Long) = InstallHistoryEntry(
        id, 1, 2, if (success) InstallRunResult.Succeeded else InstallRunResult.Failed, "", startedAtUptimeMillis = uptime,
        exploitElapsedMillis = elapsed, attemptCount = attempts,
    )
}
