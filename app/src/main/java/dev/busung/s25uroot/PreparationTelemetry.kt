package dev.busung.s25uroot

internal data class PreparationRecord(
    val runId: String,
    val attempt: Int,
    val scope: String,
    val event: String,
    val durationMicros: Long,
    val result: String,
    val arguments: Map<String, String>,
)

internal data class PreparationCheckpoint(
    val scope: String,
    val event: String,
    val uptimeMillis: Long,
)

internal data class PreparationScopeAnalysis(
    val preparationBeginUptimeMillis: Long?,
    val mode: Long?,
    val preparationAttempt: Long?,
    val preparationCycles: Int,
    val failedCycles: Int,
    val totalMicros: Long?,
    val kernelSnitchSetupMicros: Long?,
    val allocationsMicros: Long?,
    val collisionPhaseMicros: Long?,
    val bruteForceMicros: Long?,
    val reclaimMicros: Long?,
    val objectIndex: Long?,
    val skBuffSendsRequested: Long?,
    val skBuffSendsCompleted: Long?,
    val result: String?,
    val traceComplete: Boolean?,
    val phaseDurationsMicros: Map<String, Long>,
)

internal data class PreparationAnalysis(
    val scopes: Map<String, PreparationScopeAnalysis>,
    val malformedRecords: Int,
)

internal object PreparationTelemetryParser {
    private const val RECORD_PREFIX = "RMG_PREP_V1|"
    private const val CHECKPOINT_PREFIX = "RMG_PREP_CHECKPOINT_V1|"

    fun analyze(log: String): PreparationAnalysis {
        var malformed = 0
        val records = log.lineSequence().mapNotNull { line ->
            val marker = line.indexOf(RECORD_PREFIX)
            if (marker < 0) return@mapNotNull null
            runCatching { parseRecord(line.substring(marker + RECORD_PREFIX.length)) }
                .getOrElse { malformed++; null }
        }.toList()
        return PreparationAnalysis(
            scopes = listOf("p0", "fops").associateWith { scope -> analyzeScope(records, scope) },
            malformedRecords = malformed,
        )
    }

    fun lastCheckpoint(log: String): PreparationCheckpoint? = log.lineSequence().mapNotNull { line ->
        val marker = line.indexOf(CHECKPOINT_PREFIX)
        if (marker < 0) return@mapNotNull null
        runCatching { parseCheckpoint(line.substring(marker + CHECKPOINT_PREFIX.length)) }.getOrNull()
    }.lastOrNull()

    private fun analyzeScope(records: List<PreparationRecord>, scope: String): PreparationScopeAnalysis {
        val scoped = records.filter { it.scope == scope }
        val lastTotal = scoped.lastOrNull { it.event == "total" }
        val active = lastTotal?.let { terminal ->
            scoped.filter { it.runId == terminal.runId && it.attempt == terminal.attempt }
        }.orEmpty()
        val totals = active.filter { it.event == "total" }
        fun duration(vararg eventParts: String): Long? {
            val values = active.filter { record -> eventParts.any(record.event::contains) }
                .map(PreparationRecord::durationMicros)
            return values.takeIf(List<Long>::isNotEmpty)?.sum()
        }
        val reclaim = active.filter { record ->
            record.event.contains("partial_free") ||
                record.event.contains("partial_drain") ||
                record.event.contains("sk_buff_reclaim")
        }
        val skBuff = active.lastOrNull { it.event == "sk_buff_reclaim" }
        val preparationContexts = active.filter { it.event == "preparation_context" }
        val preparationContext = preparationContexts.lastOrNull()
        val phaseDurations = linkedMapOf<String, Long>()
        active.asSequence()
            .filterNot { it.event == "preparation_begin" || it.event == "preparation_context" || it.event == "total" }
            .forEach { record ->
                phaseDurations[record.event] = phaseDurations.getOrDefault(record.event, 0L) + record.durationMicros
            }
        return PreparationScopeAnalysis(
            preparationBeginUptimeMillis = active.firstOrNull { it.event == "preparation_begin" }
                ?.arguments?.get("arg0")?.toLongOrNull(),
            mode = preparationContext?.arguments?.get("arg1")?.toLongOrNull(),
            preparationAttempt = preparationContext?.arguments?.get("arg0")?.toLongOrNull(),
            preparationCycles = totals.size,
            failedCycles = totals.count { it.result != "ok" },
            totalMicros = totals.map(PreparationRecord::durationMicros).takeIf(List<Long>::isNotEmpty)?.sum(),
            kernelSnitchSetupMicros = duration("kernelsnitch_setup"),
            allocationsMicros = duration("initial_prep_allocations", "spray_allocations", "pre_allocations", "post_allocations", "leak_memfd_open"),
            collisionPhaseMicros = duration("collision_ready", "wait_leak_child"),
            bruteForceMicros = duration("kernelsnitch_bruteforce"),
            reclaimMicros = reclaim.map(PreparationRecord::durationMicros).takeIf(List<Long>::isNotEmpty)?.sum(),
            objectIndex = lastTotal?.arguments?.get("object_index")?.toLongOrNull(),
            skBuffSendsRequested = skBuff?.arguments?.get("arg0")?.toLongOrNull(),
            skBuffSendsCompleted = skBuff?.arguments?.get("arg1")?.toLongOrNull(),
            result = lastTotal?.result,
            traceComplete = totals.takeIf(List<PreparationRecord>::isNotEmpty)?.all { it.arguments["trace_complete"] == "1" },
            phaseDurationsMicros = phaseDurations,
        )
    }

