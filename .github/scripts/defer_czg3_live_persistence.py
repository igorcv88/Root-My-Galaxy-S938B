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
    '''        val transport = if (shizuku) "shizuku" else "standalone"
        val observer = if (profile.profileId == CZG3_PROFILE_ID) {''',
    '''        val transport = if (shizuku) "shizuku" else "standalone"
        val externalObserverMode = profile.profileId == CZG3_PROFILE_ID
        val observer = if (externalObserverMode) {''',
)
replace_once(
    manual,
    '''                    cacheP0Offset(bootToken, rawLog)
                    publishExploitLog(logPrefix, rawLog)''',
    '''                    if (!externalObserverMode) {
                        cacheP0Offset(bootToken, rawLog)
                        publishExploitLog(logPrefix, rawLog)
                    }''',
)
replace_once(
    manual,
    '''                    checkpointPreparation(prepDelta)
                    lastRawLog = rawLog''',
    '''                    if (!externalObserverMode) checkpointPreparation(prepDelta)
                    lastRawLog = rawLog''',
)
old_method = '''    private fun checkpointSupervisorAttempt(attempt: Int, elapsedMillis: Long) {
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
    }'''
new_method = '''    private fun checkpointSupervisorAttempt(attempt: Int, elapsedMillis: Long) {
        mutableState.value = mutableState.value.copy(
            exploitStage = ExploitStage.AttemptingRace,
            exploitAttempt = attempt,
            exploitElapsedMillis = elapsedMillis,
            message = ExploitStage.AttemptingRace.userLabel(attempt, elapsedMillis),
        )
        activeHistoryEntry = activeHistoryEntry?.let { entry ->
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
    }'''
replace_once(manual, old_method, new_method)


auto = "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt"
replace_once(
    auto,
    '''        val transport = if (useShizuku) "shizuku" else "standalone"
        val observer = if (payloads.profile.profileId == CZG3_PROFILE_ID) {''',
    '''        val transport = if (useShizuku) "shizuku" else "standalone"
        val externalObserverMode = payloads.profile.profileId == CZG3_PROFILE_ID
        val observer = if (externalObserverMode) {''',
)
replace_once(
    auto,
    '''        fun publishNewLog(rawLog: String) {
            val clean = stripAnsi(rawLog).trim()
            if (clean.isNotBlank() && clean != publishedLog) {''',
    '''        fun publishNewLog(rawLog: String, terminal: Boolean = false) {
            val clean = stripAnsi(rawLog).trim()
            if ((!externalObserverMode || terminal) && clean.isNotBlank() && clean != publishedLog) {''',
)
replace_once(
    auto,
    '''                if (rawLog != lastRawLog) {
                    cacheP0Offset(bootToken, rawLog)
                    publishNewLog(rawLog)''',
    '''                if (rawLog != lastRawLog) {
                    if (!externalObserverMode) cacheP0Offset(bootToken, rawLog)
                    publishNewLog(rawLog)''',
)
replace_once(
    auto,
    '''            val rawLog = readLog()
            cacheP0Offset(bootToken, rawLog)
            publishNewLog(rawLog)
            onLog("[*] stage=RunningExploit exit_code=$exitCode elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")''',
    '''            val rawLog = readLog()
            cacheP0Offset(bootToken, rawLog)
            publishNewLog(rawLog, terminal = true)
            onLog("[*] stage=RunningExploit exit_code=$exitCode elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")''',
)

service = "app/src/main/java/dev/busung/s25uroot/AutoRootService.kt"
replace_once(
    service,
    '''            val runner = AutoRootRunner(
                context = this,''',
    '''            val externalObserverMode = payloads.profile.profileId == CZG3_PROFILE_ID
            val runner = AutoRootRunner(
                context = this,''',
)
replace_once(
    service,
    '''                        saveHistory(true)
                        updateNotification(ExploitStage.AttemptingRace.userLabel(attempt, elapsedMillis))''',
    '''                        if (!externalObserverMode) {
                            saveHistory(true)
                            updateNotification(ExploitStage.AttemptingRace.userLabel(attempt, elapsedMillis))
                        }''',
)
replace_once(
    service,
    '''                        saveHistory(diagnostic.outcome != null)
                        updateNotification(diagnostic.stage.userLabel(diagnostic.attempt, diagnostic.elapsedMillis))''',
    '''                        if (!externalObserverMode) {
                            saveHistory(diagnostic.outcome != null)
                            updateNotification(diagnostic.stage.userLabel(diagnostic.attempt, diagnostic.elapsedMillis))
                        }''',
)
