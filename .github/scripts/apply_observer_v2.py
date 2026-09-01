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
        }
        observer?.attach(process)''',
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
        }
        observer?.attach(process)''',
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
