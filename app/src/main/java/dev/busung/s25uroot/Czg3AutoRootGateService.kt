package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val CZG3_AUTO_ROOT_GATE_NOTIFICATION_ID = 43500

/**
 * Lightweight boot coordinator for the exact CZG3 target.
 *
 * The coordinator owns only the minimum-uptime wait. At the gate it binds a fresh
 * :autoroot_exec process while this service remains the sole foreground service.
 * That avoids a delayed startForegroundService() call after the BOOT_COMPLETED
 * exemption has expired, while still preventing the exploit helper from inheriting
 * the long-lived boot coordinator's process state. No exploit timing, retry,
 * observer, or fail-closed policy is changed here.
 */
class Czg3AutoRootGateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var handedOff = false
    private var executorBound = false
    private var shuttingDown = false

    private val executorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "RMG_AUTOROOT_COORD_GATE_V1|event=executor_connected|executor=fresh_bound_process")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleExecutorBindingLoss("service_disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleExecutorBindingLoss("binding_died")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleExecutorBindingLoss("null_binding")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (gateJob?.isActive == true) return START_NOT_STICKY

        val notification = buildNotification(getString(R.string.autoroot_stabilizing_android))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                CZG3_AUTO_ROOT_GATE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(CZG3_AUTO_ROOT_GATE_NOTIFICATION_ID, notification)
        }

        gateJob = scope.launch {
            try {
                runGate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "CZG3 Auto Root gate failed", error)
                Czg3AutoRootGateState.clear(this@Czg3AutoRootGateService)
                stopWithoutResult()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        gateJob?.cancel()
        gateJob = null
        if (executorBound) {
            runCatching { unbindService(executorConnection) }
            executorBound = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runGate() {
        val bootToken = AutoRootSupport.currentBootToken() ?: run {
            stopWithoutResult()
            return
        }
        Czg3AutoRootGateState.recoverPreviousBootIfNeeded(this, bootToken)

        if (!AppPreferences.autoRootEnabled(this) ||
            !AutoRootSupport.shouldRunForBoot(this, bootToken) ||
            NativeProbe.isKernelSuActive()
        ) {
            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
            }
            Czg3AutoRootGateState.clear(this)
            stopWithoutResult()
            return
        }

        val payloads = AutoRootSupport.loadVerifiedLocalPayloads(this)
        require(payloads.profile.profileId == CZG3_PROFILE_ID) {
            getString(R.string.autoroot_profile_invalid)
        }
        val minimumUptimeSeconds = AppPreferences.czg3BootMinUptimeSeconds(this)
        val requestedAtUptimeMillis = SystemClock.elapsedRealtime()
        val targetUptimeMillis = minimumUptimeSeconds.toLong() * 1_000L
        val waitMillis = (targetUptimeMillis - requestedAtUptimeMillis).coerceAtLeast(0L)

        Czg3AutoRootGateState.write(
            this,
            Czg3AutoRootGateState.Record(
                bootId = bootToken,
                startedAtMillis = System.currentTimeMillis(),
                startedAtUptimeMillis = requestedAtUptimeMillis,
                appVersion = BuildConfig.VERSION_NAME,
                profileId = payloads.profile.profileId,
                payloadSha256 = payloads.profile.exploit.sha256,
                payloadSize = payloads.profile.exploit.size,
                selectedMinUptimeSeconds = minimumUptimeSeconds,
                handedOff = false,
            ),
        )
        Log.i(
            TAG,
            "RMG_AUTOROOT_COORD_GATE_V1|event=start|request_uptime_ms=$requestedAtUptimeMillis|" +
                "target_uptime_ms=$targetUptimeMillis|wait_ms=$waitMillis|executor=fresh_bound_process",
        )

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:Czg3AutoRootGate",
        ).also { lock ->
            lock.acquire((waitMillis + WAKELOCK_HANDOFF_MARGIN_MILLIS).coerceAtLeast(30_000L))
        }

        if (waitMillis > 0L) delay(waitMillis)

        if (!AppPreferences.autoRootEnabled(this)) {
            Czg3AutoRootGateState.clear(this)
            stopWithoutResult()
            return
        }
        val releaseBootToken = AutoRootSupport.currentBootToken()
        if (releaseBootToken != bootToken) {
            // Keep the durable record so the next boot can classify the interrupted
            // gate rather than silently losing the pre-exploit diagnostic state.
            stopWithoutResult(clearState = false)
            return
        }
        if (NativeProbe.isKernelSuActive()) {
            AutoRootSupport.markVerifiedForBoot(this, bootToken)
            Czg3AutoRootGateState.clear(this)
            stopWithoutResult()
            return
        }

        val releaseUptimeMillis = SystemClock.elapsedRealtime()
        val currentRecord = Czg3AutoRootGateState.read(this)
        if (currentRecord != null) {
            Czg3AutoRootGateState.write(this, currentRecord.copy(handedOff = true))
        }
        handedOff = true
        Log.i(
            TAG,
            "RMG_AUTOROOT_COORD_GATE_V1|event=release|request_uptime_ms=$requestedAtUptimeMillis|" +
                "release_uptime_ms=$releaseUptimeMillis|wait_ms=${releaseUptimeMillis - requestedAtUptimeMillis}|" +
                "executor=fresh_bound_process",
        )

        val executorIntent = Intent(this, AutoRootService::class.java)
            .setAction(AutoRootService.ACTION_RUN_BOUND_CZG3)
        require(bindService(executorIntent, executorConnection, Context.BIND_AUTO_CREATE)) {
            "Unable to bind CZG3 Auto Root executor"
        }
        executorBound = true
        // Stay foreground and bound until the executor finishes. AutoRootService
        // updates this notification and stops this gate when its terminal state is
        // durable. This avoids any delayed second-FGS start restriction.
    }

    private fun handleExecutorBindingLoss(reason: String) {
        if (shuttingDown) return
        Log.e(TAG, "CZG3 Auto Root executor binding lost: $reason")
        scope.launch {
            val bootToken = AutoRootSupport.currentBootToken()
            if (bootToken != null) {
                Czg3AutoRootGateState.recoverExecutorDisconnect(
                    context = this@Czg3AutoRootGateService,
                    currentBootId = bootToken,
                    reason = reason,
                )
            }
            stopWithoutResult(clearState = false)
        }
    }

    private fun stopWithoutResult(clearState: Boolean = true) {
        if (clearState && !handedOff) Czg3AutoRootGateState.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.autoroot_notification_title))
            .setContentText(message)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.autoroot_disable),
                PendingIntent.getBroadcast(
                    this,
                    1,
                    Intent(this, AutoRootActionReceiver::class.java)
                        .setAction(AutoRootActionReceiver.ACTION_DISABLE_AUTO_ROOT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.autoroot_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.autoroot_channel_description)
            },
        )
    }

    companion object {
        private const val TAG = "RootMyGalaxyCzg3Gate"
        private const val CHANNEL_ID = "auto_root_postboot"
        private const val CZG3_PROFILE_ID = "pa3q-S938BXXSBCZG3"
        private const val WAKELOCK_HANDOFF_MARGIN_MILLIS = 60_000L
    }
}

