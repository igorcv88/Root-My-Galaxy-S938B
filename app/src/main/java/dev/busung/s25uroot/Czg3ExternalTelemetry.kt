package dev.busung.s25uroot

internal data class Czg3ExternalRunSummary(
    val maxSupervisorAttempt: Int,
    val observerStartUptimeMillis: Long?,
    val observerStopUptimeMillis: Long?,
    val exploitElapsedMillis: Long?,
    val observerTargetPid: Long?,
    val observerAttached: Boolean,
    val observerDroppedEvents: Long?,
    val processSamples: Int,
    val p0Succeeded: Boolean,
    val fopsReached: Boolean,
    val fopsTriggered: Boolean?,
    val unsafeStop: Boolean,
    val lastCheckpoint: String?,
    val lastCheckpointUptimeMillis: Long?,
    val lastPselectDurationMicros: Long?,
    val fopsEffectiveConsumeMicros: Long?,
    val targetCpuChangesObserved: Int?,
    val targetRuntimeDeltaNanos: Long?,
    val targetWaitDeltaNanos: Long?,
    val targetSlicesDelta: Long?,
)

internal object Czg3ExternalTelemetryParser {
    private val attempt = Regex("""\bexploit attempt=(\d+)/(\d+)\b""")
    private val observerStart = Regex("""RMG_OBSERVER_V2\|event=start\|t_ms=(\d+)""")
    private val observerStop = Regex("""RMG_OBSERVER_V2\|event=stop\|t_ms=(\d+)\|dropped=(\d+)""")
    private val nativeAttach = Regex("""RMG_OBSERVER_V2\|event=attach\|t_ms=(\d+)\|pid=(\d+)\|stat_access=(\d+)""")
    private val marker = Regex("""RMG_OBSERVER_V2\|event=marker\|t_ms=(\d+)\|name=([a-z0-9_]+)\|""")
    private val pselect = Regex("""slide pselect returned[^\n]*elapsed_usec=(\d+)""")
    private val fopsConsume = Regex("""app fops slide route[^\n]*effective_consume_usec=(\d+)""")
    private val fopsTrigger = Regex("""app fops stage=trigger-return[^\n]*triggered=(\d+)""")
    private val proc = Regex(
        """RMG_OBSERVER_V2\|event=proc\|t_ms=(\d+)\|pid=(\d+)\|[^\n]*\|cpu=(-?\d+)\|[^\n]*\|runtime_ns=([^|]+)\|wait_ns=([^|]+)\|slices=([^|\n]+)""",
    )

