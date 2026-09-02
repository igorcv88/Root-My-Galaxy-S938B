package dev.busung.s25uroot

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class InstallRunResult { Running, Succeeded, Failed, UnexpectedReboot }
enum class KernelCrashRecordStatus { Found, NotAccessible, NoneFound }

data class StageTiming(val stage: ExploitStage, val elapsedMillis: Long, val attempt: Int? = null)

data class InstallHistoryEntry(
    val id: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val result: InstallRunResult,
    val log: String,
    val profileId: String? = null,
    val usedShizuku: Boolean = false,
    val bootId: String? = null,
    val startedAtUptimeMillis: Long? = null,
    val deviceIdentity: String? = null,
    val appVersion: String? = null,
    val payloadSha256: String? = null,
    val payloadSize: Long? = null,
    val stage: ExploitStage? = null,
    val attemptCount: Int = 0,
    val exploitElapsedMillis: Long? = null,
    val failureClass: ExploitFailureClass? = null,
    val safety: ExploitSafety? = null,
    val outcome: ExploitOutcome? = null,
    val stageTimings: List<StageTiming> = emptyList(),
    val unexpectedReboot: Boolean = false,
    val crashRecordStatus: KernelCrashRecordStatus? = null,
    val crashRecord: String? = null,
    val invocationMode: String? = null,
    val selectedMinUptimeSeconds: Int? = null,
    val lastPrepCheckpoint: String? = null,
    val lastPrepCheckpointUptimeMillis: Long? = null,
)

data class DiagnosticAggregate(
    val totalRuns: Int,
    val successfulRuns: Int,
    val successRate: Double,
    val medianAcquisitionMillis: Long?,
    val p90AcquisitionMillis: Long?,
    val medianAttemptCount: Double?,
    val uptimeBuckets: Map<String, Pair<Int, Int>>,
)

internal fun aggregateDiagnostics(entries: List<InstallHistoryEntry>): DiagnosticAggregate {
    val normalized = entries
    val terminal = normalized.filter { it.result != InstallRunResult.Running }
    val successful = terminal.filter { it.result == InstallRunResult.Succeeded }
    val elapsed = successful.mapNotNull(InstallHistoryEntry::exploitElapsedMillis).sorted()
    val attempts = terminal.map(InstallHistoryEntry::attemptCount).filter { it > 0 }.sorted()
    val buckets = linkedMapOf("<10m" to intArrayOf(0, 0), "10-60m" to intArrayOf(0, 0), "1-6h" to intArrayOf(0, 0), ">=6h" to intArrayOf(0, 0))
    terminal.forEach { entry ->
        val uptime = entry.startedAtUptimeMillis ?: return@forEach
        val key = when {
            uptime < 10 * 60_000L -> "<10m"
            uptime < 60 * 60_000L -> "10-60m"
            uptime < 6 * 60 * 60_000L -> "1-6h"
            else -> ">=6h"
        }
        buckets.getValue(key)[0]++
        if (entry.result == InstallRunResult.Succeeded) buckets.getValue(key)[1]++
    }
    return DiagnosticAggregate(
        totalRuns = terminal.size,
        successfulRuns = successful.size,
        successRate = if (terminal.isEmpty()) 0.0 else successful.size.toDouble() / terminal.size,
        medianAcquisitionMillis = percentile(elapsed, 0.5),
        p90AcquisitionMillis = percentile(elapsed, 0.9),
        medianAttemptCount = if (attempts.isEmpty()) null else if (attempts.size % 2 == 1) attempts[attempts.size / 2].toDouble() else (attempts[attempts.size / 2 - 1] + attempts[attempts.size / 2]) / 2.0,
        uptimeBuckets = buckets.mapValues { (_, counts) -> counts[0] to counts[1] },
    )
}

