#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


manual = "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt"
replace_once(
    manual,
    '''                    consumedDiagnosticCharacters = parsed.consumedCharacters
                    supervisorAttempt = parsed.supervisorAttempt
                    parsed.events.forEach { (event, eventSupervisorAttempt) ->''',
    '''                    consumedDiagnosticCharacters = parsed.consumedCharacters
                    val previousSupervisorAttempt = supervisorAttempt
                    supervisorAttempt = parsed.supervisorAttempt
                    if (diagnosticSnapshot == null && supervisorAttempt != null &&
                        supervisorAttempt != previousSupervisorAttempt
                    ) {
                        checkpointSupervisorAttempt(
                            supervisorAttempt,
                            SystemClock.elapsedRealtime() - startedAt,
                        )
                    }
                    parsed.events.forEach { (event, eventSupervisorAttempt) ->''',
)
replace_once(
    manual,
    '''            consumedDiagnosticCharacters = parsed.consumedCharacters
            supervisorAttempt = parsed.supervisorAttempt
            parsed.events.forEach { (event, eventSupervisorAttempt) ->''',
    '''            consumedDiagnosticCharacters = parsed.consumedCharacters
            val previousSupervisorAttempt = supervisorAttempt
            supervisorAttempt = parsed.supervisorAttempt
            if (diagnosticSnapshot == null && supervisorAttempt != null &&
                supervisorAttempt != previousSupervisorAttempt
            ) {
                checkpointSupervisorAttempt(
                    supervisorAttempt,
                    SystemClock.elapsedRealtime() - startedAt,
                )
            }
            parsed.events.forEach { (event, eventSupervisorAttempt) ->''',
)
replace_once(
    manual,
    '''            checkpointPreparation(prepDelta)
            // Both transports drain into `captured` during the poll loop, so
            // this never blocks on a child still holding the pipe open.
            val earlyOutput = captured.toString().trim()
            if (exitCode != 0 && diagnosticSnapshot == null) {
                error(app.getString(R.string.error_payload_exit, exitCode, earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: ""))
            }
            validateTerminalExploit(diagnosticSnapshot, exitCode)''',
    '''            checkpointPreparation(prepDelta)
            validateTerminalExploit(diagnosticSnapshot, exitCode, rawLog)''',
)
replace_once(
    manual,
    '''    private fun checkpointDiagnosticSnapshot(updated: ExploitDiagnosticSnapshot): ExploitDiagnosticSnapshot {
        mutableState.value = mutableState.value.copy(
            exploitStage = updated.stage,
            exploitAttempt = updated.attempt,
            exploitElapsedMillis = updated.elapsedMillis,
            message = updated.stage.userLabel(updated.attempt, updated.elapsedMillis),
        )
        checkpointDiagnostic(updated)
        return updated
    }

    private fun checkpointPreparation(log: String) {''',
    '''    private fun checkpointDiagnosticSnapshot(updated: ExploitDiagnosticSnapshot): ExploitDiagnosticSnapshot {
        mutableState.value = mutableState.value.copy(
            exploitStage = updated.stage,
            exploitAttempt = updated.attempt,
            exploitElapsedMillis = updated.elapsedMillis,
            message = updated.stage.userLabel(updated.attempt, updated.elapsedMillis),
        )
        checkpointDiagnostic(updated)
        return updated
    }

    private fun checkpointSupervisorAttempt(attempt: Int, elapsedMillis: Long) {
        mutableState.value = mutableState.value.copy(
            exploitStage = ExploitStage.AttemptingRace,
            exploitAttempt = attempt,
            exploitElapsedMillis = elapsedMillis,
            message = ExploitStage.AttemptingRace.userLabel(attempt, elapsedMillis),
        )
        checkpointHistory(force = true) { entry ->
            val timing = StageTiming(ExploitStage.AttemptingRace, elapsedMillis, attempt)
            entry.copy(
                stage = ExploitStage.AttemptingRace,
                attemptCount = maxOf(entry.attemptCount, attempt),
                exploitElapsedMillis = maxOf(entry.exploitElapsedMillis, elapsedMillis),
                stageTimings = if (entry.stageTimings.lastOrNull() == timing) {
                    entry.stageTimings
                } else {
                    entry.stageTimings + timing
                },
            )
        }
    }

    private fun checkpointPreparation(log: String) {''',
)


