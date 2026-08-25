package dev.busung.s25uroot

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PayloadRepositoryTest {
    @Test
    fun manifestMatchesDeviceAndArtifactsDownload() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = PayloadRepository(context)
        val snapshot = DeviceSnapshot.current()
        val profile = repository.resolveTarget(snapshot)
        val exact = profile.exactMatch
        assertNotNull(exact)
        assertEquals(snapshot.kernelRelease, exact?.kernelRelease)
        assertEquals(snapshot.kernelVersionInfo, exact?.kernelVersionInfo)
        assertEquals(snapshot.machine, exact?.machine)

        val payloads = repository.download(profile) { }
        assertEquals(profile.exploit.size, payloads.exploit.length())
        assertEquals(profile.kernelSu.artifact.size, payloads.kernelSu.length())
        assertEquals(profile.exploit.sha256, sha256(payloads.exploit))
        assertEquals(profile.kernelSu.artifact.sha256, sha256(payloads.kernelSu))
        assertTrue(payloads.exploit.canRead())
        assertTrue(payloads.kernelSu.canRead())
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
