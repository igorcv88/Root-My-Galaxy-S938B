package dev.busung.s25uroot

internal data class Czg3ProcessRoleMetrics(
    val expectedPids: Int,
    val sampledPids: Int,
    val processSamples: Int,
    val cpuChangesObserved: Int?,
    val runtimeDeltaNanos: Long?,
    val waitDeltaNanos: Long?,
    val slicesDelta: Long?,
)

internal data class Czg3ExternalRunSummary(
    val maxSupervisorAttempt: Int,
    val observerStartUptimeMillis: Long?,
    val observerStopUptimeMillis: Long?,
    val observerElapsedMillis: Long?,
    val exploitElapsedMillis: Long?,
    val observerTargetPid: Long?,
    val observerAttached: Boolean,
    val observerScope: String?,
    val observerDroppedEvents: Long?,
    val processSamples: Int,
    val traceComplete: Boolean,
    val processCoverageComplete: Boolean?,
    val processCoverageReason: String,
    val criticalSlidePid: Long?,
    val roleMetrics: Map<String, Czg3ProcessRoleMetrics>,
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
    private val payloadRelease = Regex("""RMG_BOOT_V1\|[^\n]*payload_release_uptime_ms=(\d+)""")
    private val nativeAttach = Regex("""RMG_OBSERVER_V2\|event=attach\|t_ms=(\d+)\|pid=(\d+)\|stat_access=(\d+)""")
    private val controllerScope = Regex("""RMG_OBSERVER_V2\|event=controller_start\|[^\n]*\|scope=([^|\n]+)""")
    private val marker = Regex("""RMG_OBSERVER_V2\|event=marker\|t_ms=(\d+)\|name=([a-z0-9_]+)\|""")
    private val discovered = Regex("""RMG_OBSERVER_V2\|event=pid_discovered\|t_ms=(\d+)\|role=([a-z_]+)\|pid=(\d+)\|starttime_ticks=([^|\n]+)\|identity_ok=(\d+)""")
    private val rawSlidePid = Regex("""slide child context[^\n]*\bpid=(\d+)""")
    private val procLine = Regex("""RMG_OBSERVER_V2\|event=proc\|([^\n]+)""")
    private val pselect = Regex("""slide pselect returned[^\n]*elapsed_usec=(\d+)""")
    private val fopsConsume = Regex("""app fops slide route[^\n]*effective_consume_usec=(\d+)""")
    private val fopsTrigger = Regex("""app fops stage=trigger-return[^\n]*triggered=(\d+)""")
    private val roles = listOf("helper", "supervisor", "attempt", "slide_child")

    fun parse(log: String): Czg3ExternalRunSummary {
        val maxAttempt = attempt.findAll(log)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val start = observerStart.find(log)?.groupValues?.get(1)?.toLongOrNull()
        val stopMatch = observerStop.findAll(log).lastOrNull()
        val stop = stopMatch?.groupValues?.get(1)?.toLongOrNull()
        val dropped = stopMatch?.groupValues?.get(2)?.toLongOrNull()
        val scope = controllerScope.findAll(log).lastOrNull()?.groupValues?.get(1)
        val release = payloadRelease.findAll(log).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
        val observerElapsed = if (start != null && stop != null && stop >= start) stop - start else null
        val exploitStart = when {
            release != null && stop != null && release <= stop && (start == null || release >= start) -> release
            else -> start
        }
        val exploitElapsed = if (exploitStart != null && stop != null && stop >= exploitStart) {
            stop - exploitStart
        } else {
            observerElapsed
        }

        val native = nativeAttach.findAll(log).lastOrNull()
        val targetPid = native?.groupValues?.get(2)?.toLongOrNull()
        val attached = native?.groupValues?.get(3) == "1"

        val markers = marker.findAll(log).mapNotNull { match ->
            val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            time to match.groupValues[2]
        }.toList()

        val discoveries = discovered.findAll(log).mapNotNull { match ->
            val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val role = match.groupValues[2]
            val pid = match.groupValues[3].toLongOrNull() ?: return@mapNotNull null
            val identityOk = match.groupValues[5] == "1"
            val starttime = match.groupValues[4].toLongOrNull()?.takeIf { identityOk && it > 0L }
            DiscoveredProcess(role, pid, starttime, time)
        }.toList()
        val expectedByRole = roles.associateWith { role ->
            discoveries.filter { it.role == role && it.starttimeTicks != null }
        }

        val procSamples = procLine.findAll(log).mapNotNull { match ->
            val fields = parseFields(match.groupValues[1])
            val pid = fields["pid"]?.toLongOrNull() ?: return@mapNotNull null
            val explicitRole = fields["role"]?.takeIf { it in roles }
            val role = explicitRole ?: if (scope != "process_tree_system" && pid == targetPid) "helper" else "tree"
            ProcessSample(
                timeMillis = fields["t_ms"]?.toLongOrNull() ?: 0L,
                pid = pid,
                starttimeTicks = fields["starttime_ticks"]?.toLongOrNull(),
                role = role,
                cpu = fields["cpu"]?.toIntOrNull()?.takeIf { it >= 0 },
                runtimeNanos = fields["runtime_ns"]?.toLongOrNull(),
                waitNanos = fields["wait_ns"]?.toLongOrNull(),
                slices = fields["slices"]?.toLongOrNull(),
            )
        }.toList()

        fun isCovered(expected: DiscoveredProcess): Boolean {
            val starttime = expected.starttimeTicks ?: return false
            return procSamples.any { sample ->
                sample.role == expected.role &&
                    sample.pid == expected.pid &&
                    sample.starttimeTicks == starttime &&
                    sample.timeMillis >= expected.discoveredMillis
            }
        }

        val roleMetrics = roles.associateWith { role ->
            buildRoleMetrics(role, expectedByRole.getValue(role), procSamples)
        }
        val helperMetrics = roleMetrics.getValue("helper")
        val criticalSlide = expectedByRole.getValue("slide_child").maxByOrNull(DiscoveredProcess::discoveredMillis)
        val slideObserved = rawSlidePid.containsMatchIn(log) ||
            discoveries.any { it.role == "slide_child" } ||
            markers.any { it.second == "slide_child" }

        val coverageProblems = mutableListOf<String>()
        if (scope == "process_tree_system") {
            if (!attached) coverageProblems += "helper_attach"
            fun requireRole(role: String, required: Boolean) {
                if (!required) return
                val all = discoveries.filter { it.role == role }
                if (all.isEmpty()) {
                    coverageProblems += "${role}_discovery"
                    return
                }
                if (all.any { it.starttimeTicks == null }) coverageProblems += "${role}_identity"
                val verified = all.filter { it.starttimeTicks != null }
                if (verified.isEmpty() || verified.any { !isCovered(it) })
                    coverageProblems += "${role}_sample"
            }
            requireRole("helper", true)
            requireRole("supervisor", true)
            requireRole("attempt", true)
            requireRole("slide_child", slideObserved)
        }
        val processCoverage = when (scope) {
            "process_tree_system" -> coverageProblems.isEmpty()
            "system_remote_markers" -> null
            else -> null
        }
        val coverageReason = when {
            scope == "system_remote_markers" -> "not_applicable_remote_markers"
            scope != "process_tree_system" -> "scope_unknown"
            coverageProblems.isEmpty() -> "complete"
            else -> coverageProblems.distinct().joinToString(",")
        }
        val sessionComplete = stop != null && dropped == 0L
        val traceComplete = when (scope) {
            "process_tree_system" -> sessionComplete && processCoverage == true
            "system_remote_markers" -> sessionComplete && markers.isNotEmpty()
            else -> sessionComplete && attached
        }

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

        return Czg3ExternalRunSummary(
            maxSupervisorAttempt = maxAttempt,
            observerStartUptimeMillis = start,
            observerStopUptimeMillis = stop,
            observerElapsedMillis = observerElapsed,
            exploitElapsedMillis = exploitElapsed,
            observerTargetPid = targetPid,
            observerAttached = attached,
            observerScope = scope,
            observerDroppedEvents = dropped,
            processSamples = procSamples.size,
            traceComplete = traceComplete,
            processCoverageComplete = processCoverage,
            processCoverageReason = coverageReason,
            criticalSlidePid = criticalSlide?.pid,
            roleMetrics = roleMetrics,
            p0Succeeded = p0Success,
            fopsReached = fopsReached,
            fopsTriggered = trigger,
            unsafeStop = unsafe,
            lastCheckpoint = checkpoint,
            lastCheckpointUptimeMillis = checkpointTime,
            lastPselectDurationMicros = pselect.findAll(log).lastOrNull()?.groupValues?.get(1)?.toLongOrNull(),
            fopsEffectiveConsumeMicros = fopsConsume.findAll(log).lastOrNull()?.groupValues?.get(1)?.toLongOrNull(),
            targetCpuChangesObserved = helperMetrics.cpuChangesObserved,
            targetRuntimeDeltaNanos = helperMetrics.runtimeDeltaNanos,
            targetWaitDeltaNanos = helperMetrics.waitDeltaNanos,
            targetSlicesDelta = helperMetrics.slicesDelta,
        )
    }

    private fun parseFields(body: String): Map<String, String> = body
        .split('|')
        .mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
        }
        .toMap()

    private fun buildRoleMetrics(
        role: String,
        expected: List<DiscoveredProcess>,
        allSamples: List<ProcessSample>,
    ): Czg3ProcessRoleMetrics {
        val samples = allSamples.filter { it.role == role }.sortedBy(ProcessSample::timeMillis)
        val byIdentity = samples.groupBy { it.pid to (it.starttimeTicks ?: -1L) }
        val sampledIdentities = samples.map { it.pid to (it.starttimeTicks ?: -1L) }.toSet()
        var cpuSeen = false
        var cpuChanges = 0
        byIdentity.values.forEach { identitySamples ->
            val cpus = identitySamples.mapNotNull(ProcessSample::cpu)
            if (cpus.isNotEmpty()) cpuSeen = true
            cpuChanges += cpus.zipWithNext().count { (a, b) -> a != b }
        }

        fun sumDelta(selector: (ProcessSample) -> Long?): Long? {
            var any = false
            var total = 0L
            byIdentity.values.forEach { identitySamples ->
                val values = identitySamples.mapNotNull(selector)
                val first = values.firstOrNull()
                val last = values.lastOrNull()
                if (first != null && last != null && last >= first) {
                    total += last - first
                    any = true
                }
            }
            return total.takeIf { any }
        }

        return Czg3ProcessRoleMetrics(
            expectedPids = expected.map { it.pid to it.starttimeTicks }.toSet().size,
            sampledPids = sampledIdentities.size,
            processSamples = samples.size,
            cpuChangesObserved = cpuChanges.takeIf { cpuSeen },
            runtimeDeltaNanos = sumDelta(ProcessSample::runtimeNanos),
            waitDeltaNanos = sumDelta(ProcessSample::waitNanos),
            slicesDelta = sumDelta(ProcessSample::slices),
        )
    }

    private data class DiscoveredProcess(
        val role: String,
        val pid: Long,
        val starttimeTicks: Long?,
        val discoveredMillis: Long,
    )

    private data class ProcessSample(
        val timeMillis: Long,
        val pid: Long,
        val starttimeTicks: Long?,
        val role: String,
        val cpu: Int?,
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

private val postExploitStages = setOf(
    ExploitStage.StagingKernelSu,
    ExploitStage.LateLoadingKernelSu,
    ExploitStage.VerifyingKernelSu,
)

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
    val normalizedStageTimings = if (elapsed == null) {
        entry.stageTimings
    } else {
        entry.stageTimings.map { timing ->
            if (timing.stage in postExploitStages && timing.elapsedMillis < elapsed) {
                timing.copy(elapsedMillis = elapsed)
            } else {
                timing
            }
        }
    }
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
        stageTimings = if (timing != null && normalizedStageTimings.lastOrNull() != timing) {
            normalizedStageTimings + timing
        } else {
            normalizedStageTimings
        },
    )
}

