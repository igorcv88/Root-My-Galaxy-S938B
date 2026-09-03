package dev.busung.s25uroot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AndroidRunContext.recordBootReceiver(context)
        if (!AppPreferences.autoRootEnabled(context)) return
        if (!AutoRootSupport.hasVerifiedInstall(context)) return

        val bootToken = AutoRootSupport.currentBootToken() ?: return
        if (!AutoRootSupport.shouldRunForBoot(context, bootToken)) return

        // Mirror the upstream boot preflight, but keep the stronger local boot-token
        // gate. If another mechanism already restored KernelSU, do not start a
        // foreground service just to discover the same thing again.
        if (NativeProbe.isKernelSuActive()) {
            AutoRootSupport.markVerifiedForBoot(context, bootToken)
            return
        }

        // On the exact CZG3 target, keep the boot-time wait in a lightweight gate
        // service and start the actual Auto Root executor only when the selected
        // minimum uptime has arrived. The executor runs in a separate process, so
        // the helper is spawned from a fresh foreground process instead of
        // inheriting the long-lived boot service's demoted cgroup/uclamp state.
        val service = if (isExactCzg3DiagnosticTarget(DeviceSnapshot.current())) {
            Czg3AutoRootGateService::class.java
        } else {
            AutoRootService::class.java
        }
        context.startForegroundService(Intent(context, service))
    }
}

class AutoRootActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISABLE_AUTO_ROOT) return
        AppPreferences.setAutoRootEnabled(context, false)
        context.stopService(Intent(context, Czg3AutoRootGateService::class.java))
        context.stopService(Intent(context, AutoRootService::class.java))
        context.getSystemService(NotificationManager::class.java)
            .cancel(AUTO_ROOT_NOTIFICATION_ID)
        context.getSystemService(NotificationManager::class.java)
            .cancel(CZG3_AUTO_ROOT_GATE_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_DISABLE_AUTO_ROOT =
            "dev.busung.s25uroot.action.DISABLE_AUTO_ROOT"
    }
}