auto = "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt"
replace_once(
    auto,
    '''    private val onLog: (String) -> Unit = {},
    private val onDiagnostic: (ExploitDiagnosticSnapshot) -> Unit = {},
) {''',
    '''    private val onLog: (String) -> Unit = {},
    private val onDiagnostic: (ExploitDiagnosticSnapshot) -> Unit = {},
    private val onSupervisorAttempt: (Int, Long) -> Unit = { _, _ -> },
) {''',
)
replace_once(
    auto,
    '''            consumedDiagnosticCharacters = parsed.consumedCharacters
            supervisorAttempt = parsed.supervisorAttempt
            parsed.events.forEach { (event, eventSupervisorAttempt) ->''',
    '''            consumedDiagnosticCharacters = parsed.consumedCharacters
            val previousSupervisorAttempt = supervisorAttempt
            supervisorAttempt = parsed.supervisorAttempt
            if (supervisorAttempt != null && supervisorAttempt != previousSupervisorAttempt) {
                onSupervisorAttempt(
                    supervisorAttempt,
                    SystemClock.elapsedRealtime() - spawnUptimeMillis,
                )
            }
            parsed.events.forEach { (event, eventSupervisorAttempt) ->''',
)
replace_once(
    auto,
    '''            val earlyOutput = output.snapshot().trim()
            onLog("[*] stage=RunningExploit exit_code=$exitCode elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            if (exitCode != 0 && diagnosticSnapshot == null) {
                error(context.getString(R.string.error_payload_exit, exitCode, earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: ""))
            }
            validateTerminalExploit(diagnosticSnapshot, exitCode)''',
    '''            onLog("[*] stage=RunningExploit exit_code=$exitCode elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            validateTerminalExploit(diagnosticSnapshot, exitCode, rawLog)''',
)

service = "app/src/main/java/dev/busung/s25uroot/AutoRootService.kt"
replace_once(
    service,
    '''                onDiagnostic = { diagnostic ->
                    historyEntry?.let { entry ->''',
    '''                onSupervisorAttempt = { attempt, elapsedMillis ->
                    historyEntry?.let { entry ->
                        val timing = StageTiming(ExploitStage.AttemptingRace, elapsedMillis, attempt)
                        historyEntry = entry.copy(
                            stage = ExploitStage.AttemptingRace,
                            attemptCount = maxOf(entry.attemptCount, attempt),
                            exploitElapsedMillis = maxOf(entry.exploitElapsedMillis, elapsedMillis),
                            stageTimings = if (entry.stageTimings.lastOrNull() == timing) {
                                entry.stageTimings
                            } else {
                                entry.stageTimings + timing
                            },
                        )
                        saveHistory(true)
                        updateNotification(ExploitStage.AttemptingRace.userLabel(attempt, elapsedMillis))
                    }
                },
                onDiagnostic = { diagnostic ->
                    historyEntry?.let { entry ->''',
)