internal fun externalObserverAnalysisReport(log: String): String {
    if (!log.contains("RMG_OBSERVER_V2|")) return ""
    val value = Czg3ExternalTelemetryParser.parse(log)
    return buildString {
        appendLine("external_observer_analysis:")
        appendLine("attached=${value.observerAttached}")
        appendLine("target_pid=${value.observerTargetPid ?: "unknown"}")
        appendLine("observer_scope=${value.observerScope ?: "unknown"}")
        appendLine("observer_start_uptime_ms=${value.observerStartUptimeMillis ?: "unknown"}")
        appendLine("observer_stop_uptime_ms=${value.observerStopUptimeMillis ?: "unknown"}")
        appendLine("observer_elapsed_ms=${value.observerElapsedMillis ?: "unknown"}")
        appendLine("dropped_events=${value.observerDroppedEvents ?: "unknown"}")
        appendLine("process_samples=${value.processSamples}")
        appendLine("trace_complete=${value.traceComplete}")
        appendLine("process_coverage_complete=${value.processCoverageComplete ?: "not_applicable"}")
        appendLine("process_coverage_reason=${value.processCoverageReason}")
        appendLine("critical_slide_pid=${value.criticalSlidePid ?: "none"}")
        value.roleMetrics.forEach { (role, metrics) ->
            appendLine("role_${role}_expected_pids=${metrics.expectedPids}")
            appendLine("role_${role}_sampled_pids=${metrics.sampledPids}")
            appendLine("role_${role}_samples=${metrics.processSamples}")
            appendLine("role_${role}_cpu_changes=${metrics.cpuChangesObserved ?: "unavailable"}")
            appendLine("role_${role}_runtime_delta_ns=${metrics.runtimeDeltaNanos ?: "unavailable"}")
            appendLine("role_${role}_wait_delta_ns=${metrics.waitDeltaNanos ?: "unavailable"}")
            appendLine("role_${role}_slices_delta=${metrics.slicesDelta ?: "unavailable"}")
        }
        appendLine("max_supervisor_attempt=${value.maxSupervisorAttempt}")
        appendLine("p0_succeeded=${value.p0Succeeded}")
        appendLine("fops_reached=${value.fopsReached}")
        appendLine("fops_triggered=${value.fopsTriggered ?: "unknown"}")
        appendLine("unsafe_stop=${value.unsafeStop}")
        appendLine("last_checkpoint=${value.lastCheckpoint ?: "none"}")
        appendLine("last_checkpoint_uptime_ms=${value.lastCheckpointUptimeMillis ?: "unknown"}")
        appendLine("last_pselect_duration_us=${value.lastPselectDurationMicros ?: "unknown"}")
        appendLine("fops_effective_consume_us=${value.fopsEffectiveConsumeMicros ?: "unknown"}")
        appendLine("target_cpu_changes_observed=${value.targetCpuChangesObserved ?: "unavailable"}")
        appendLine("target_runtime_delta_ns=${value.targetRuntimeDeltaNanos ?: "unavailable"}")
        appendLine("target_wait_delta_ns=${value.targetWaitDeltaNanos ?: "unavailable"}")
        appendLine("target_slices_delta=${value.targetSlicesDelta ?: "unavailable"}")
    }
}

internal const val CZG3_PROFILE_ID_FOR_DIAGNOSTICS = "pa3q-S938BXXSBCZG3"