    private fun parseRecord(body: String): PreparationRecord {
        val fields = parseFields(body)
        val scope = requireNotNull(fields["scope"]).also { require(it in setOf("p0", "fops")) }
        val event = requireNotNull(fields["event"]).also { require(it.matches(Regex("[a-z0-9_]+"))) }
        val runId = requireNotNull(fields["run"]).also { require(it.matches(Regex("[0-9a-fA-F]+"))) }
        val attempt = requireNotNull(fields["attempt"]).toInt().also { require(it > 0) }
        val duration = requireNotNull(fields["duration_us"]).toLong().also { require(it >= 0) }
        val result = requireNotNull(fields["result"]).also { require(it.matches(Regex("[a-z0-9_]+"))) }
        return PreparationRecord(runId, attempt, scope, event, duration, result, fields - setOf("run", "attempt", "scope", "event", "duration_us", "result"))
    }

    private fun parseCheckpoint(body: String): PreparationCheckpoint {
        val fields = parseFields(body)
        val scope = requireNotNull(fields["scope"]).also { require(it in setOf("p0", "fops")) }
        val event = requireNotNull(fields["event"]).also { require(it.matches(Regex("[a-z0-9_]+"))) }
        val uptime = requireNotNull(fields["uptime_ms"]).toLong().also { require(it >= 0) }
        return PreparationCheckpoint(scope, event, uptime)
    }

    private fun parseFields(body: String): Map<String, String> = buildMap {
        body.trim().split('|').filter(String::isNotBlank).forEach { field ->
            val separator = field.indexOf('=')
            require(separator > 0)
            require(put(field.substring(0, separator), field.substring(separator + 1)) == null)
        }
    }
}

private data class ExternalPrep(
    val cycles: Int,
    val lastAttempt: Long?,
    val lastElapsedMicros: Long?,
    val lastObjectIndex: Long?,
    val skRequested: Long?,
    val skCompleted: Long?,
)

private fun externalPrep(log: String, mode: Int): ExternalPrep {
    val prepare = Regex("""kernel page prepare mode=$mode attempt=(\d+)/(\d+) elapsed_ms=(\d+)[^\n]*""")
    val all = prepare.findAll(log).toList()
    val last = all.lastOrNull()
    val objectIndex = if (last != null) {
        val prefix = log.substring(0, last.range.first)
        Regex("""mm leaked=[^\n]*object_index=(\d+)""").findAll(prefix).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
    } else null
    val sk = if (last != null) {
        val prefix = log.substring(0, last.range.first)
        Regex("""sk_buff reclaim sends=(\d+)/(\d+) mode=$mode""").findAll(prefix).lastOrNull()
    } else null
    return ExternalPrep(
        cycles = all.size,
        lastAttempt = last?.groupValues?.get(1)?.toLongOrNull(),
        lastElapsedMicros = last?.groupValues?.get(3)?.toLongOrNull()?.times(1_000L),
        lastObjectIndex = objectIndex,
        skRequested = sk?.groupValues?.get(2)?.toLongOrNull(),
        skCompleted = sk?.groupValues?.get(1)?.toLongOrNull(),
    )
}