/**
 * A tiny durable sentinel preserves the diagnostic boundary while the lightweight
 * gate is waiting and before the normal Auto Root History entry is created.
 */
internal object Czg3AutoRootGateState {
    private const val VERSION = 1
    private const val STATE_PATH = "autoroot/czg3-gate-state.txt"

    data class Record(
        val bootId: String,
        val startedAtMillis: Long,
        val startedAtUptimeMillis: Long,
        val appVersion: String,
        val profileId: String,
        val payloadSha256: String,
        val payloadSize: Long,
        val selectedMinUptimeSeconds: Int,
        val handedOff: Boolean,
    )

    fun write(context: Context, record: Record) {
        val file = stateFile(context)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val content = buildString {
                appendLine("version=$VERSION")
                appendLine("boot_id=${record.bootId}")
                appendLine("started_at_ms=${record.startedAtMillis}")
                appendLine("started_uptime_ms=${record.startedAtUptimeMillis}")
                appendLine("app_version=${record.appVersion}")
                appendLine("profile=${record.profileId}")
                appendLine("payload_sha256=${record.payloadSha256}")
                appendLine("payload_size=${record.payloadSize}")
                appendLine("min_uptime_sec=${record.selectedMinUptimeSeconds}")
                appendLine("handed_off=${if (record.handedOff) 1 else 0}")
            }
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun read(context: Context): Record? = runCatching {
        val file = stateFile(context)
        if (!file.isFile) return@runCatching null
        val values = file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }
            .toMap()
        if (values["version"]?.toIntOrNull() != VERSION) return@runCatching null
        Record(
            bootId = values.getValue("boot_id"),
            startedAtMillis = values.getValue("started_at_ms").toLong(),
            startedAtUptimeMillis = values.getValue("started_uptime_ms").toLong(),
            appVersion = values.getValue("app_version"),
            profileId = values.getValue("profile"),
            payloadSha256 = values.getValue("payload_sha256"),
            payloadSize = values.getValue("payload_size").toLong(),
            selectedMinUptimeSeconds = values.getValue("min_uptime_sec").toInt(),
            handedOff = values.getValue("handed_off") == "1",
        )
    }.getOrNull()

