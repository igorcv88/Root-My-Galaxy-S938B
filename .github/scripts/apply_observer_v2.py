#!/usr/bin/env python3
from pathlib import Path
import re


def sub(path: str, pattern: str, replacement: str) -> None:
    p = Path(path)
    text = p.read_text()
    text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: pattern did not match exactly once: {pattern[:120]}")
    p.write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


manual = "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt"
sub(
    manual,
    r'(        val stagedPayload = if \(shizuku\) \{.*?        \}\n)(        val minimumUptimeSeconds = if \(profile\.profileId == CZG3_PROFILE_ID\) \{)',
    r'''\1        val transport = if (shizuku) "shizuku" else "standalone"
        val observer = if (profile.profileId == CZG3_PROFILE_ID) {
            ExploitObserverSession.start(
                context = app,
                runId = runId,
                invocationMode = invocationMode,
                transport = transport,
                payloadLog = if (shizuku) null else logFile,
            )
        } else {
            null
        }
        observer?.let {
            appendLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport",
            )
        }
\2''',
)
sub(
    manual,
    r'        val transport = if \(shizuku\) "shizuku" else "standalone"\n        appendLog\(\n            ExploitRunControl\.contextRecord\(',
    '        appendLog(\n            ExploitRunControl.contextRecord(',
)
sub(
    manual,
    r'''        val process = ExploitRunControl\.start\(
            useShizuku = shizuku,
            helper = helper,
            payload = payload,
            logFile = logFile,
            environmentVariables = environment,
            shizukuPayloadPath = stagedPayload\.absolutePath,
        \)''',
    '''        val process = try {
            ExploitRunControl.start(
                useShizuku = shizuku,
                helper = helper,
                payload = payload,
                logFile = logFile,
                environmentVariables = environment,
                shizukuPayloadPath = stagedPayload.absolutePath,
            )
        } catch (error: Throwable) {
            observer?.stopAndCollect()
            throw error
        }''',
)
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
    '''            // Both transports drain into `captured` during the poll loop, so
            // this never blocks on a child still holding the pipe open.
            val earlyOutput = captured.toString().trim()
            if (exitCode != 0 && diagnosticSnapshot == null) {
                error(app.getString(R.string.error_payload_exit, exitCode, earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: ""))
            }
            validateTerminalExploit(diagnosticSnapshot, exitCode)''',
    '''            validateTerminalExploit(diagnosticSnapshot, exitCode, rawLog)''',
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
                exploitElapsedMillis = maxOf(entry.exploitElapsedMillis ?: 0L, elapsedMillis),
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
sub(
    manual,
    r'''        \} finally \{
            if \(process\.isAlive\) \{
                process\.destroy\(\)
                delay\(500\.milliseconds\)
                if \(process\.isAlive\) process\.destroyForcibly\(\)
            \}
        \}
        appendLog\(app\.getString\(R\.string\.log_bootstrap_root\)\)''',
    '''        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            try {
                observer?.stopAndCollect()?.let { report ->
                    appendLog(
                        "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                            "target_pid=${report.targetPid ?: -1}",
                    )
                    if (report.text.isNotBlank()) appendLog(report.text.trimEnd())
                }
            } catch (observerError: Throwable) {
                appendLog(
                    "RMG_OBSERVER_V2|event=controller_error|message=" +
                        (observerError.message ?: observerError.javaClass.simpleName),
                )
            }
        }
        appendLog(app.getString(R.string.log_bootstrap_root))''',
)


auto = "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt"
sub(
    auto,
    r'(        val stagedPayload = if \(useShizuku\) \{.*?        \}\n)(        val minimumUptimeSeconds = if \(payloads\.profile\.profileId == CZG3_PROFILE_ID\) \{)',
    r'''\1        val transport = if (useShizuku) "shizuku" else "standalone"
        val observer = if (payloads.profile.profileId == CZG3_PROFILE_ID) {
            ExploitObserverSession.start(
                context = context,
                runId = runId,
                invocationMode = InvocationMode.AutoRoot,
                transport = transport,
                payloadLog = if (useShizuku) null else logFile,
            )
        } else {
            null
        }
        observer?.let {
            onLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport",
            )
        }
\2''',
)
sub(
    auto,
    r'        val transport = if \(useShizuku\) "shizuku" else "standalone"\n        onLog\(\n            ExploitRunControl\.contextRecord\(',
    '        onLog(\n            ExploitRunControl.contextRecord(',
)
sub(
    auto,
    r'''        val process = ExploitRunControl\.start\(
            useShizuku = useShizuku,
            helper = helper,
            payload = payload,
            logFile = logFile,
            environmentVariables = environment,
            shizukuPayloadPath = stagedPayload\.absolutePath,
        \)''',
    '''        val process = try {
            ExploitRunControl.start(
                useShizuku = useShizuku,
                helper = helper,
                payload = payload,
                logFile = logFile,
                environmentVariables = environment,
                shizukuPayloadPath = stagedPayload.absolutePath,
            )
        } catch (error: Throwable) {
            observer?.stopAndCollect()
            throw error
        }''',
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
sub(
    auto,
    r'''        \} finally \{
            if \(process\.isAlive\) \{
                process\.destroy\(\)
                delay\(500\.milliseconds\)
                if \(process\.isAlive\) process\.destroyForcibly\(\)
            \}
            output\.awaitCompletion\(\)
        \}
        onLog\(context\.getString\(R\.string\.log_bootstrap_root\)\)''',
    '''        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
            output.awaitCompletion()
            try {
                observer?.stopAndCollect()?.let { report ->
                    onLog(
                        "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                            "target_pid=${report.targetPid ?: -1}",
                    )
                    if (report.text.isNotBlank()) onLog(report.text.trimEnd())
                }
            } catch (observerError: Throwable) {
                onLog(
                    "RMG_OBSERVER_V2|event=controller_error|message=" +
                        (observerError.message ?: observerError.javaClass.simpleName),
                )
            }
        }
        onLog(context.getString(R.string.log_bootstrap_root))''',
)
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
                            exploitElapsedMillis = maxOf(entry.exploitElapsedMillis ?: 0L, elapsedMillis),
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
    // The external-observer CZG3 payload intentionally emits no in-band
    // diagnostics. A zero supervisor exit remains the authoritative success
    // signal; failure classification comes from post-child supervisor output.
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