internal fun preparationAnalysisReport(log: String): String {
    if (!log.contains("RMG_PREP_V1|") && log.contains("RMG_OBSERVER_V2|")) {
        val summary = Czg3ExternalTelemetryParser.parse(log)
        val p0 = externalPrep(log, 1)
        val fops = externalPrep(log, 0)
        val traceComplete = summary.observerAttached && summary.observerDroppedEvents == 0L && summary.observerStopUptimeMillis != null
        return buildString {
            appendLine("prep_analysis:")
            appendLine("source=external_observer_v2")
            appendLine("p0:")
            appendLine("preparation_begin_uptime_ms=unavailable")
            appendLine("mode=1")
            appendLine("preparation_attempt=${p0.lastAttempt ?: "unknown"}")
            appendLine("preparation_cycles=${p0.cycles}")
            appendLine("failed_cycles=unavailable")
            appendLine("total_us=${p0.lastElapsedMicros ?: "unknown"}")
            appendLine("kernelsnitch_setup_us=unavailable")
            appendLine("allocations_us=unavailable")
            appendLine("collision_phase_us=unavailable")
            appendLine("brute_force_us=unavailable")
            appendLine("reclaim_us=unavailable")
            appendLine("object_index=${p0.lastObjectIndex ?: "unknown"}")
            appendLine("sk_buff_sends_requested=${p0.skRequested ?: "unknown"}")
            appendLine("sk_buff_sends_completed=${p0.skCompleted ?: "unknown"}")
            appendLine("result=${if (summary.p0Succeeded) "success" else "not_acquired"}")
            appendLine("trace_complete=$traceComplete")
            appendLine("phases:")
            appendLine("  unavailable")
            appendLine("fops:")
            appendLine("preparation_begin_uptime_ms=unavailable")
            appendLine("mode=0")
            appendLine("preparation_attempt=${fops.lastAttempt ?: "unknown"}")
            appendLine("preparation_cycles=${fops.cycles}")
            appendLine("failed_cycles=unavailable")
            appendLine("total_us=${fops.lastElapsedMicros ?: "unknown"}")
            appendLine("kernelsnitch_setup_us=unavailable")
            appendLine("allocations_us=unavailable")
            appendLine("collision_phase_us=unavailable")
            appendLine("brute_force_us=unavailable")
            appendLine("reclaim_us=unavailable")
            appendLine("object_index=${fops.lastObjectIndex ?: "unknown"}")
            appendLine("sk_buff_sends_requested=${fops.skRequested ?: "unknown"}")
            appendLine("sk_buff_sends_completed=${fops.skCompleted ?: "unknown"}")
            appendLine("result=" + when {
                summary.fopsTriggered == true -> "triggered"
                summary.unsafeStop -> "mutation_uncertain"
                summary.fopsReached -> "reached"
                else -> "not_reached"
            })
            appendLine("trace_complete=$traceComplete")
            appendLine("phases:")
            appendLine("  unavailable")
            appendLine("malformed_records=0")
            append(externalObserverAnalysisReport(log))
        }
    }

    return buildString {
        val analysis = PreparationTelemetryParser.analyze(log)
        appendLine("prep_analysis:")
        listOf("p0", "fops").forEach { scope ->
            val value = analysis.scopes.getValue(scope)
            appendLine("$scope:")
            appendLine("preparation_begin_uptime_ms=${value.preparationBeginUptimeMillis ?: "unknown"}")
            appendLine("mode=${value.mode ?: "unknown"}")
            appendLine("preparation_attempt=${value.preparationAttempt ?: "unknown"}")
            appendLine("preparation_cycles=${value.preparationCycles}")
            appendLine("failed_cycles=${value.failedCycles}")
            appendLine("total_us=${value.totalMicros ?: "unknown"}")
            appendLine("kernelsnitch_setup_us=${value.kernelSnitchSetupMicros ?: "unknown"}")
            appendLine("allocations_us=${value.allocationsMicros ?: "unknown"}")
            appendLine("collision_phase_us=${value.collisionPhaseMicros ?: "unknown"}")
            appendLine("brute_force_us=${value.bruteForceMicros ?: "unknown"}")
            appendLine("reclaim_us=${value.reclaimMicros ?: "unknown"}")
            appendLine("object_index=${value.objectIndex ?: "unknown"}")
            appendLine("sk_buff_sends_requested=${value.skBuffSendsRequested ?: "unknown"}")
            appendLine("sk_buff_sends_completed=${value.skBuffSendsCompleted ?: "unknown"}")
            appendLine("result=${value.result ?: "unknown"}")
            appendLine("trace_complete=${value.traceComplete ?: "unknown"}")
            appendLine("phases:")
            if (value.phaseDurationsMicros.isEmpty()) appendLine("  none")
            else value.phaseDurationsMicros.forEach { (event, duration) -> appendLine("  ${event}_us=$duration") }
        }
        appendLine("malformed_records=${analysis.malformedRecords}")
    }
}
