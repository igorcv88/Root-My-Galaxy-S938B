package dev.busung.s25uroot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class AutoRootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifications: NotificationManager
    @Volatile private var started = false

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        started = true
        startForegroundCompat(progressNotification(getString(R.string.auto_root_waiting_android), true))
        scope.launch { executeAutoRoot() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun executeAutoRoot() {
        val log = StringBuilder()
        try {
            require(
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ) { getString(R.string.auto_root_notification_required) }

            updateProgress(getString(R.string.auto_root_waiting_android))
            require(waitForBootComplete()) { getString(R.string.auto_root_boot_timeout) }
            delay(STABILIZATION_MILLIS)

            if (!AppPreferences.autoRootEnabled(this)) {
                finishSilently()
                return
            }
            if (NativeProbe.isKernelSuActive()) {
                finishSuccess(getString(R.string.auto_root_already_active))
                return
            }

            val bootId = currentBootId() ?: error(getString(R.string.error_boot_id))
            if (!AppPreferences.claimAutoRootAttempt(this, bootId)) {
                finishSilently()
                return
            }

            val runner = AutoRootRunner(
                context = this,
                onStage = { stage -> updateProgress(stageText(stage)) },
                onLog = { line ->
                    if (log.isNotEmpty()) log.append('\n')
                    log.append(line)
                    writeLastLog(log.toString())
                },
            )
            runner.run()
            finishSuccess(getString(R.string.auto_root_root_restored))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            if (log.isNotEmpty()) log.append('\n')
            log.append("[-] ").append(message)
            writeLastLog(log.toString())
            finishFailure(message)
        }
    }

    private suspend fun waitForBootComplete(): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < BOOT_WAIT_TIMEOUT_MILLIS) {
            if (readSystemProperty("sys.boot_completed") == "1") return true
            delay(BOOT_POLL_MILLIS)
        }
        return false
    }

    private fun readSystemProperty(name: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun stageText(stage: AutoRootStage): String = when (stage) {
        AutoRootStage.CheckingFirmware -> getString(R.string.auto_root_checking_firmware)
        AutoRootStage.PreparingExploit -> getString(R.string.auto_root_preparing_exploit)
        AutoRootStage.RunningExploit -> getString(R.string.auto_root_running_exploit)
        AutoRootStage.LoadingKernelSu -> getString(R.string.auto_root_loading_kernelsu)
        AutoRootStage.VerifyingRoot -> getString(R.string.auto_root_verifying_root)
        AutoRootStage.RootRestored -> getString(R.string.auto_root_root_restored)
    }

    private fun updateProgress(text: String) {
        notifications.notify(PROGRESS_NOTIFICATION_ID, progressNotification(text, true))
    }

    private fun finishSuccess(text: String) {
        notifications.notify(PROGRESS_NOTIFICATION_ID, progressNotification(text, false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun finishFailure(message: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.notify(
            RESULT_NOTIFICATION_ID,
            resultNotification(
                title = getString(R.string.auto_root_failed),
                message = message,
            ),
        )
        stopSelf()
    }

    private fun finishSilently() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progressNotification(text: String, ongoing: Boolean): Notification =
        Notification.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.auto_root_notification_title))
            .setContentText(text)
            .setContentIntent(contentPendingIntent())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .addAction(disableAction())
            .build()

    private fun resultNotification(title: String, message: String): Notification =
        Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(contentPendingIntent())
            .setAutoCancel(true)
            .addAction(disableAction())
            .build()

    private fun disableAction(): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
        getString(R.string.auto_root_disable),
        PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, AutoRootControlReceiver::class.java).setAction(AutoRootControlReceiver.ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                getString(R.string.auto_root_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.auto_root_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun writeLastLog(content: String) {
        runCatching {
            val directory = File(filesDir, "auto-root").apply { mkdirs() }
            File(directory, "last.log").writeText(content)
        }
    }

    companion object {
        const val PROGRESS_NOTIFICATION_ID = 43499
        const val RESULT_NOTIFICATION_ID = 43500
        private const val PROGRESS_CHANNEL_ID = "auto_root_progress"
        private const val ALERT_CHANNEL_ID = "auto_root_alerts"
        private const val BOOT_POLL_MILLIS = 2_000L
        private const val BOOT_WAIT_TIMEOUT_MILLIS = 5 * 60_000L
        private const val STABILIZATION_MILLIS = 45_000L
    }
}