    fun parse(log: String): Czg3ExternalRunSummary {
        val maxAttempt = attempt.findAll(log)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val start = observerStart.find(log)?.groupValues?.get(1)?.toLongOrNull()
        val stopMatch = observerStop.findAll(log).lastOrNull()
        val stop = stopMatch?.groupValues?.get(1)?.toLongOrNull()
        val dropped = stopMatch?.groupValues?.get(2)?.toLongOrNull()

        // Only a native acknowledgement proves that the observer process could
        // actually read the target. Controller startService success is merely a
        // queued request and must never be promoted to a verified attachment.
        val native = nativeAttach.findAll(log).lastOrNull()
        val targetPid = native?.groupValues?.get(2)?.toLongOrNull()
        val attached = native?.groupValues?.get(3) == "1"

        val markers = marker.findAll(log).mapNotNull { match ->
            val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            time to match.groupValues[2]
        }.toList()

        val p0Success = log.contains("slide-kaslr-ok") || markers.any { it.second == "p0_success" }
        val fopsReached = log.contains("durable log checkpoint stage=fops-page-held") ||
            log.contains("app fops slide route") ||
            markers.any { it.second == "fops_page_held" || it.second == "fops_route" }
        val trigger = fopsTrigger.findAll(log).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull()?.let { it != 0 }
        val unsafe = log.contains("writer route outcome is mutation-uncertain", ignoreCase = true) ||
            markers.any { it.second == "unsafe_stop" }

        val checkpoint = when {
            fopsReached -> "fops/page-held"
            p0Success -> "p0/slide-kaslr-ok"
            log.contains("p0 pipe oracle prepared") -> "p0/oracle-ready"
            else -> null
        }
        val checkpointTime = when (checkpoint) {
            "fops/page-held" -> markers.lastOrNull { it.second == "fops_page_held" || it.second == "fops_route" }?.first
            "p0/slide-kaslr-ok" -> markers.lastOrNull { it.second == "p0_success" }?.first
            "p0/oracle-ready" -> markers.lastOrNull { it.second == "p0_oracle_ready" }?.first
            else -> null
        }

        val procSamples = proc.findAll(log).mapNotNull { match ->
            val pid = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val cpu = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            ProcessSample(
                pid = pid,
                cpu = cpu,
                runtimeNanos = match.groupValues[4].toLongOrNull(),
                waitNanos = match.groupValues[5].toLongOrNull(),
                slices = match.groupValues[6].toLongOrNull(),
            )
        }.toList()
        val targetSamples = targetPid?.let { root -> procSamples.filter { it.pid == root } }.orEmpty()
        val cpuChanges = targetSamples.takeIf(List<ProcessSample>::isNotEmpty)?.zipWithNext()
            ?.count { (a, b) -> a.cpu != b.cpu }
        val firstSched = targetSamples.firstOrNull { it.runtimeNanos != null && it.waitNanos != null && it.slices != null }
        val lastSched = targetSamples.lastOrNull { it.runtimeNanos != null && it.waitNanos != null && it.slices != null }

        fun delta(first: Long?, last: Long?): Long? {
            if (first == null || last == null || last < first) return null
            return last - first
        }

        return Czg3ExternalRunSummary(
            maxSupervisorAttempt = maxAttempt,
            observerStartUptimeMillis = start,
            observerStopUptimeMillis = stop,
            exploitElapsedMillis = if (start != null && stop != null && stop >= start) stop - start else null,
            observerTargetPid = targetPid,
            observerAttached = attached,
            observerDroppedEvents = dropped,
            processSamples = procSamples.size,
            p0Succeeded = p0Success,
            fopsReached = fopsReached,
            fopsTriggered = trigger,
            unsafeStop = unsafe,
            lastCheckpoint = checkpoint,
            lastCheckpointUptimeMillis = checkpointTime,
            lastPselectDurationMicros = pselect.findAll(log).lastOrNull()?.groupValues?.get(1)?.toLongOrNull(),
            fopsEffectiveConsumeMicros = fopsConsume.findAll(log).lastOrNull()?.groupValues?.get(1)?.toLongOrNull(),
            targetCpuChangesObserved = cpuChanges,
            targetRuntimeDeltaNanos = delta(firstSched?.runtimeNanos, lastSched?.runtimeNanos),
            targetWaitDeltaNanos = delta(firstSched?.waitNanos, lastSched?.waitNanos),
            targetSlicesDelta = delta(firstSched?.slices, lastSched?.slices),
        )
    }

    private data class ProcessSample(
        val pid: Long,
        val cpu: Int,
        val runtimeNanos: Long?,
        val waitNanos: Long?,
        val slices: Long?,
    )
}

private fun laterExploitStage(existing: ExploitStage?, inferred: ExploitStage?): ExploitStage? = when {
    existing == null -> inferred
    inferred == null -> existing
    existing.ordinal >= inferred.ordinal -> existing
    else -> inferred
}

