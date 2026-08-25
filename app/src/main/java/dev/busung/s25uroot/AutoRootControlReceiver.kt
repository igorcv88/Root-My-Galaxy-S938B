package dev.busung.s25uroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRootControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISABLE) return
        AppPreferences.setAutoRootEnabled(context, false)
        context.stopService(Intent(context, AutoRootService::class.java))
        AutoRootService.cancelNotifications(context)
    }

    companion object {
        const val ACTION_DISABLE = "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"
    }
}
