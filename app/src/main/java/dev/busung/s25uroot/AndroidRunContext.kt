package dev.busung.s25uroot

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager

internal object AndroidRunContext {
    private const val PREFERENCES = "race_diagnostic_context"
    private const val BOOT_RECEIVER_UPTIME = "boot_receiver_uptime_ms"

    fun recordBootReceiver(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong(BOOT_RECEIVER_UPTIME, SystemClock.elapsedRealtime()).apply()
    }

    fun snapshot(context: Context, event: String, observedUptimeMillis: Long = SystemClock.elapsedRealtime()): String {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val user = context.getSystemService(UserManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun batteryInt(name: String) = battery?.getIntExtra(name, -1) ?: -1
        val bootObserved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(BOOT_RECEIVER_UPTIME, -1)
        return "RMG_ANDROID_V1|event=$event|uptime_ms=$observedUptimeMillis" +
            "|boot_receiver_uptime_ms=$bootObserved|user_unlocked=${user.isUserUnlocked}" +
            "|interactive=${power.isInteractive}|keyguard_locked=${keyguard.isKeyguardLocked}" +
            "|thermal_status=${power.currentThermalStatus}" +
            "|battery_level=${batteryInt(BatteryManager.EXTRA_LEVEL)}" +
            "|battery_scale=${batteryInt(BatteryManager.EXTRA_SCALE)}" +
            "|battery_status=${batteryInt(BatteryManager.EXTRA_STATUS)}" +
            "|battery_plugged=${batteryInt(BatteryManager.EXTRA_PLUGGED)}" +
            "|battery_temp_tenths_c=${batteryInt(BatteryManager.EXTRA_TEMPERATURE)}"
    }
}
