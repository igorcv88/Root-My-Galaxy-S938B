package dev.busung.s25uroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Czg3AutoRootPolicyTest {
    private val exact = DeviceSnapshot(
        manufacturer = "samsung", model = "SM-S938B", device = "pa3q",
        kernelRelease = "6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k",
        kernelVersionInfo = "#1", machine = "aarch64",
        buildId = "BP4A.251205.006.S938BXXSBCZG3", fingerprint = "fingerprint",
        androidRelease = "16", sdk = 36, abi = "arm64-v8a", pageSize = 4096,
    )

    @Test fun exactCzg3BypassesLegacyStabilization() {
        assertTrue(isExactCzg3DiagnosticTarget(exact))
        assertFalse(shouldUseLegacyAutoRootStabilization(exact))
    }

    @Test fun otherBuildKeepsLegacyStabilization() {
        assertTrue(shouldUseLegacyAutoRootStabilization(exact.copy(buildId = "other")))
    }
}
