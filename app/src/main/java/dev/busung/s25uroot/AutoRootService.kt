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
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val AUTO_ROOT_NOTIFICATION_ID = 43499
internal const val AUTO_ROOT_CHANNEL_ID = "auto_root_postboot"

/**
 * Foreground boot gate for Auto Root.
 *
 * BOOT_COMPLETED is the Android-readiness signal. The gate owns the foreground
 * lifecycle and minimum-uptime wait. The exploit still runs in a fresh
 * :autoroot_exec process, but execution now starts only after that process has
 * actually connected and accepted an explicit Messenger command.
 */
class AutoRootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var handoffTimeoutJob: Job? = null
    private var bindingLossJob: Job? = null
    private var executorBound = false
    private var executorConnected = false
    private var shuttingDown = false
    private var pendingBootToken: String? = null

    private val executorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (shuttingDown) return
            if (service == null) {
                failWithoutExecutorResult("executor connected without a binder")
                return
            }

            val bootToken = pendingBootToken
            if (bootToken.isNullOrBlank()) {
                failWithoutExecutorResult("executor handoff lost the boot token")
                return
            }

            try {
                val command = Message.obtain(null, AutoRootExecutorService.MSG_START_AUTO_ROOT).apply {
                    data = Bundle().apply {
                        putString(AutoRootExecutorService.EXTRA_BOOT_TOKEN, bootToken)
                    }
                }
                Messenger(service).send(command)
                executorConnected = true
                handoffTimeoutJob?.cancel()
                handoffTimeoutJob = null
                updateNotification(getString(R.string.autoroot_preparing_exploit))
                Log.i(TAG, "Auto Root executor connected and start command delivered")
            } catch (error: Throwable) {
                failWithoutExecutorResult(
                    "unable to start connected executor: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scheduleBindingLossFailure("executor disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            scheduleBindingLossFailure("executor binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (!shuttingDown) failWithoutExecutorResult("executor returned a null binding")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXECUTOR_RESULT) {
            bindingLossJob?.cancel()
            bindingLossJob = null
            val removeNotification = intent.getBooleanExtra(EXTRA_REMOVE_NOTIFICATION, false)
            val message = intent.getStringExtra(EXTRA_RESULT_MESSAGE)
            if (removeNotification) {
                stopWithoutResult()
            } else {
                finishWithResult(
                    message ?: getString(R.string.autoroot_failed, "executor returned no result"),
                )
            }
            return START_NOT_STICKY
        }

        if (runJob?.isActive == true || executorBound) return START_NOT_STICKY

        val initial = buildNotification(getString(R.string.autoroot_stabilizing_android), ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AUTO_ROOT_NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(AUTO_ROOT_NOTIFICATION_ID, initial)
        }

        runJob = scope.launch { runGate() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = null
        bindingLossJob?.cancel()
        bindingLossJob = null
        if (executorBound) {
            runCatching { unbindService(executorConnection) }
            executorBound = false
        }
        pendingBootToken = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    private suspend fun runGate() {
        val initialBootToken = AutoRootSupport.currentBootToken()
        if (initialBootToken == null || !AutoRootSupport.shouldRunForBoot(this, initialBootToken)) {
            Log.i(TAG, "Auto Root skipped: kernel boot id is unchanged (soft/userspace reboot) or unverifiable")
            stopWithoutResult()
            return
        }

        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AutoRootGate",
        )
        wakeLock.acquire(MAX_GATE_WAKELOCK_MILLIS)
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

            if (isExactCzg3(DeviceSnapshot.current())) {
                DiagnosticUptime.waitUntil(AppPreferences.czg3BootMinUptimeSeconds(this))
            } else {
                delay(LEGACY_STABILIZATION_DELAY_MILLIS)
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

            // Consume the once-per-boot attempt only when execution is actually
            // ready to hand off. A killed gate during the stabilization wait no
            // longer burns the only automatic attempt for the boot.
            require(AutoRootSupport.claimAttempt(this, bootToken)) {
                getString(R.string.autoroot_already_attempted)
            }

            pendingBootToken = bootToken
            val executorIntent = Intent(this, AutoRootExecutorService::class.java)
                .setAction(AutoRootExecutorService.ACTION_RUN_AUTO_ROOT)
            val bindFlags = Context.BIND_AUTO_CREATE or
                Context.BIND_IMPORTANT or
                Context.BIND_ABOVE_CLIENT
            require(bindService(executorIntent, executorConnection, bindFlags)) {
                "Unable to bind fresh Auto Root executor"
            }
            executorBound = true

            // bindService(true) only means the bind request was accepted. It does
            // not prove that :autoroot_exec exists or onServiceConnected ran.
            handoffTimeoutJob = scope.launch {
                delay(EXECUTOR_CONNECT_TIMEOUT_MILLIS)
                if (!executorConnected && !shuttingDown) {
                    failWithoutExecutorResult(
                        "fresh Auto Root executor did not connect within ${EXECUTOR_CONNECT_TIMEOUT_MILLIS / 1_000}s",
                    )
                }
            }
        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Auto Root gate failed", error)
            recordGateFailure(detail, initialBootToken)
            finishWithResult(getString(R.string.autoroot_failed, detail))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun scheduleBindingLossFailure(detail: String) {
        if (shuttingDown) return
        bindingLossJob?.cancel()
        bindingLossJob = scope.launch {
            // A normally finishing bound service can disconnect immediately before
            // its terminal result intent is delivered. Give that result a brief
            // ordering window before classifying the executor as lost.
            delay(EXECUTOR_RESULT_GRACE_MILLIS)
            if (!shuttingDown) failWithoutExecutorResult(detail)
        }
    }

    private fun recordGateFailure(detail: String, bootToken: String? = AutoRootSupport.currentBootToken()) {
        runCatching {
            val now = System.currentTimeMillis()
            val log = buildString {
                append("[*] Auto Root gate\n")
                bootToken?.let { append("[*] boot_id=$it\n") }
                append("[-] $detail")
            }
            InstallHistoryStore(this).save(
                InstallHistoryEntry(
                    id = UUID.randomUUID().toString(),
                    startedAtMillis = now,
                    completedAtMillis = now,
                    result = InstallRunResult.Failed,
                    log = log,
                    profileId = null,
                    usedShizuku = false,
                ),
            )
        }
    }

    private fun failWithoutExecutorResult(detail: String) {
        if (shuttingDown) return
        Log.e(TAG, "Auto Root executor handoff failed: $detail")
        recordGateFailure(detail)
        finishWithResult(getString(R.string.autoroot_failed, detail))
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = true),
        )
    }

    private fun finishWithResult(message: String) {
        if (shuttingDown) return
        shuttingDown = true
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = false),
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopWithoutResult() {
        if (shuttingDown) return
        shuttingDown = true
        getSystemService(NotificationManager::class.java).cancel(AUTO_ROOT_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(message: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, AUTO_ROOT_CHANNEL_ID)
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
                AUTO_ROOT_CHANNEL_ID,
                getString(R.string.autoroot_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.autoroot_channel_description)
            },
        )
    }

    companion object {
        const val ACTION_EXECUTOR_RESULT = "dev.busung.s25uroot.action.AUTO_ROOT_EXECUTOR_RESULT"
        const val EXTRA_RESULT_MESSAGE = "executor_result_message"
        const val EXTRA_REMOVE_NOTIFICATION = "executor_remove_notification"

        private const val TAG = "RootMyGalaxyAutoRootGate"
        private const val LEGACY_STABILIZATION_DELAY_MILLIS = 45_000L
        private const val EXECUTOR_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val EXECUTOR_RESULT_GRACE_MILLIS = 1_500L
        private const val MAX_GATE_WAKELOCK_MILLIS = 10 * 60 * 1_000L
    }
}
