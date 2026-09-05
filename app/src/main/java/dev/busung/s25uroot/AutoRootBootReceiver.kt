package dev.busung.s25uroot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPreferences.autoRootEnabled(context)) return
        if (!AutoRootSupport.hasVerifiedInstall(context)) return

        val bootToken = AutoRootSupport.currentBootToken() ?: return
        if (!AutoRootSupport.shouldRunForBoot(context, bootToken)) return

        context.startForegroundService(Intent(context, AutoRootService::class.java))
    }
}

class AutoRootActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISABLE_AUTO_ROOT) return
        AppPreferences.setAutoRootEnabled(context, false)
        context.stopService(Intent(context, AutoRootExecutorService::class.java))
        context.stopService(Intent(context, AutoRootService::class.java))
        context.getSystemService(NotificationManager::class.java)
            .cancel(AUTO_ROOT_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_DISABLE_AUTO_ROOT =
            "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"
    }
}
