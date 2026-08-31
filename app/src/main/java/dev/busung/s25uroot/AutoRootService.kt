package dev.busung.s25uroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val AUTO_ROOT_NOTIFICATION_ID = 43499

class AutoRootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var watchdogExpired = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (runJob?.isActive == true) return START_NOT_STICKY

        val initial = buildNotification(getString(R.string.autoroot_waiting_android), ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AUTO_ROOT_NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(AUTO_ROOT_NOTIFICATION_ID, initial)
        }

        watchdogExpired = false
        runJob = scope.launch {
            try {
                runAutoRoot()
            } finally {
                if (!watchdogExpired) watchdogJob?.cancel()
                watchdogJob = null
            }
        }
        watchdogJob = scope.launch {
            delay(AUTO_ROOT_SERVICE_WATCHDOG_MILLIS)
            if (runJob?.isActive == true) {
                watchdogExpired = true
                Log.e(TAG, "Auto Root service watchdog expired")
                runJob?.cancel(CancellationException("Auto Root service watchdog expired"))
                finishWithResult(
                    getString(
                        R.string.autoroot_failed,
                        getString(R.string.autoroot_service_watchdog_timeout),
                    ),
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchdogJob?.cancel()
        watchdogJob = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAutoRoot() {
        val historyStore = InstallHistoryStore(this)
        historyStore.closeInterruptedRuns()
        var historyEntry: InstallHistoryEntry? = null
        var lastHistoryWriteAt = 0L
        fun saveHistory(force: Boolean = false) {
            val entry = historyEntry ?: return
            val now = SystemClock.elapsedRealtime()
            if (force || now - lastHistoryWriteAt >= HISTORY_CHECKPOINT_MILLIS) {
                historyStore.save(entry)
                lastHistoryWriteAt = now
            }
        }
        fun persist(line: String, force: Boolean = false) {
            Log.i(TAG, line)
            val entry = historyEntry ?: return
            val timestamped = "${Instant.now()} $line"
            val updated = entry.copy(log = (entry.log + "\n" + timestamped).trim())
            historyEntry = updated
            saveHistory(force)
        }
        fun finishHistory(result: InstallRunResult) {
            val entry = historyEntry ?: return
            entry.copy(
                completedAtMillis = System.currentTimeMillis(),
                result = result,
            ).also(historyStore::save)
            historyEntry = null
        }
        val initialBootToken = AutoRootSupport.currentBootToken()
        if (initialBootToken == null || !AutoRootSupport.shouldRunForBoot(this, initialBootToken)) {
            Log.i(TAG, "Auto Root skipped: kernel boot id is unchanged (soft/userspace reboot) or unverifiable")
            stopWithoutResult()
            return
        }

        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AutoRoot",
        )
        wakeLock.acquire(MAX_WAKELOCK_MILLIS)
        try {
            if (!AppPreferences.autoRootEnabled(this)) {
                stopWithoutResult()
                return
            }
            require(AutoRootSupport.hasVerifiedInstall(this)) {
                getString(R.string.autoroot_prior_install_required)
            }

            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, initialBootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            require(waitForAndroidReady()) { getString(R.string.autoroot_boot_timeout) }
            updateNotification(getString(R.string.autoroot_stabilizing_android))
            if (waitForStabilizationOrRoot(initialBootToken)) {
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            if (!AppPreferences.autoRootEnabled(this)) {
                stopWithoutResult()
                return
            }

            val bootToken = AutoRootSupport.currentBootToken()
                ?: error(getString(R.string.error_boot_id))
            require(bootToken == initialBootToken) { getString(R.string.autoroot_boot_changed) }

            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            updateNotification(getString(R.string.autoroot_checking_firmware))
            val payloads = AutoRootSupport.loadVerifiedLocalPayloads(this)

            // Upstream #483 avoids Shizuku at boot entirely. Preserve the faster
            // Shizuku path when it is actually usable, but verify more than Binder
            // presence before spending the single automatic attempt for this boot.
            val shizukuCandidate = ShizukuController.isRunning() && ShizukuController.isGranted()
            val useShizuku = shizukuCandidate && ShizukuController.canRunUnattended()

            require(AutoRootSupport.claimAttempt(this, bootToken)) {
                getString(R.string.autoroot_already_attempted)
            }

            historyEntry = historyStore.create().copy(
                profileId = payloads.profile.profileId,
                usedShizuku = useShizuku,
                payloadSha256 = payloads.profile.exploit.sha256,
                payloadSize = payloads.profile.exploit.size,
            ).also(historyStore::save)
            if (shizukuCandidate && !useShizuku) {
                persist("[*] Shizuku available but unattended health probe failed; falling back to standalone")
            }
            persist("[*] Auto Root starting transport=${if (useShizuku) "shizuku" else "standalone"}")
            val runner = AutoRootRunner(
                context = this,
                useShizuku = useShizuku,
                onStage = { stage ->
                    persist("[*] stage=$stage")
                    val message = when (stage) {
                        AutoRootStage.PreparingExploit -> R.string.autoroot_preparing_exploit
                        AutoRootStage.RunningExploit -> R.string.autoroot_running_exploit
                        AutoRootStage.LoadingKernelSu -> R.string.autoroot_loading_ksu
                        AutoRootStage.VerifyingRoot -> R.string.autoroot_verifying_root
                    }
                    updateNotification(getString(message))
                },
                onLog = { line -> persist(line) },
                onDiagnostic = { diagnostic ->
                    historyEntry?.let { entry ->
                        val timing = StageTiming(diagnostic.stage, diagnostic.elapsedMillis, diagnostic.attempt)
                        historyEntry = entry.copy(
                            stage = diagnostic.stage,
                            attemptCount = maxOf(entry.attemptCount, diagnostic.attempt ?: 0),
                            exploitElapsedMillis = diagnostic.elapsedMillis,
                            failureClass = diagnostic.failureClass,
                            safety = diagnostic.safety,
                            outcome = diagnostic.outcome,
                            stageTimings = if (entry.stageTimings.lastOrNull() == timing) entry.stageTimings else entry.stageTimings + timing,
                        )
                        saveHistory(diagnostic.outcome != null)
                        updateNotification(diagnostic.stage.userLabel(diagnostic.attempt, diagnostic.elapsedMillis))
                    }
                },
            )
            runner.run(payloads, bootToken, requireNotNull(historyEntry).id)

            AutoRootSupport.markVerifiedForBoot(this, bootToken)
            persist("[+] Auto Root completed")
            val softReboot = AppPreferences.softRebootAfterRoot(this)
            if (softReboot) persist("[*] KernelSU soft reboot requested", force = true)
            finishHistory(InstallRunResult.Succeeded)

            if (softReboot) {
                updateNotification(getString(R.string.soft_reboot_starting))
                val result = KernelSuSoftReboot.request(this)
                if (result.started) {
                    Log.i(TAG, "KernelSU soft reboot started")
                    stopWithoutResult()
                    return
                }
                Log.w(TAG, "KernelSU soft reboot was not started: ${result.detail}")
            }
            finishWithResult(getString(R.string.autoroot_root_restored))
        } catch (error: CancellationException) {
            persist("[-] Auto Root cancelled: ${error.message ?: "cancelled"}", force = true)
            finishHistory(InstallRunResult.Failed)
            throw error
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            if (error is ExploitRunException) {
                historyEntry = historyEntry?.copy(failureClass = error.failureClass, safety = error.safety, outcome = ExploitOutcome.Failed)
            }
            Log.e(TAG, "Auto Root failed", error)
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            persist("[-] Auto Root failed\n$trace", force = true)
            finishHistory(InstallRunResult.Failed)
            finishWithResult(getString(R.string.autoroot_failed, detail.take(160)))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun waitForAndroidReady(): Boolean =
        withTimeoutOrNull(BOOT_COMPLETED_TIMEOUT_MILLIS) {
            while (scope.isActive && readBootCompletedProperty() != "1") {
                delay(BOOT_PROPERTY_POLL_MILLIS)
            }
            scope.isActive
        } == true

    /**
     * Keep the existing conservative stabilization window, but stop waiting if
     * KernelSU becomes active during it. This preserves the safer timing while
     * avoiding up to 45 seconds of pointless latency when root was restored by
     * another mechanism after BOOT_COMPLETED.
     */
    private suspend fun waitForStabilizationOrRoot(bootToken: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STABILIZATION_DELAY_MILLIS
        while (scope.isActive && SystemClock.elapsedRealtime() < deadline) {
            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                return true
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) delay(minOf(STABILIZATION_POLL_MILLIS, remaining))
        }
        return false
    }

    private fun readBootCompletedProperty(): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", "sys.boot_completed")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output
    }.getOrDefault("")

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = true),
        )
    }

    private fun finishWithResult(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = false),
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopWithoutResult() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(message: String, ongoing: Boolean) =
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
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
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
        private const val TAG = "RootMyGalaxyAutoRoot"
        private const val CHANNEL_ID = "auto_root_postboot"
        private const val STABILIZATION_DELAY_MILLIS = 45_000L
        private const val STABILIZATION_POLL_MILLIS = 5_000L
        private const val BOOT_COMPLETED_TIMEOUT_MILLIS = 120_000L
        private const val BOOT_PROPERTY_POLL_MILLIS = 1_000L
        private const val AUTO_ROOT_SERVICE_WATCHDOG_MILLIS = 25 * 60 * 1_000L
        private const val MAX_WAKELOCK_MILLIS = AUTO_ROOT_SERVICE_WATCHDOG_MILLIS
        private const val HISTORY_CHECKPOINT_MILLIS = 2_000L
    }
}
