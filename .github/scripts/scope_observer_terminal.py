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
    "validateTerminalExploit(diagnosticSnapshot, exitCode, rawLog)",
    "validateTerminalExploit(\n                diagnosticSnapshot,\n                exitCode,\n                rawLog,\n                profile.profileId == CZG3_PROFILE_ID,\n            )",
)

auto = "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt"
replace_once(
    auto,
    "validateTerminalExploit(diagnosticSnapshot, exitCode, rawLog)",
    "validateTerminalExploit(\n                diagnosticSnapshot,\n                exitCode,\n                rawLog,\n                payloads.profile.profileId == CZG3_PROFILE_ID,\n            )",
)

diag = "app/src/main/java/dev/busung/s25uroot/ExploitDiagnostics.kt"
replace_once(
    diag,
    '''internal fun validateTerminalExploit(
    snapshot: ExploitDiagnosticSnapshot?,
    exitCode: Int,
    supervisorLog: String = "",
) {
    // External-observer CZG3 intentionally has no in-band diagnostics. The
    // supervisor exit is authoritative for success; nonzero exits are mapped
    // from the post-child retry/reboot decision and otherwise fail closed.
    if (exitCode == 0) return

    val terminal = if (snapshot == null) {''',
    '''internal fun validateTerminalExploit(
    snapshot: ExploitDiagnosticSnapshot?,
    exitCode: Int,
    supervisorLog: String = "",
    externalObserverMode: Boolean = false,
) {
    // Only the exact CZG3 external-observer release intentionally omits
    // in-band diagnostics. All other profiles retain the original fail-closed
    // requirement for a structured terminal snapshot.
    if (externalObserverMode) {
        if (exitCode == 0) return
    } else {
        requireNotNull(snapshot) { "Payload did not emit structured exploit diagnostics" }
        if (snapshot.protocol == ExploitDiagnosticProtocol.PayloadPipeV1 && exitCode == 0) return
        if (snapshot.protocol == ExploitDiagnosticProtocol.JsonV1 &&
            snapshot.outcome == ExploitOutcome.Succeeded && exitCode == 0
        ) return
    }

    val terminal = if (snapshot == null) {''',
)

test = "app/src/test/java/dev/busung/s25uroot/ExploitDiagnosticsTest.kt"
replace_once(
    test,
    '''    fun externalObserverPayloadUsesSupervisorExitAndFailsClosedForUnknownFailure() {
        validateTerminalExploit(null, 0)
        val error = assertThrows(ExploitRunException::class.java) {
            validateTerminalExploit(null, 1, "unclassified failure")
        }''',
    '''    fun externalObserverPayloadUsesSupervisorExitAndFailsClosedForUnknownFailure() {
        validateTerminalExploit(null, 0, externalObserverMode = true)
        assertThrows(IllegalArgumentException::class.java) { validateTerminalExploit(null, 0) }
        val error = assertThrows(ExploitRunException::class.java) {
            validateTerminalExploit(null, 1, "unclassified failure", externalObserverMode = true)
        }''',
)
replace_once(
    test,
    '''            validateTerminalExploit(null, 1, log)
        }
        assertEquals(ExploitFailureClass.UnsafeKernelState, error.failureClass)''',
    '''            validateTerminalExploit(null, 1, log, externalObserverMode = true)
        }
        assertEquals(ExploitFailureClass.UnsafeKernelState, error.failureClass)''',
)
replace_once(
    test,
    '''            validateTerminalExploit(null, 1, log)
        }
        assertEquals(ExploitFailureClass.CleanRaceMiss, error.failureClass)''',
    '''            validateTerminalExploit(null, 1, log, externalObserverMode = true)
        }
        assertEquals(ExploitFailureClass.CleanRaceMiss, error.failureClass)''',
)
