package dev.busung.s25uroot

internal fun isExactCzg3DiagnosticTarget(device: DeviceSnapshot): Boolean =
    device.manufacturer.equals("samsung", ignoreCase = true) &&
        device.model == "SM-S938B" &&
        device.device == "pa3q" &&
        device.buildId == "BP4A.251205.006.S938BXXSBCZG3" &&
        device.kernelRelease == "6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k"

internal fun shouldUseLegacyAutoRootStabilization(device: DeviceSnapshot): Boolean =
    !isExactCzg3DiagnosticTarget(device)
