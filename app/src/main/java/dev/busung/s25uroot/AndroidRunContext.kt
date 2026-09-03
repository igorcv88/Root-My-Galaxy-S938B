package dev.busung.s25uroot

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import java.io.File

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
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun batteryInt(name: String) = battery?.getIntExtra(name, -1) ?: -1
        val bootObserved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(BOOT_RECEIVER_UPTIME, -1)
        val processState = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val cgroup = readProc("/proc/self/cgroup")
        val status = readProc("/proc/self/status")
        val sched = readProc("/proc/self/sched")
        val cpusAllowed = status.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim().orEmpty().ifBlank { "unknown" }
        fun schedValue(name: String): String = sched.lineSequence()
            .firstOrNull { it.trimStart().startsWith(name) }
            ?.substringAfter(':')?.trim().orEmpty().ifBlank { "unknown" }
        val threadNice = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrDefault(Int.MIN_VALUE)

        // Sample connectivity only when an existing diagnostic marker is emitted.
        // Do not add polling around the race: this is passive provenance for the
        // online/offline correlation without adding critical-window activity.
        val airplaneMode = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrNull()
        val activeNetwork = runCatching { connectivity.activeNetwork }.getOrNull()
        val networkCapabilities = activeNetwork?.let { network ->
            runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
        }
        val networkMetered = if (activeNetwork == null) null else runCatching {
            connectivity.isActiveNetworkMetered
        }.getOrNull()

        return "RMG_ANDROID_V1|event=$event|uptime_ms=$observedUptimeMillis" +
            "|boot_receiver_uptime_ms=$bootObserved|user_unlocked=${user.isUserUnlocked}" +
            "|interactive=${power.isInteractive}|keyguard_locked=${keyguard.isKeyguardLocked}" +
            "|thermal_status=${power.currentThermalStatus}" +
            "|power_save_mode=${power.isPowerSaveMode}|device_idle_mode=${power.isDeviceIdleMode}" +
            "|battery_level=${batteryInt(BatteryManager.EXTRA_LEVEL)}" +
            "|battery_scale=${batteryInt(BatteryManager.EXTRA_SCALE)}" +
            "|battery_status=${batteryInt(BatteryManager.EXTRA_STATUS)}" +
            "|battery_plugged=${batteryInt(BatteryManager.EXTRA_PLUGGED)}" +
            "|battery_temp_tenths_c=${batteryInt(BatteryManager.EXTRA_TEMPERATURE)}" +
            "|airplane_mode=${airplaneMode ?: "unknown"}|active_network=${activeNetwork != null}" +
            "|network_internet=${networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false}" +
            "|network_validated=${networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false}" +
            "|network_metered=${networkMetered ?: "unknown"}" +
            "|network_transports=${networkTransports(networkCapabilities)}" +
            "|network_down_kbps=${networkCapabilities?.linkDownstreamBandwidthKbps ?: -1}" +
            "|network_up_kbps=${networkCapabilities?.linkUpstreamBandwidthKbps ?: -1}" +
            "|total_rx_bytes=${traffic { TrafficStats.getTotalRxBytes() }}" +
            "|total_tx_bytes=${traffic { TrafficStats.getTotalTxBytes() }}" +
            "|mobile_rx_bytes=${traffic { TrafficStats.getMobileRxBytes() }}" +
            "|mobile_tx_bytes=${traffic { TrafficStats.getMobileTxBytes() }}" +
            "|process_importance=${processState.importance}" +
            "|thread_nice=$threadNice|cpus_allowed_list=${sanitize(cpusAllowed)}" +
            "|uclamp_min=${sanitize(schedValue("uclamp.min"))}" +
            "|uclamp_max=${sanitize(schedValue("uclamp.max"))}" +
            "|effective_uclamp_min=${sanitize(schedValue("effective uclamp.min"))}" +
            "|effective_uclamp_max=${sanitize(schedValue("effective uclamp.max"))}" +
            "|process_cgroup=${sanitize(cgroup)}"
    }

    private fun networkTransports(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"
        return buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("wifi_aware")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) add("lowpan")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("usb")
        }.joinToString(",").ifBlank { "none" }
    }

    private inline fun traffic(read: () -> Long): Long =
        runCatching(read).getOrDefault(TrafficStats.UNSUPPORTED.toLong())

    private fun readProc(path: String): String = runCatching { File(path).readText() }.getOrDefault("")

    private fun sanitize(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ';')
        .replace('|', '/')
        .trim()
        .ifBlank { "unknown" }
        .take(768)
}
