from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


def replace_region(path: str, start_marker: str, end_marker: str, replacement: str) -> None:
    file = Path(path)
    text = file.read_text()
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"Start marker not found in {path}: {start_marker}")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"End marker not found in {path}: {end_marker}")
    file.write_text(text[:start] + replacement + text[end:])


replace_region(
    "app/src/main/java/dev/busung/s25uroot/ExploitDiagnostics.kt",
    "internal object SupervisorAttemptParser {",
    "class ExploitRunException(",
    r'''internal data class SupervisorDiagnosticBatch(
    val events: List<Pair<ExploitDiagnosticEvent, Int?>>,
    val consumedCharacters: Int,
    val supervisorAttempt: Int?,
)

internal object SupervisorAttemptParser {
    private val attemptPattern = Regex("\\bexploit attempt=(\\d+)/(\\d+)\\b")

    fun parseNewEvents(
        log: String,
        consumedCharacters: Int,
        priorSupervisorAttempt: Int? = null,
        includeTrailingLine: Boolean = false,
    ): SupervisorDiagnosticBatch {
        if (consumedCharacters !in 0..log.length) {
            return SupervisorDiagnosticBatch(emptyList(), 0, priorSupervisorAttempt)
        }
        val lastNewline = log.lastIndexOf('\n')
        val end = if (includeTrailingLine) log.length else lastNewline + 1
        if (end <= consumedCharacters) {
            return SupervisorDiagnosticBatch(emptyList(), consumedCharacters, priorSupervisorAttempt)
        }

        var supervisorAttempt = priorSupervisorAttempt
        val events = mutableListOf<Pair<ExploitDiagnosticEvent, Int?>>()
        log.substring(consumedCharacters, end).lineSequence().forEach { line ->
            parseAttempt(line)?.let { supervisorAttempt = it }
            ExploitDiagnosticParser.parseLine(line)?.let { event ->
                events += event to supervisorAttempt
            }
        }
        return SupervisorDiagnosticBatch(events, end, supervisorAttempt)
    }

    private fun parseAttempt(line: String): Int? {
        val match = attemptPattern.find(line) ?: return null
        val attempt = match.groupValues[1].toIntOrNull() ?: return null
        val configured = match.groupValues[2].toIntOrNull() ?: return null
        return attempt.takeIf { it in 1..configured }
    }
}

internal fun ExploitDiagnosticSnapshot.withSupervisorAttempt(supervisorAttempt: Int?): ExploitDiagnosticSnapshot {
    val resolved = supervisorAttempt ?: return this
    if (resolved <= (attempt ?: 0)) return this
    return copy(attempt = resolved)
}

''',
)

replace_once(
    "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt",
    '''        var publishedLog = ""
        var consumedDiagnosticCharacters = 0
        var diagnosticSnapshot: ExploitDiagnosticSnapshot? = null
''',
    '''        var publishedLog = ""
        var consumedDiagnosticCharacters = 0
        var supervisorAttempt: Int? = null
        var diagnosticSnapshot: ExploitDiagnosticSnapshot? = null
''',
)

replace_once(
    "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt",
    '''            val parsed = ExploitDiagnosticParser.parseNewEvents(
                diagnosticLog,
                consumedDiagnosticCharacters,
                includeTrailingLine = !process.isAlive,
            )
            consumedDiagnosticCharacters = parsed.second
            val supervisorAttempt = SupervisorAttemptParser.maxAttempt(diagnosticLog)
            parsed.first.forEach { event ->
                val updated = (diagnosticSnapshot ?: ExploitDiagnosticSnapshot(runId)).apply(event)
                    .withSupervisorAttempt(supervisorAttempt)
                diagnosticSnapshot = updated
                onDiagnostic(updated)
            }
            diagnosticSnapshot?.withSupervisorAttempt(supervisorAttempt)?.let { reconciled ->
                if (reconciled != diagnosticSnapshot) {
                    diagnosticSnapshot = reconciled
                    onDiagnostic(reconciled)
                }
            }
''',
    '''            val parsed = SupervisorAttemptParser.parseNewEvents(
                diagnosticLog,
                consumedDiagnosticCharacters,
                supervisorAttempt,
                includeTrailingLine = !process.isAlive,
            )
            consumedDiagnosticCharacters = parsed.consumedCharacters
            supervisorAttempt = parsed.supervisorAttempt
            parsed.events.forEach { (event, eventSupervisorAttempt) ->
                val updated = (diagnosticSnapshot ?: ExploitDiagnosticSnapshot(runId)).apply(event)
                    .withSupervisorAttempt(eventSupervisorAttempt)
                diagnosticSnapshot = updated
                onDiagnostic(updated)
            }
''',
)

replace_once(
    "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt",
    '''            var lastRawLog = ""
            var consumedDiagnosticCharacters = 0
            var diagnosticSnapshot: ExploitDiagnosticSnapshot? = null
''',
    '''            var lastRawLog = ""
            var consumedDiagnosticCharacters = 0
            var supervisorAttempt: Int? = null
            var diagnosticSnapshot: ExploitDiagnosticSnapshot? = null
''',
)