internal fun normalizeCzg3ExternalHistory(entry: InstallHistoryEntry): InstallHistoryEntry {
    if (entry.profileId != CZG3_PROFILE_ID_FOR_DIAGNOSTICS && !entry.log.contains("RMG_OBSERVER_V2|")) return entry
    val summary = Czg3ExternalTelemetryParser.parse(entry.log)
    val inferredStage = when {
        summary.fopsReached -> ExploitStage.AttemptingRace
        summary.p0Succeeded -> ExploitStage.ValidatingPrimitive
        summary.maxSupervisorAttempt > 0 -> ExploitStage.ResolvingKernelState
        else -> null
    }
    val stage = laterExploitStage(entry.stage, inferredStage)
    val elapsed = maxOf(entry.exploitElapsedMillis ?: 0L, summary.exploitElapsedMillis ?: 0L)
        .takeIf { it > 0L } ?: entry.exploitElapsedMillis
    val attemptCount = maxOf(entry.attemptCount, summary.maxSupervisorAttempt)
    val checkpoint = entry.lastPrepCheckpoint ?: summary.lastCheckpoint
    val checkpointUptime = entry.lastPrepCheckpointUptimeMillis ?: summary.lastCheckpointUptimeMillis
    val canRecordInferredTiming = inferredStage != null &&
        (entry.stage == null || entry.stage.ordinal <= inferredStage.ordinal)
    val timing = if (canRecordInferredTiming && elapsed != null) {
        StageTiming(inferredStage, elapsed, attemptCount.takeIf { it > 0 })
    } else {
        null
    }
    return entry.copy(
        stage = stage,
        attemptCount = attemptCount,
        exploitElapsedMillis = elapsed,
        lastPrepCheckpoint = checkpoint,
        lastPrepCheckpointUptimeMillis = checkpointUptime,
        stageTimings = if (timing != null && entry.stageTimings.lastOrNull() != timing) entry.stageTimings + timing else entry.stageTimings,
    )
}

internal fun externalObserverAnalysisReport(log: String): String {
    if (!log.contains("RMG_OBSERVER_V2|")) return ""
    val value = Czg3ExternalTelemetryParser.parse(log)
    return buildString {
        appendLine("external_observer_analysis:")
        appendLine("attached=${value.observerAttached}")
        appendLine("target_pid=${value.observerTargetPid ?: "unknown"}")
        appendLine("observer_start_uptime_ms=${value.observerStartUptimeMillis ?: "unknown"}")
        appendLine("observer_stop_uptime_ms=${value.observerStopUptimeMillis ?: "unknown"}")
        appendLine("observer_elapsed_ms=${value.exploitElapsedMillis ?: "unknown"}")
        appendLine("dropped_events=${value.observerDroppedEvents ?: "unknown"}")
        appendLine("process_samples=${value.processSamples}")
        appendLine("max_supervisor_attempt=${value.maxSupervisorAttempt}")
        appendLine("p0_succeeded=${value.p0Succeeded}")
        appendLine("fops_reached=${value.fopsReached}")
        appendLine("fops_triggered=${value.fopsTriggered ?: "unknown"}")
        appendLine("unsafe_stop=${value.unsafeStop}")
        appendLine("last_checkpoint=${value.lastCheckpoint ?: "none"}")
        appendLine("last_checkpoint_uptime_ms=${value.lastCheckpointUptimeMillis ?: "unknown"}")
        appendLine("last_pselect_duration_us=${value.lastPselectDurationMicros ?: "unknown"}")
        appendLine("fops_effective_consume_us=${value.fopsEffectiveConsumeMicros ?: "unknown"}")
        appendLine("target_cpu_changes_observed=${value.targetCpuChangesObserved ?: "unknown"}")
        appendLine("target_runtime_delta_ns=${value.targetRuntimeDeltaNanos ?: "unknown"}")
        appendLine("target_wait_delta_ns=${value.targetWaitDeltaNanos ?: "unknown"}")
        appendLine("target_slices_delta=${value.targetSlicesDelta ?: "unknown"}")
    }
}

internal const val CZG3_PROFILE_ID_FOR_DIAGNOSTICS = "pa3q-S938BXXSBCZG3"
