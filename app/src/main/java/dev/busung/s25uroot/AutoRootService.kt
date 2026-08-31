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
import android.util.Log
import androidx.core.app.NotificationCompat
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

        runJob = scope.launch { runAutoRoot() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAutoRoot() {
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

            val initialBootToken = AutoRootSupport.currentBootToken()
                ?: error(getString(R.string.error_boot_id))
            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, initialBootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            require(waitForAndroidReady()) { getString(R.string.autoroot_boot_timeout) }
            updateNotification(getString(R.string.autoroot_stabilizing_android))
            delay(STABILIZATION_DELAY_MILLIS)

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

            require(AutoRootSupport.claimAttempt(this, bootToken)) {
                getString(R.string.autoroot_already_attempted)
            }

            val useShizuku = ShizukuController.isRunning() && ShizukuController.isGranted()
            Log.i(TAG, "Auto Root starting with ${if (useShizuku) "Shizuku" else "standalone"} transport")
            val runner = AutoRootRunner(
                context = this,
                useShizuku = useShizuku,
                onStage = { stage ->
                    val message = when (stage) {
                        AutoRootStage.PreparingExploit -> R.string.autoroot_preparing_exploit
                        AutoRootStage.RunningExploit -> R.string.autoroot_running_exploit
                        AutoRootStage.LoadingKernelSu -> R.string.autoroot_loading_ksu
                        AutoRootStage.VerifyingRoot -> R.string.autoroot_verifying_root
                    }
                    updateNotification(getString(message))
                },
                onLog = { line -> Log.i(TAG, line) },
            )
            runner.run(payloads, bootToken)

            AutoRootSupport.markVerifiedForBoot(this, bootToken)
            finishWithResult(getString(R.string.autoroot_root_restored))
        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Auto Root failed", error)
            finishWithResult(getString(R.string.autoroot_failed, detail))
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
        private const val BOOT_COMPLETED_TIMEOUT_MILLIS = 120_000L
        private const val BOOT_PROPERTY_POLL_MILLIS = 1_000L
        private const val MAX_WAKELOCK_MILLIS = 20 * 60 * 1_000L
    }
}
