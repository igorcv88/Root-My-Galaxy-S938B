package dev.busung.s25uroot

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * A plain Android-side minimum boot-uptime gate for CZG3.
 *
 * Despite the historical UI name "Diagnostic Launch Time", this class performs
 * no diagnostics, observation, sampling, logging, or native instrumentation.
 */
internal object DiagnosticUptime {
    const val DEFAULT_SECONDS = 120

    val allowedSeconds = listOf(0, 30, 60, 90, 120, 180, 300, 600)

    fun normalize(seconds: Int): Int =
        allowedSeconds.minByOrNull { kotlin.math.abs(it - seconds) } ?: DEFAULT_SECONDS

    suspend fun waitUntil(seconds: Int) {
        val targetMillis = normalize(seconds) * 1_000L
        while (true) {
            val remaining = targetMillis - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            delay(minOf(remaining, 1_000L))
        }
    }
}

internal fun isExactCzg3(profile: TargetProfile): Boolean =
    profile.profileId == "pa3q-S938BXXSBCZG3"

internal fun isExactCzg3(device: DeviceSnapshot): Boolean =
    device.manufacturer.equals("samsung", ignoreCase = true) &&
        device.model == "SM-S938B" &&
        device.device == "pa3q" &&
        device.buildId == "BP4A.251205.006.S938BXXSBCZG3" &&
        device.kernelRelease == "6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k"