diag = "app/src/main/java/dev/busung/s25uroot/ExploitDiagnostics.kt"
replace_once(
    diag,
    '''internal fun validateTerminalExploit(snapshot: ExploitDiagnosticSnapshot?, exitCode: Int) {
    requireNotNull(snapshot) { "Payload did not emit structured exploit diagnostics" }
    if (snapshot.protocol == ExploitDiagnosticProtocol.PayloadPipeV1 && exitCode == 0) return
    if (snapshot.protocol == ExploitDiagnosticProtocol.JsonV1 &&
        snapshot.outcome == ExploitOutcome.Succeeded && exitCode == 0
    ) return

    val failureClass = snapshot.failureClass ?: ExploitFailureClass.Unknown
    val safety = snapshot.safety ?: ExploitSafety.Unknown
    val message = when {
        failureClass == ExploitFailureClass.CleanRaceMiss ->
            "The payload exhausted its bounded clean race retries"
        failureClass == ExploitFailureClass.Precondition -> "Exploit precondition failed"
        failureClass == ExploitFailureClass.PrivilegeBootstrap -> "Privilege bootstrap failed"
        failureClass == ExploitFailureClass.KernelSuStaging -> "KernelSU staging failed"
        failureClass == ExploitFailureClass.KernelSuVerification -> "KernelSU verification failed"
        failureClass == ExploitFailureClass.UnsafeKernelState ||
            safety != ExploitSafety.SafeRetry || snapshot.outcome == ExploitOutcome.StoppedUnsafe ->
            "Exploit stopped because the kernel state is unsafe or unknown; an immediate retry was intentionally blocked"
        else -> "Exploit failed with an unclassified outcome"
    }
    throw ExploitRunException(failureClass, safety, message)
}''',
    '''internal data class SupervisorTerminalOutcome(
    val failureClass: ExploitFailureClass,
    val safety: ExploitSafety,
)

internal object SupervisorTerminalClassifier {
    private val childOutcome = Regex(
        "supervisor child outcome attempt=(\\d+)/(\\d+)[^\\n]*retry=(\\d+)[^\\n]*reboot_required=(\\d+)",
    )

    fun classify(log: String): SupervisorTerminalOutcome {
        val last = childOutcome.findAll(log).lastOrNull()
        val retry = last?.groupValues?.get(3)?.toIntOrNull() == 1
        val rebootRequired = last?.groupValues?.get(4)?.toIntOrNull() == 1
        val unsafeText = listOf(
            "writer route outcome is mutation-uncertain",
            "p0 oracle state dirty or uncertain",
            "p0 oracle dirtied before slide discovery",
            "fresh P0 session was consumed",
            "refusing unsafe retry",
        ).any { marker -> log.contains(marker, ignoreCase = true) }
        if (rebootRequired || unsafeText) {
            return SupervisorTerminalOutcome(
                ExploitFailureClass.UnsafeKernelState,
                ExploitSafety.DoNotRetry,
            )
        }
        if (retry && log.contains("exploit stopped after at most", ignoreCase = true)) {
            return SupervisorTerminalOutcome(
                ExploitFailureClass.CleanRaceMiss,
                ExploitSafety.SafeRetry,
            )
        }
        return SupervisorTerminalOutcome(
            ExploitFailureClass.Unknown,
            ExploitSafety.DoNotRetry,
        )
    }
}

internal fun validateTerminalExploit(
    snapshot: ExploitDiagnosticSnapshot?,
    exitCode: Int,
    supervisorLog: String = "",
) {
    // External-observer CZG3 intentionally has no in-band diagnostics. The
    // supervisor exit is authoritative for success; nonzero exits are mapped
    // from the post-child retry/reboot decision and otherwise fail closed.
    if (exitCode == 0) return

    val terminal = if (snapshot == null) {
        SupervisorTerminalClassifier.classify(supervisorLog)
    } else {
        SupervisorTerminalOutcome(
            snapshot.failureClass ?: ExploitFailureClass.Unknown,
            snapshot.safety ?: ExploitSafety.Unknown,
        )
    }
    val failureClass = terminal.failureClass
    val safety = terminal.safety
    val message = when {
        failureClass == ExploitFailureClass.CleanRaceMiss ->
            "The payload exhausted its bounded clean race retries"
        failureClass == ExploitFailureClass.Precondition -> "Exploit precondition failed"
        failureClass == ExploitFailureClass.PrivilegeBootstrap -> "Privilege bootstrap failed"
        failureClass == ExploitFailureClass.KernelSuStaging -> "KernelSU staging failed"
        failureClass == ExploitFailureClass.KernelSuVerification -> "KernelSU verification failed"
        failureClass == ExploitFailureClass.UnsafeKernelState ||
            safety != ExploitSafety.SafeRetry || snapshot?.outcome == ExploitOutcome.StoppedUnsafe ->
            "Exploit stopped because the kernel state is unsafe or unknown; an immediate retry was intentionally blocked"
        else -> "Exploit failed with an unclassified outcome"
    }
    throw ExploitRunException(failureClass, safety, message)
}''',
)

