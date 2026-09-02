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
    private val nativeAttach = Regex("""RMG_OBSERVER_V2\|event=attach\|t_ms=(\d+)\|pid=(\d+)\|stat_access=(\d+)""")
    private val controllerScope = Regex("""RMG_OBSERVER_V2\|event=controller_start\|[^\n]*\|scope=([^|\n]+)""")
    private val marker = Regex("""RMG_OBSERVER_V2\|event=marker\|t_ms=(\d+)\|name=([a-z0-9_]+)\|""")
    private val discovered = Regex("""RMG_OBSERVER_V2\|event=pid_discovered\|t_ms=\d+\|role=([a-z_]+)\|pid=(\d+)""")
    private val rawSupervisorPid = Regex("""preload supervisor pid=(\d+)""")
    private val rawAttemptPid = Regex("""\[\+\] exploit attempt=\d+/\d+[^\n]*\bpid=(\d+)""")
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

        val native = nativeAttach.findAll(log).lastOrNull()
        val targetPid = native?.groupValues?.get(2)?.toLongOrNull()
        val attached = native?.groupValues?.get(3) == "1"

        val markers = marker.findAll(log).mapNotNull { match ->
  val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
  time to match.groupValues[2]
        }.toList()

        val expected = linkedMapOf<String, LinkedHashSet<Long>>()
        fun addExpected(role: String, pid: Long?) {
  if (pid == null || pid <= 0L) return
  expected.getOrPut(role, ::LinkedHashSet).add(pid)
        }
        if (attached) addExpected("helper", targetPid)
        discovered.findAll(log).forEach { match ->
  addExpected(match.groupValues[1], match.groupValues[2].toLongOrNull())
        }
        rawSupervisorPid.findAll(log).forEach { addExpected("supervisor", it.groupValues[1].toLongOrNull()) }
        rawAttemptPid.findAll(log).forEach { addExpected("attempt", it.groupValues[1].toLongOrNull()) }
        rawSlidePid.findAll(log).forEach { addExpected("slide_child", it.groupValues[1].toLongOrNull()) }
        val expectedRoleByPid = linkedMapOf<Long, String>()
        roles.forEach { role -> expected[role].orEmpty().forEach { expectedRoleByPid[it] = role } }

        val procSamples = procLine.findAll(log).mapNotNull { match ->
  val fields = parseFields(match.groupValues[1])
  val pid = fields["pid"]?.toLongOrNull() ?: return@mapNotNull null
  val role = fields["role"]?.takeIf { it in roles }
      ?: expectedRoleByPid[pid]
      ?: if (pid == targetPid) "helper" else "tree"
  ProcessSample(
      timeMillis = fields["t_ms"]?.toLongOrNull() ?: 0L,
      pid = pid,
      role = role,
      cpu = fields["cpu"]?.toIntOrNull()?.takeIf { it >= 0 },
      runtimeNanos = fields["runtime_ns"]?.toLongOrNull(),
      waitNanos = fields["wait_ns"]?.toLongOrNull(),
      slices = fields["slices"]?.toLongOrNull(),
  )
        }.toList()

        val roleMetrics = roles.associateWith { role ->
  buildRoleMetrics(role, expected[role].orEmpty(), procSamples)
        }
        val helperMetrics = roleMetrics.getValue("helper")
        val criticalSlidePid = expected["slide_child"]?.lastOrNull()
        val sampledPids = procSamples.map(ProcessSample::pid).toSet()

        val coverageProblems = mutableListOf<String>()
        if (scope == "process_tree_system") {
  if (!attached) coverageProblems += "helper_attach"
  val helperExpected = expected["helper"].orEmpty()
  if (helperExpected.isEmpty() || !sampledPids.containsAll(helperExpected)) coverageProblems += "helper_sample"
  val supervisorExpected = expected["supervisor"].orEmpty()
  if (supervisorExpected.isEmpty() || !sampledPids.containsAll(supervisorExpected)) coverageProblems += "supervisor_sample"
  val attemptExpected = expected["attempt"].orEmpty()
  if (attemptExpected.isEmpty() || !sampledPids.containsAll(attemptExpected)) coverageProblems += "attempt_sample"
  if (criticalSlidePid != null && criticalSlidePid !in sampledPids) coverageProblems += "critical_slide_sample"
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
  else -> coverageProblems.joinToString(",")
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
  exploitElapsedMillis = if (start != null && stop != null && stop >= start) stop - start else null,
  observerTargetPid = targetPid,
  observerAttached = attached,
  observerScope = scope,
  observerDroppedEvents = dropped,
  processSamples = procSamples.size,
  traceComplete = traceComplete,
  processCoverageComplete = processCoverage,
  processCoverageReason = coverageReason,
  criticalSlidePid = criticalSlidePid,
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
        expectedPids: Set<Long>,
        allSamples: List<ProcessSample>,
    ): Czg3ProcessRoleMetrics {
        val samples = allSamples.filter { it.role == role }.sortedBy(ProcessSample::timeMillis)
        val byPid = samples.groupBy(ProcessSample::pid)
        val sampledPids = samples.map(ProcessSample::pid).toSet()
        var cpuSeen = false
        var cpuChanges = 0
        byPid.values.forEach { pidSamples ->
  val cpus = pidSamples.mapNotNull(ProcessSample::cpu)
  if (cpus.isNotEmpty()) cpuSeen = true
  cpuChanges += cpus.zipWithNext().count { (a, b) -> a != b }
        }

        fun sumDelta(selector: (ProcessSample) -> Long?): Long? {
  var any = false
  var total = 0L
  byPid.values.forEach { pidSamples ->
      val values = pidSamples.mapNotNull(selector)
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
  expectedPids = expectedPids.size,
  sampledPids = sampledPids.size,
  processSamples = samples.size,
  cpuChangesObserved = cpuChanges.takeIf { cpuSeen },
  runtimeDeltaNanos = sumDelta(ProcessSample::runtimeNanos),
  waitDeltaNanos = sumDelta(ProcessSample::waitNanos),
  slicesDelta = sumDelta(ProcessSample::slices),
        )
    }

    private data class ProcessSample(
        val timeMillis: Long,
        val pid: Long,
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
        appendLine("observer_scope=${value.observerScope ?: "unknown"}")
        appendLine("observer_start_uptime_ms=${value.observerStartUptimeMillis ?: "unknown"}")
        appendLine("observer_stop_uptime_ms=${value.observerStopUptimeMillis ?: "unknown"}")
        appendLine("observer_elapsed_ms=${value.exploitElapsedMillis ?: "unknown"}")
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
