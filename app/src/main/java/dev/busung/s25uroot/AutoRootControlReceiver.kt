package dev.busung.s25uroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRootControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISABLE) return
        // Do not tear down a kernel exploit that may already be running. The
        // service re-checks this preference before claiming the boot attempt;
        // once the exploit has started, disabling applies to future boots.
        AppPreferences.setAutoRootEnabled(context, false)
    }

    companion object {
        const val ACTION_DISABLE = "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"
    }
}
