package dev.busung.s25uroot

internal data class RaceRecord(
    val runId: String,
    val attempt: Int,
    val raceId: String,
    val role: String,
    val event: String,
    val timestampRawNanos: Long,
    val arguments: Map<String, String>,
)

internal data class RaceAnalysis(
    val measurementsMicros: Map<String, Long?>,
    val traceComplete: Boolean?,
    val droppedEvents: Long?,
    val malformedRecords: Int,
    val schedulerDeltas: Map<String, Long?>,
)

internal object RaceTelemetryParser {
    private const val PREFIX = "RMG_RACE_V1|"

    fun analyze(log: String): RaceAnalysis {
        var malformed = 0
        val records = log.lineSequence().mapNotNull { line ->
            val marker = line.indexOf(PREFIX)
            if (marker < 0) return@mapNotNull null
            runCatching { parse(line.substring(marker + PREFIX.length)) }
                .getOrElse { malformed++; null }
        }.toList()
        val status = records.lastOrNull { it.event == "trace_status" }
        val activeRecords = status?.let { completed ->
            records.filter {
                it.runId == completed.runId &&
                    it.attempt == completed.attempt &&
                    it.raceId == completed.raceId
            }
        }.orEmpty()
        fun time(event: String): Long? = activeRecords.firstOrNull { it.event == event }?.timestampRawNanos
        fun delta(start: String, end: String): Long? {
            val first = time(start) ?: return null
            val last = time(end) ?: return null
            return (last - first) / 1_000
        }
        val schedulerDeltas = listOf("parent", "owner", "waiter", "consumer").associateWith { role ->
            schedulerDelta(log, status?.runId, role, "nr_migrations")
        } + listOf("parent", "owner", "waiter", "consumer").associate { role ->
            "${role}_involuntary_switches" to schedulerDelta(log, status?.runId, role, "nr_involuntary_switches")
        }
        return RaceAnalysis(
            measurementsMicros = linkedMapOf(
                "pselect_duration_us" to delta("pselect_enter", "pselect_return"),
                "consumer_arm_to_action_us" to delta("consumer_armed", "consumer_action_begin"),
                "consumer_action_to_readiness_us" to delta("consumer_action_begin", "readiness_operation_complete"),
                "readiness_to_pselect_return_us" to delta("readiness_operation_complete", "pselect_return"),
                "writer_enter_to_return_us" to delta("writer_enter", "writer_return"),
                "consumer_action_to_writer_enter_us" to delta("consumer_action_begin", "writer_enter"),
                "writer_enter_to_pselect_return_us" to delta("writer_enter", "pselect_return"),
            ),
            traceComplete = status?.arguments?.get("trace_complete")?.let { it == "1" },
            droppedEvents = status?.arguments?.get("dropped_events")?.toLongOrNull(),
            malformedRecords = malformed,
            schedulerDeltas = schedulerDeltas,
        )
    }

    private fun schedulerDelta(log: String, runId: String?, role: String, field: String): Long? {
        if (runId == null) return null
        fun value(phase: String): Long? = log.lineSequence()
            .filter {
                it.contains("RMG_SYS_V1|") &&
                    it.contains("|run=$runId|") &&
                    it.contains("|phase=$phase|") &&
                    it.contains("|kind=${role}_sched|")
            }
            .mapNotNull { line ->
                val text = line.substringAfter("|line=", "")
                if (!text.substringBefore(':').trim().endsWith(field)) null else text.substringAfter(':').trim().toDoubleOrNull()?.toLong()
            }.firstOrNull()
        val pre = value("pre") ?: return null
        val post = value("post") ?: return null
        return (post - pre).takeIf { it >= 0 }
    }

    private fun parse(body: String): RaceRecord {
        val fields = linkedMapOf<String, String>()
        body.trim().split('|').filter(String::isNotBlank).forEach { field ->
            val separator = field.indexOf('=')
            require(separator > 0)
            require(fields.put(field.substring(0, separator), field.substring(separator + 1)) == null)
        }
        val role = requireNotNull(fields["role"]).also { require(it.matches(Regex("[a-z_]+"))) }
        val event = requireNotNull(fields["event"]).also { require(it.matches(Regex("[a-z0-9_]+"))) }
        val runId = requireNotNull(fields["run"]).also { require(it.matches(Regex("[0-9a-fA-F]+"))) }
        val attempt = requireNotNull(fields["attempt"]).toInt().also { require(it > 0) }
        val raceId = requireNotNull(fields["race"]).also { require(it.toLong() > 0) }
        val timestamp = requireNotNull(fields["ts_raw_ns"]).toLong().also { require(it >= 0) }
        return RaceRecord(runId, attempt, raceId, role, event, timestamp, fields - setOf("run", "attempt", "race", "role", "event", "ts_raw_ns"))
    }
}

private object LightRaceTelemetryParser {
    private const val PREFIX = "RMG_RACE_LIGHT_V1|"
    private val metricNames = listOf(
        "pselect_duration_us",
        "consumer_arm_to_action_us",
        "consumer_action_to_readiness_us",
        "readiness_to_pselect_return_us",
        "writer_enter_to_return_us",
        "consumer_action_to_writer_enter_us",
        "writer_enter_to_pselect_return_us",
    )