    fun clear(context: Context) {
        stateFile(context).delete()
        File(stateFile(context).absolutePath + ".bak").delete()
    }

    fun recoverPreviousBootIfNeeded(context: Context, currentBootId: String) {
        val record = read(context) ?: return
        if (record.bootId == currentBootId) return
        recoverRecord(
            context = context,
            record = record,
            currentBootId = currentBootId,
            unexpectedReboot = true,
            reason = "unexpected_reboot",
        )
    }

    fun recoverExecutorDisconnect(context: Context, currentBootId: String, reason: String) {
        val record = read(context) ?: return
        if (record.bootId != currentBootId) {
            recoverPreviousBootIfNeeded(context, currentBootId)
            return
        }
        recoverRecord(
            context = context,
            record = record,
            currentBootId = currentBootId,
            unexpectedReboot = false,
            reason = reason,
        )
    }

    private fun recoverRecord(
        context: Context,
        record: Record,
        currentBootId: String,
        unexpectedReboot: Boolean,
        reason: String,
    ) {
        val historyStore = InstallHistoryStore(context)
        historyStore.recoverInterruptedRuns(currentBootId)
        val alreadyRepresented = historyStore.load().any { entry ->
            entry.bootId == record.bootId && entry.invocationMode == InvocationMode.AutoRoot.wireValue
        }
        if (!alreadyRepresented) {
            val crash = if (unexpectedReboot) PstoreCollector.collect(false) else null
            val result = if (unexpectedReboot) InstallRunResult.UnexpectedReboot else InstallRunResult.Failed
            val entry = historyStore.create(bootId = record.bootId, usedShizuku = false).copy(
                startedAtMillis = record.startedAtMillis,
                completedAtMillis = System.currentTimeMillis(),
                result = result,
                log = buildString {
                    append(Instant.ofEpochMilli(record.startedAtMillis))
                    append(" RMG_AUTOROOT_COORD_GATE_V1|event=start|request_uptime_ms=")
                    append(record.startedAtUptimeMillis)
                    append("|target_uptime_ms=")
                    append(record.selectedMinUptimeSeconds.toLong() * 1_000L)
                    append("|executor=fresh_bound_process|handed_off=")
                    append(if (record.handedOff) 1 else 0)
                    append("\n[-] CZG3 Auto Root gate/executor ended before a normal History entry: ")
                    append(reason)
                },
                profileId = record.profileId,
                usedShizuku = false,
                bootId = record.bootId,
                startedAtUptimeMillis = record.startedAtUptimeMillis,
                appVersion = record.appVersion,
                payloadSha256 = record.payloadSha256,
                payloadSize = record.payloadSize,
                invocationMode = InvocationMode.AutoRoot.wireValue,
                selectedMinUptimeSeconds = record.selectedMinUptimeSeconds,
                unexpectedReboot = unexpectedReboot,
                crashRecordStatus = crash?.status,
                crashRecord = crash?.content,
            )
            historyStore.saveTerminal(entry)
        }
        clear(context)
    }

    private fun stateFile(context: Context) = File(context.filesDir, STATE_PATH)
}