test = "app/src/test/java/dev/busung/s25uroot/ExploitDiagnosticsTest.kt"
replace_once(
    test,
    '''    @Test
    fun terminalDecisionFailsClosedForMissingOrUnsafeOutcome() {
        assertThrows(IllegalArgumentException::class.java) { validateTerminalExploit(null, 0) }
        val unsafe = ExploitDiagnosticSnapshot("run-12345678").apply(
            event(ExploitStage.AttemptingRace, elapsed = 50).copy(
                failureClass = ExploitFailureClass.UnsafeKernelState,
                safety = ExploitSafety.DoNotRetry,
                outcome = ExploitOutcome.StoppedUnsafe,
            ),
        )
        val error = assertThrows(ExploitRunException::class.java) { validateTerminalExploit(unsafe, 1) }
        assertEquals(ExploitSafety.DoNotRetry, error.safety)
    }
''',
    '''    @Test
    fun externalObserverPayloadUsesSupervisorExitAndFailsClosedForUnknownFailure() {
        validateTerminalExploit(null, 0)
        val error = assertThrows(ExploitRunException::class.java) {
            validateTerminalExploit(null, 1, "unclassified failure")
        }
        assertEquals(ExploitFailureClass.Unknown, error.failureClass)
        assertEquals(ExploitSafety.DoNotRetry, error.safety)
    }

    @Test
    fun externalObserverPayloadClassifiesUnsafeSupervisorStop() {
        val log = """
            [*] supervisor child outcome attempt=2/24 writer_state=POSSIBLE_MUTATION wait_kind=exit wait_value=1 retry=0 reboot_required=1
            [-] writer route outcome is mutation-uncertain; refusing retry on this boot (reboot required)
        """.trimIndent()
        val error = assertThrows(ExploitRunException::class.java) {
            validateTerminalExploit(null, 1, log)
        }
        assertEquals(ExploitFailureClass.UnsafeKernelState, error.failureClass)
        assertEquals(ExploitSafety.DoNotRetry, error.safety)
    }

    @Test
    fun externalObserverPayloadClassifiesBoundedCleanMiss() {
        val log = """
            [*] supervisor child outcome attempt=24/24 writer_state=NOT_ARMED wait_kind=exit wait_value=1 retry=1 reboot_required=0
            [-] exploit stopped after at most 24 configured attempts; see supervisor outcome for effective retry policy
        """.trimIndent()
        val error = assertThrows(ExploitRunException::class.java) {
            validateTerminalExploit(null, 1, log)
        }
        assertEquals(ExploitFailureClass.CleanRaceMiss, error.failureClass)
        assertEquals(ExploitSafety.SafeRetry, error.safety)
    }

    @Test
    fun terminalDecisionStillFailsClosedForUnsafeLegacyOutcome() {
        val unsafe = ExploitDiagnosticSnapshot("run-12345678").apply(
            event(ExploitStage.AttemptingRace, elapsed = 50).copy(
                failureClass = ExploitFailureClass.UnsafeKernelState,
                safety = ExploitSafety.DoNotRetry,
                outcome = ExploitOutcome.StoppedUnsafe,
            ),
        )
        val error = assertThrows(ExploitRunException::class.java) { validateTerminalExploit(unsafe, 1) }
        assertEquals(ExploitSafety.DoNotRetry, error.safety)
    }
''',
)

observer = "app/src/main/cpp/external_observer.c"
replace_once(
    observer,
    '''#define OBS_FAST_INTERVAL_MS 25ULL
#define OBS_SYSTEM_INTERVAL_ACTIVE_MS 200ULL''',
    '''#define OBS_FAST_INTERVAL_MS 50ULL
#define OBS_SYSTEM_INTERVAL_ACTIVE_MS 250ULL''',
)
replace_once(
    observer,
    '''static void observer_pin_away_from_exploit(void) {
  (void)setpriority(PRIO_PROCESS, 0, 10);
  long cpus = sysconf(_SC_NPROCESSORS_ONLN);''',
    '''static void observer_pin_away_from_exploit(void) {
  (void)setpriority(PRIO_PROCESS, 0, 10);
#ifdef SCHED_IDLE
  struct sched_param idle = {.sched_priority = 0};
  (void)sched_setscheduler(0, SCHED_IDLE, &idle);
#endif
  long cpus = sysconf(_SC_NPROCESSORS_ONLN);''',
)
replace_once(
    observer,
    '''  observer_pin_away_from_exploit();
  uint64_t last_system = 0;''',
    '''  observer_pin_away_from_exploit();
  observer_append(
      "RMG_OBSERVER_V2|event=observer_sched|t_ms=%llu|policy=%d|nice=%d|cpu=%d\\n",
      (unsigned long long)boottime_ms(), sched_getscheduler(0),
      getpriority(PRIO_PROCESS, 0), sched_getcpu());
  uint64_t last_system = 0;''',
)
