package dev.busung.s25uroot

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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
 * BIND_IMPORTANT | BIND_ABOVE_CLIENT. Auto Root is always offline and always
 * standalone. AutoRootRunner boosts only the child spawn window and lowers the
 * parent polling thread during the race.
 */
class AutoRootExecutorService : Service() {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "RootMyGalaxy-AutoRootExec")
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var runJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != ACTION_RUN_AUTO_ROOT) return null
        if (runJob?.isActive != true) {
            val bootToken = intent.getStringExtra(EXTRA_BOOT_TOKEN)
            runJob = scope.launch { runAutoRoot(bootToken) }
        }
        return Binder()
    }

    override fun onDestroy() {
        scope.cancel()
        dispatcher.close()
        super.onDestroy()
    }

    private suspend fun runAutoRoot(expectedBootToken: String?) {
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
            // History must never change the root outcome. Constructing the store
            // and writing its AtomicFile are both deferred until the exploit is over.
            runCatching { InstallHistoryStore(this).save(updated) }
        }

        try {
            require(AppPreferences.autoRootEnabled(this)) { "Auto Root was disabled before execution" }

            val bootToken = AutoRootSupport.currentBootToken()
                ?: error(getString(R.string.error_boot_id))
            require(expectedBootToken == null || bootToken == expectedBootToken) {
                getString(R.string.autoroot_boot_changed)
            }

            if (NativeProbe.isKernelSuActive()) {
                AutoRootSupport.markVerifiedForBoot(this, bootToken)
                finishWithResult(getString(R.string.autoroot_root_restored))
                return
            }

            // Create only an in-memory record before any payload work. This makes
            // preflight/cache failures visible in History without touching disk.
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

            require(AutoRootSupport.claimAttempt(this, bootToken)) {
                getString(R.string.autoroot_already_attempted)
            }

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
                    val message = getString(messageRes)
                    if (stage == AutoRootStage.LoadingKernelSu || stage == AutoRootStage.VerifyingRoot) {
                        updateNotification(message)
                    }
                    appendHistory("[*] $message")
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
            runner.run(payloads)

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
                val message = getString(R.string.soft_reboot_failed, reboot.detail.take(160))
                Log.w(TAG, message)
                appendHistory("[-] $message")
                finishHistory(InstallRunResult.Succeeded)
                finishWithResult(message)
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

    private fun finishWithResult(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            AUTO_ROOT_NOTIFICATION_ID,
            buildNotification(message, ongoing = false),
        )
        stopGateAndSelf(removeNotification = false)
    }

    private fun stopGateAndSelf(removeNotification: Boolean) {
        if (removeNotification) {
            getSystemService(NotificationManager::class.java).cancel(AUTO_ROOT_NOTIFICATION_ID)
        }
        stopService(Intent(this, AutoRootService::class.java))
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

    companion object {
        const val ACTION_RUN_AUTO_ROOT = "dev.busung.s25uroot.action.RUN_FRESH_AUTO_ROOT"
        const val EXTRA_BOOT_TOKEN = "boot_token"

        private const val TAG = "RootMyGalaxyAutoRootExec"
        private const val MAX_EXECUTOR_WAKELOCK_MILLIS = 20 * 60 * 1_000L
    }
}
