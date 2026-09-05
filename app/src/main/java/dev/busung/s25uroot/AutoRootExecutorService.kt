package dev.busung.s25uroot

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fresh process used only for the critical Auto Root execution window.
 *
 * The foreground gate binds this service at the configured launch uptime with
 * BIND_IMPORTANT | BIND_ABOVE_CLIENT. Unlike the previous implementation,
 * onBind() has no execution side effect: the gate must connect first and send an
 * explicit MSG_START_AUTO_ROOT command. That makes a failed process handoff
 * distinguishable from an exploit failure.
 */
class AutoRootExecutorService : Service() {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "RootMyGalaxy-AutoRootExec")
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var runJob: Job? = null

    private val commandHandler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_START_AUTO_ROOT -> {
                handleStartCommand(message)
                true
            }
            else -> false
        }
    }
    private val commandMessenger = Messenger(commandHandler)

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != ACTION_RUN_AUTO_ROOT) return null
        Log.i(TAG, "Fresh Auto Root executor bound; awaiting explicit start command")
        return commandMessenger.binder
    }

    override fun onDestroy() {
        scope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    private fun handleStartCommand(message: Message) {
        if (runJob != null) {
            Log.w(TAG, "Ignoring duplicate Auto Root executor start command")
            return
        }

        val bootToken = message.data.getString(EXTRA_BOOT_TOKEN)
        if (bootToken.isNullOrBlank()) {
            finishWithResult(getString(R.string.autoroot_failed, "executor received no boot token"))
            return
        }

        // Publish a visible proof of handoff before the worker thread starts. If
        // the next transition never appears, we know the failure is inside this
        // fresh executor rather than in the boot gate/binding path.
        updateNotification(getString(R.string.autoroot_preparing_exploit))
        Log.i(TAG, "Auto Root executor start command accepted")
        runJob = scope.launch { runAutoRoot(bootToken) }
    }

    private suspend fun runAutoRoot(expectedBootToken: String) {
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AutoRootExecutor",
        )
        wakeLock.acquire(MAX_EXECUTOR_WAKELOCK_MILLIS)

        var historyEntry: InstallHistoryEntry? = null

        // No History disk writes while the exploit is active. We keep the runner's
        // cumulative log in memory and persist once after normal success/failure.
        fun appendHistory(line: String?) {
            val current = historyEntry ?: return
            val clean = line?.trim().orEmpty()
            if (clean.isBlank()) return
            historyEntry = current.copy(log = (current.log + "\n" + clean).trim())
        }

        fun finishHistory(result: InstallRunResult) {
            val current = historyEntry ?: return
            val updated = current.copy(
                completedAtMillis = System.currentTimeMillis(),
                result = result,
            )
            historyEntry = updated
            runCatching { InstallHistoryStore(this).save(updated) }
        }

        try {
            require(AppPreferences.autoRootEnabled(this)) { "Auto Root was disabled before execution" }

            val bootToken = AutoRootSupport.currentBootToken()
                ?: error(getString(R.string.error_boot_id))
            require(bootToken == expectedBootToken) {
                getString(R.string.autoroot_boot_changed)
            }

            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            historyEntry = InstallHistoryEntry(
                id = UUID.randomUUID().toString(),
                startedAtMillis = System.currentTimeMillis(),
                completedAtMillis = null,
                result = InstallRunResult.Running,
                profileId = null,
                usedShizuku = false,
                log = "[*] Auto Root started: fresh executor, offline cache, standalone transport",
            )

            val payloads = AutoRootSupport.loadVerifiedLocalPayloads(this)
            require(payloads.source == PayloadSource.Offline) {
                "Auto Root requires the last-known-good offline payload"
            }
            val currentHistory = historyEntry ?: error("Auto Root history state missing")
            historyEntry = currentHistory.copy(
                profileId = payloads.profile.profileId,
                log = currentHistory.log + "\n[*] profile=${payloads.profile.profileId}",
            )

            var lastRunnerSnapshot = ""
            val runner = AutoRootRunner(
                context = this,
                onStage = { stage ->
                    val messageRes = when (stage) {
                        AutoRootStage.PreparingExploit -> R.string.autoroot_preparing_exploit
                        AutoRootStage.RunningExploit -> R.string.autoroot_running_exploit
                        AutoRootStage.LoadingKernelSu -> R.string.autoroot_loading_ksu
                        AutoRootStage.VerifyingRoot -> R.string.autoroot_verifying_root
                    }
                    val stageMessage = getString(messageRes)
                    // The old split-executor implementation intentionally skipped
                    // Preparing/Running notifications, which made a healthy handoff
                    // look exactly like a dead executor. Every coarse stage is now
                    // visible; there is still no notification work inside the race.
                    updateNotification(stageMessage)
                    appendHistory("[*] $stageMessage")
                },
                onLog = { snapshot ->
                    val delta = if (
                        lastRunnerSnapshot.isNotEmpty() &&
                        snapshot.startsWith(lastRunnerSnapshot)
                    ) {
                        snapshot.substring(lastRunnerSnapshot.length).trimStart('\n', '\r')
                    } else {
                        snapshot
                    }
                    lastRunnerSnapshot = snapshot
                    appendHistory(delta)
                },
            )
            runner.run(payloads, bootToken)

            AutoRootSupport.markVerifiedForBoot(this, bootToken)
            appendHistory("[+] Auto Root completed")
            finishHistory(InstallRunResult.Succeeded)

            if (AppPreferences.softRebootAfterRoot(this)) {
                updateNotification(getString(R.string.soft_reboot_starting))
                val reboot = KernelSuSoftReboot.request(this)
                if (reboot.started) {
                    Log.i(TAG, "KernelSU soft reboot started")
                    stopGateAndSelf(removeNotification = true)
                    return
                }
                val failureMessage = getString(R.string.soft_reboot_failed, reboot.detail.take(160))
                Log.w(TAG, failureMessage)
                appendHistory("[-] $failureMessage")
                finishHistory(InstallRunResult.Succeeded)
                finishWithResult(failureMessage)
                return
            }

            finishWithResult(getString(R.string.autoroot_root_restored))
        } catch (error: Throwable) {
            if (!scope.isActive) return
            val detail = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Auto Root executor failed", error)
            if (historyEntry != null) {
                appendHistory("[-] $detail")
                finishHistory(InstallRunResult.Failed)
            }
            finishWithResult(getString(R.string.autoroot_failed, detail))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = true),
        )
    }

    /**
     * Let the already-running foreground gate own the terminal notification and
     * FGS teardown. This avoids replacing the gate's foreground notification in
     * one process and immediately destroying its owner from another process.
     */
    private fun finishWithResult(message: String) {
        val delivered = deliverGateResult(message, removeNotification = false)
        if (!delivered) {
            getSystemService(NotificationManager::class.java).notify(
                AUTO_ROOT_NOTIFICATION_ID,
                buildNotification(message, ongoing = false),
            )
            stopService(Intent(this, AutoRootService::class.java))
        }
        stopSelf()
    }

    private fun stopGateAndSelf(removeNotification: Boolean) {
        val delivered = deliverGateResult(message = null, removeNotification = removeNotification)
        if (!delivered) {
            if (removeNotification) {
                getSystemService(NotificationManager::class.java).cancel(AUTO_ROOT_NOTIFICATION_ID)
            }
            stopService(Intent(this, AutoRootService::class.java))
        }
        stopSelf()
    }

    private fun deliverGateResult(message: String?, removeNotification: Boolean): Boolean =
        runCatching {
            startService(
                Intent(this, AutoRootService::class.java)
                    .setAction(AutoRootService.ACTION_EXECUTOR_RESULT)
                    .putExtra(AutoRootService.EXTRA_RESULT_MESSAGE, message)
                    .putExtra(AutoRootService.EXTRA_REMOVE_NOTIFICATION, removeNotification),
            ) != null
        }.onFailure { error ->
            Log.e(TAG, "Unable to deliver executor result to foreground gate", error)
        }.getOrDefault(false)

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

    companion object {
        const val ACTION_RUN_AUTO_ROOT = "dev.busung.s25uroot.action.RUN_FRESH_AUTO_ROOT"
        const val EXTRA_BOOT_TOKEN = "boot_token"
        const val MSG_START_AUTO_ROOT = 1

        private const val TAG = "RootMyGalaxyAutoRootExec"
        private const val MAX_EXECUTOR_WAKELOCK_MILLIS = 20 * 60 * 1_000L
    }
}