replace_once(
    "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt",
    '''                    val parsed = ExploitDiagnosticParser.parseNewEvents(rawLog, consumedDiagnosticCharacters)
                    consumedDiagnosticCharacters = parsed.second
                    val supervisorAttempt = SupervisorAttemptParser.maxAttempt(rawLog)
                    parsed.first.forEach { event ->
                        diagnosticSnapshot = applyDiagnosticEvent(diagnosticSnapshot, event, runId, supervisorAttempt)
                    }
                    diagnosticSnapshot?.withSupervisorAttempt(supervisorAttempt)?.let { reconciled ->
                        if (reconciled != diagnosticSnapshot) {
                            diagnosticSnapshot = checkpointDiagnosticSnapshot(reconciled)
                        }
                    }
''',
    '''                    val parsed = SupervisorAttemptParser.parseNewEvents(
                        rawLog,
                        consumedDiagnosticCharacters,
                        supervisorAttempt,
                    )
                    consumedDiagnosticCharacters = parsed.consumedCharacters
                    supervisorAttempt = parsed.supervisorAttempt
                    parsed.events.forEach { (event, eventSupervisorAttempt) ->
                        diagnosticSnapshot = applyDiagnosticEvent(
                            diagnosticSnapshot,
                            event,
                            runId,
                            eventSupervisorAttempt,
                        )
                    }
''',
)

replace_once(
    "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt",
    '''            val parsed = ExploitDiagnosticParser.parseNewEvents(rawLog, consumedDiagnosticCharacters, includeTrailingLine = true)
            val supervisorAttempt = SupervisorAttemptParser.maxAttempt(rawLog)
            parsed.first.forEach { event ->
                diagnosticSnapshot = applyDiagnosticEvent(diagnosticSnapshot, event, runId, supervisorAttempt)
            }
            diagnosticSnapshot?.withSupervisorAttempt(supervisorAttempt)?.let { reconciled ->
                if (reconciled != diagnosticSnapshot) diagnosticSnapshot = checkpointDiagnosticSnapshot(reconciled)
            }
''',
    '''            val parsed = SupervisorAttemptParser.parseNewEvents(
                rawLog,
                consumedDiagnosticCharacters,
                supervisorAttempt,
                includeTrailingLine = true,
            )
            consumedDiagnosticCharacters = parsed.consumedCharacters
            supervisorAttempt = parsed.supervisorAttempt
            parsed.events.forEach { (event, eventSupervisorAttempt) ->
                diagnosticSnapshot = applyDiagnosticEvent(
                    diagnosticSnapshot,
                    event,
                    runId,
                    eventSupervisorAttempt,
                )
            }
''',
)

replace_region(
    "app/src/test/java/dev/busung/s25uroot/ExploitDiagnosticsTest.kt",
    "    @Test\n    fun supervisorAttemptParserTracksRealSupervisorRetries()",
    "    @Test\n    fun supervisorAttemptCannotBeRegressedByChildLocalAttempt()",
    r'''    @Test
    fun supervisorAttemptsAreMappedInLogOrderWithinOnePollingBatch() {
        val log = """
            [+] exploit attempt=1/24 pid=10 delay=25000
            RMG_DIAG_V1|run=1111111111111111|ts_ns=1|elapsed_us=1|stage=PREPARATION|attempt=0|failure=SUCCESS|safety=UNSAFE_OR_UNKNOWN
            [+] exploit attempt=7/24 pid=20 delay=35000
            RMG_DIAG_V1|run=2222222222222222|ts_ns=2|elapsed_us=1|stage=PREPARATION|attempt=0|failure=SUCCESS|safety=UNSAFE_OR_UNKNOWN
        """.trimIndent() + "\n"
        val parsed = SupervisorAttemptParser.parseNewEvents(log, 0)
        assertEquals(listOf(1, 7), parsed.events.map { it.second })
        assertEquals(7, parsed.supervisorAttempt)
        assertEquals(log.length, parsed.consumedCharacters)
    }

    @Test
    fun supervisorAttemptCarriesAcrossPollingBatches() {
        val first = "[+] exploit attempt=3/24 pid=30 delay=25000\n"
        val firstParsed = SupervisorAttemptParser.parseNewEvents(first, 0)
        assertEquals(3, firstParsed.supervisorAttempt)
        assertEquals(0, firstParsed.events.size)

        val complete = first +
            "RMG_DIAG_V1|run=3333333333333333|ts_ns=3|elapsed_us=1|stage=PREPARATION|attempt=0|failure=SUCCESS|safety=UNSAFE_OR_UNKNOWN\n"
        val secondParsed = SupervisorAttemptParser.parseNewEvents(
            complete,
            firstParsed.consumedCharacters,
            firstParsed.supervisorAttempt,
        )
        assertEquals(3, secondParsed.events.single().second)
    }

''',
)