private fun percentile(sorted: List<Long>, fraction: Double): Long? = if (sorted.isEmpty()) null else {
    sorted[kotlin.math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
}

internal fun recoverInterruptedEntry(entry: InstallHistoryEntry, currentBootId: String?, completedAtMillis: Long): InstallHistoryEntry {
    if (entry.result != InstallRunResult.Running) return entry
    val rebooted = entry.bootId != null && currentBootId != null && entry.bootId != currentBootId
    return entry.copy(
        completedAtMillis = completedAtMillis,
        result = if (rebooted) InstallRunResult.UnexpectedReboot else InstallRunResult.Failed,
        unexpectedReboot = rebooted,
        log = (entry.log + if (rebooted) "\n[-] Previous exploit run ended during an unexpected reboot" else "\n[-] Previous run ended without a terminal state").trim(),
    )
}

internal fun historyIdsToPrune(entries: List<InstallHistoryEntry>, maximum: Int): Set<String> {
    require(maximum > 0)
    return entries.sortedByDescending(InstallHistoryEntry::startedAtMillis).drop(maximum).mapTo(mutableSetOf()) { it.id }
}

internal fun historyLogForStorage(log: String): String = log

class InstallHistoryStore(private val context: Context) {
    private val directory = File(context.filesDir, "install-history").apply { mkdirs() }

    fun load(): List<InstallHistoryEntry> = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        .mapNotNull(::decodeOrQuarantine).sortedByDescending(InstallHistoryEntry::startedAtMillis)

    fun closeInterruptedRuns(currentBootId: String? = AutoRootSupport.currentBootToken()): List<InstallHistoryEntry> {
        recoverInterruptedRuns(currentBootId)
        return load()
    }

    fun recoverInterruptedRuns(currentBootId: String? = AutoRootSupport.currentBootToken()) {
        val completedAtMillis = System.currentTimeMillis()
        directory.listFiles { file -> file.extension == "json" }.orEmpty()
            .filter(::mightContainRunningEntry)
            .mapNotNull(::decodeOrQuarantine)
            .filter { it.result == InstallRunResult.Running }
            .forEach { entry ->
                val recovered = recoverInterruptedEntry(entry, currentBootId, completedAtMillis)
                val completed = if (recovered.result == InstallRunResult.UnexpectedReboot) {
                    val record = PstoreCollector.collect(recovered.usedShizuku)
                    recovered.copy(crashRecordStatus = record.status, crashRecord = record.content)
                } else {
                    recovered
                }
                save(completed)
            }
    }

    fun create(bootId: String? = AutoRootSupport.currentBootToken(), snapshot: DeviceSnapshot = DeviceSnapshot.current()): InstallHistoryEntry = InstallHistoryEntry(
        id = UUID.randomUUID().toString(), startedAtMillis = System.currentTimeMillis(), completedAtMillis = null,
        result = InstallRunResult.Running, log = "", usedShizuku = AppPreferences.shizukuMode(context), bootId = bootId,
        startedAtUptimeMillis = SystemClock.elapsedRealtime(), deviceIdentity = snapshot.diagnosticIdentity(), appVersion = BuildConfig.VERSION_NAME,
    ).also(::save)

    @Synchronized
    fun save(entry: InstallHistoryEntry) {
        val normalized = normalizeCzg3ExternalHistory(entry)
        val target = File(directory, "${normalized.id}.json")
        val isNewEntry = !target.exists()
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            output.write(encode(normalized).toString().toByteArray(Charsets.UTF_8)); output.flush(); output.fd.sync(); atomicFile.finishWrite(output)
            if (isNewEntry) pruneHistoryFiles()
        } catch (error: Throwable) { atomicFile.failWrite(output); throw error }
    }

    fun delete(id: String) { File(directory, "$id.json").delete() }

    private fun mightContainRunningEntry(file: File): Boolean = runCatching {
        file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(RUNNING_ENTRY_SCAN_CHARS)
            val count = reader.read(buffer)
            count > 0 && String(buffer, 0, count).contains("\"result\":\"Running\"")
        }
    }.getOrDefault(false)

    private fun pruneHistoryFiles() {
        val files = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        if (files.size <= MAX_HISTORY_ENTRIES) return
        files.sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenByDescending(File::getName),
        ).drop(MAX_HISTORY_ENTRIES).forEach(File::delete)
    }

    private fun encode(entry: InstallHistoryEntry) = JSONObject()
        .put("id", entry.id).put("startedAtMillis", entry.startedAtMillis).put("completedAtMillis", entry.completedAtMillis ?: JSONObject.NULL)
        .put("result", entry.result.name).put("log", historyLogForStorage(entry.log)).put("profileId", entry.profileId ?: JSONObject.NULL)
        .put("usedShizuku", entry.usedShizuku).put("bootId", entry.bootId ?: JSONObject.NULL)
        .put("startedAtUptimeMillis", entry.startedAtUptimeMillis ?: JSONObject.NULL).put("deviceIdentity", entry.deviceIdentity ?: JSONObject.NULL)
        .put("appVersion", entry.appVersion ?: JSONObject.NULL).put("payloadSha256", entry.payloadSha256 ?: JSONObject.NULL)
        .put("payloadSize", entry.payloadSize ?: JSONObject.NULL).put("stage", entry.stage?.name ?: JSONObject.NULL)
        .put("attemptCount", entry.attemptCount).put("exploitElapsedMillis", entry.exploitElapsedMillis ?: JSONObject.NULL)
        .put("failureClass", entry.failureClass?.name ?: JSONObject.NULL).put("safety", entry.safety?.name ?: JSONObject.NULL)
        .put("outcome", entry.outcome?.name ?: JSONObject.NULL).put("unexpectedReboot", entry.unexpectedReboot)
        .put("crashRecordStatus", entry.crashRecordStatus?.name ?: JSONObject.NULL).put("crashRecord", entry.crashRecord ?: JSONObject.NULL)
        .put("invocationMode", entry.invocationMode ?: JSONObject.NULL)
        .put("selectedMinUptimeSeconds", entry.selectedMinUptimeSeconds ?: JSONObject.NULL)
        .put("lastPrepCheckpoint", entry.lastPrepCheckpoint ?: JSONObject.NULL)
        .put("lastPrepCheckpointUptimeMillis", entry.lastPrepCheckpointUptimeMillis ?: JSONObject.NULL)
        .put("stageTimings", JSONArray().apply { entry.stageTimings.takeLast(MAX_STAGE_TIMINGS).forEach { timing -> put(JSONObject().put("stage", timing.stage.name).put("elapsedMillis", timing.elapsedMillis).put("attempt", timing.attempt ?: JSONObject.NULL)) } })

    private fun decodeOrQuarantine(file: File): InstallHistoryEntry? = try { decode(AtomicFile(file).openRead().use { it.readBytes() }) } catch (_: Throwable) {
        File(directory, "${file.name}.corrupt").also { it.delete(); file.renameTo(it) }; null
    }

    private fun decode(bytes: ByteArray): InstallHistoryEntry {
        val value = JSONObject(bytes.toString(Charsets.UTF_8)); val timings = value.optJSONArray("stageTimings")
        return InstallHistoryEntry(
            id = value.getString("id"), startedAtMillis = value.getLong("startedAtMillis"), completedAtMillis = value.optionalLong("completedAtMillis"),
            result = InstallRunResult.valueOf(value.getString("result")), log = value.optString("log"), profileId = value.optionalString("profileId"),
            usedShizuku = value.optBoolean("usedShizuku", false), bootId = value.optionalString("bootId"), startedAtUptimeMillis = value.optionalLong("startedAtUptimeMillis"),
            deviceIdentity = value.optionalString("deviceIdentity"), appVersion = value.optionalString("appVersion"), payloadSha256 = value.optionalString("payloadSha256"),
            payloadSize = value.optionalLong("payloadSize"), stage = value.optionalString("stage")?.let(ExploitStage::valueOf), attemptCount = value.optInt("attemptCount", 0),
            exploitElapsedMillis = value.optionalLong("exploitElapsedMillis"), failureClass = value.optionalString("failureClass")?.let(ExploitFailureClass::valueOf),
            safety = value.optionalString("safety")?.let(ExploitSafety::valueOf), outcome = value.optionalString("outcome")?.let(ExploitOutcome::valueOf),
            stageTimings = buildList { if (timings != null) for (index in 0 until timings.length()) timings.getJSONObject(index).let { add(StageTiming(ExploitStage.valueOf(it.getString("stage")), it.getLong("elapsedMillis"), it.optionalInt("attempt"))) } },
            unexpectedReboot = value.optBoolean("unexpectedReboot", false), crashRecordStatus = value.optionalString("crashRecordStatus")?.let(KernelCrashRecordStatus::valueOf),
            crashRecord = value.optionalString("crashRecord"),
            invocationMode = value.optionalString("invocationMode"),
            selectedMinUptimeSeconds = value.optionalInt("selectedMinUptimeSeconds"),
            lastPrepCheckpoint = value.optionalString("lastPrepCheckpoint"),
            lastPrepCheckpointUptimeMillis = value.optionalLong("lastPrepCheckpointUptimeMillis"),
        ).let(::normalizeCzg3ExternalHistory)
    }

    companion object {
        internal const val MAX_HISTORY_ENTRIES = 50
        private const val MAX_STAGE_TIMINGS = 128
        private const val RUNNING_ENTRY_SCAN_CHARS = 2_048
    }
}

private fun DeviceSnapshot.diagnosticIdentity(): String = listOf(manufacturer, model, device, buildId, fingerprint, kernelRelease, kernelVersionInfo, machine, sdk.toString(), abi, pageSize.toString()).joinToString("|")
private fun JSONObject.optionalString(name: String): String? = if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)
private fun JSONObject.optionalLong(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
private fun JSONObject.optionalInt(name: String): Int? = if (!has(name) || isNull(name)) null else getInt(name)