    fun measurements(log: String): Map<String, Long?>? {
        val line = log.lineSequence().lastOrNull { it.contains(PREFIX) } ?: return null
        val body = line.substringAfter(PREFIX)
        val fields = linkedMapOf<String, String>()
        body.split('|').filter(String::isNotBlank).forEach { field ->
            val separator = field.indexOf('=')
            if (separator > 0) fields[field.substring(0, separator)] = field.substring(separator + 1)
        }
        return metricNames.associateWith { name ->
            fields[name]?.toLongOrNull()?.takeUnless { it == -1L }
        }
    }
}

internal fun raceAnalysisReport(log: String): String {
    if (!log.contains("RMG_RACE_V1|") && log.contains("RMG_OBSERVER_V2|")) {
        val value = Czg3ExternalTelemetryParser.parse(log)
        val traceComplete = value.traceComplete
        val light = LightRaceTelemetryParser.measurements(log)
        fun measurement(name: String): Any = light?.get(name) ?: "unavailable"
        return buildString {
            appendLine("race_analysis:")
            appendLine("source=external_observer_v2")
            appendLine("pselect_duration_us=${light?.get("pselect_duration_us") ?: value.lastPselectDurationMicros ?: "unknown"}")
            appendLine("consumer_arm_to_action_us=${measurement("consumer_arm_to_action_us")}")
            appendLine("consumer_action_to_readiness_us=${measurement("consumer_action_to_readiness_us")}")
            appendLine("readiness_to_pselect_return_us=${measurement("readiness_to_pselect_return_us")}")
            appendLine("writer_enter_to_return_us=${measurement("writer_enter_to_return_us")}")
            appendLine("consumer_action_to_writer_enter_us=${measurement("consumer_action_to_writer_enter_us")}")
            appendLine("writer_enter_to_pselect_return_us=${measurement("writer_enter_to_pselect_return_us")}")
            appendLine("trace_complete=$traceComplete")
            appendLine("dropped_events=${value.observerDroppedEvents ?: "unknown"}")
            appendLine("malformed_records=0")
            appendLine("observer_target_pid=${value.observerTargetPid ?: "unknown"}")
            appendLine("observer_scope=${value.observerScope ?: "unknown"}")
            appendLine("process_samples=${value.processSamples}")
            appendLine("process_coverage_complete=${value.processCoverageComplete ?: "not_applicable"}")
            appendLine("process_coverage_reason=${value.processCoverageReason}")
            appendLine("critical_slide_pid=${value.criticalSlidePid ?: "none"}")
            value.roleMetrics.forEach { (role, metrics) ->
                appendLine("process_role_$role expected=${metrics.expectedPids} sampled=${metrics.sampledPids} samples=${metrics.processSamples} cpu_changes=${metrics.cpuChangesObserved ?: "unavailable"} runtime_delta_ns=${metrics.runtimeDeltaNanos ?: "unavailable"} wait_delta_ns=${metrics.waitDeltaNanos ?: "unavailable"} slices_delta=${metrics.slicesDelta ?: "unavailable"}")
            }
            appendLine("target_cpu_changes_observed=${value.targetCpuChangesObserved ?: "unavailable"}")
            appendLine("target_runtime_delta_ns=${value.targetRuntimeDeltaNanos ?: "unavailable"}")
            appendLine("target_wait_delta_ns=${value.targetWaitDeltaNanos ?: "unavailable"}")
            appendLine("target_slices_delta=${value.targetSlicesDelta ?: "unavailable"}")
            appendLine("thread_migrations:")
            appendLine("parent=unavailable")
            appendLine("owner=unavailable")
            appendLine("waiter=unavailable")
            appendLine("consumer=unavailable")
            appendLine("involuntary_switch_delta:")
            appendLine("parent=unavailable")
            appendLine("owner=unavailable")
            appendLine("waiter=unavailable")
            appendLine("consumer=unavailable")
        }
    }

    return buildString {
        val analysis = RaceTelemetryParser.analyze(log)
        appendLine("race_analysis:")
        analysis.measurementsMicros.forEach { (name, value) -> appendLine("$name=${value ?: "unknown"}") }
        appendLine("trace_complete=${analysis.traceComplete ?: "unknown"}")
        appendLine("dropped_events=${analysis.droppedEvents ?: "unknown"}")
        appendLine("malformed_records=${analysis.malformedRecords}")
        appendLine("thread_migrations:")
        listOf("parent", "owner", "waiter", "consumer").forEach { role ->
            appendLine("$role=${analysis.schedulerDeltas[role] ?: "unknown"}")
        }
        appendLine("involuntary_switch_delta:")
        listOf("parent", "owner", "waiter", "consumer").forEach { role ->
            appendLine("$role=${analysis.schedulerDeltas["${role}_involuntary_switches"] ?: "unknown"}")
        }
    }
}
