package dev.busung.s25uroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetProfileTest {
    private val profile = TargetProfile(
        profileId = "pa3q-S938BXXSBCZG3",
        displayName = "Galaxy S25 Ultra SM-S938B | S938BXXSBCZG3",
        models = setOf("SM-S938B"),
        kernelVersions = setOf("6.6.98"),
        exactMatch = ExactTargetMatch(
            manufacturer = "samsung",
            model = "SM-S938B",
            device = "pa3q",
            buildDisplay = "BP4A.251205.006.S938BXXSBCZG3",
            buildFingerprint = "samsung/pa3qxxx/pa3q:16/BP4A.251205.006/S938BXXSBCZG3_OXMBCZG3:user/release-keys",
            kernelRelease = "6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k",
            kernelVersionInfo = "#1 SMP PREEMPT Thu Jul  2 00:48:56 UTC 2026",
            machine = "aarch64",
            sdk = 36,
            abi = "arm64-v8a",
            pageSize = 4096,
        ),
        exploit = RemoteArtifact(
            "https://example.invalid/exploit",
            1,
            "0".repeat(64),
        ),
        kernelSu = KernelSuArtifact(
            artifact = RemoteArtifact(
                "https://example.invalid/ksud",
                1,
                "1".repeat(64),
            ),
            kmi = "android15-6.6",
            managerPackage = "me.weishu.kernelsu",
        ),
    )

    @Test
    fun exactSnapshotMatches() {
        assertTrue(profile.matches(snapshot()))
    }

    @Test
    fun changedIdentityFailsClosed() {
        assertFalse(profile.matches(snapshot(manufacturer = "other")))
        assertFalse(profile.matches(snapshot(model = "SM-S938N")))
        assertFalse(profile.matches(snapshot(device = "other")))
        assertFalse(profile.matches(snapshot(kernelRelease = "6.6.98-android15-8-other")))
        assertFalse(profile.matches(snapshot(kernelVersionInfo = "#1 SMP PREEMPT changed")))
        assertFalse(profile.matches(snapshot(machine = "x86_64")))
        assertFalse(profile.matches(snapshot(buildId = "BP4A.251205.006.S938BXXSBCZG4")))
        assertFalse(profile.matches(snapshot(fingerprint = "samsung/changed")))
        assertFalse(profile.matches(snapshot(sdk = 37)))
        assertFalse(profile.matches(snapshot(abi = "x86_64")))
        assertFalse(profile.matches(snapshot(pageSize = 16384)))
    }

    @Test
    fun genericCompatibilityNeverQualifiesForAutomaticSelection() {
        val genericOnly = profile.copy(exactMatch = null)
        val device = snapshot()

        assertTrue(genericOnly.matchesDevice(device))
        assertTrue(genericOnly.matchesKernelVersion(device))
        assertFalse(genericOnly.matches(device))
        assertFalse(genericOnly.matchesKernel(device))
    }

    private fun snapshot(
        manufacturer: String = "samsung",
        model: String = "SM-S938B",
        device: String = "pa3q",
        kernelRelease: String = "6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k",
        kernelVersionInfo: String = "#1 SMP PREEMPT Thu Jul  2 00:48:56 UTC 2026",
        machine: String = "aarch64",
        buildId: String = "BP4A.251205.006.S938BXXSBCZG3",
        fingerprint: String = "samsung/pa3qxxx/pa3q:16/BP4A.251205.006/S938BXXSBCZG3_OXMBCZG3:user/release-keys",
        sdk: Int = 36,
        abi: String = "arm64-v8a",
        pageSize: Long = 4096,
    ) = DeviceSnapshot(
        manufacturer = manufacturer,
        model = model,
        device = device,
        kernelRelease = kernelRelease,
        kernelVersionInfo = kernelVersionInfo,
        machine = machine,
        buildId = buildId,
        fingerprint = fingerprint,
        androidRelease = "16",
        sdk = sdk,
        abi = abi,
        pageSize = pageSize,
    )
}
