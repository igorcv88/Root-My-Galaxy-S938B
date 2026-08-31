package dev.busung.s25uroot

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoRootIntegrityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactSizeAndSha256Pass() {
        val file = temporaryFolder.newFile("payload.bin").apply {
            writeBytes("known-good-payload".toByteArray())
        }
        assertTrue(fileMatchesArtifact(file, artifactFor(file)))
    }

    @Test
    fun sameSizeDifferentContentFails() {
        val expected = temporaryFolder.newFile("expected.bin").apply {
            writeBytes("payload-A".toByteArray())
        }
        val candidate = temporaryFolder.newFile("candidate.bin").apply {
            writeBytes("payload-B".toByteArray())
        }
        assertTrue(expected.length() == candidate.length())
        assertFalse(fileMatchesArtifact(candidate, artifactFor(expected)))
    }

    @Test
    fun wrongSizeAndMissingFilesFailClosed() {
        val expected = temporaryFolder.newFile("expected.bin").apply {
            writeBytes("expected-payload".toByteArray())
        }
        val shorter = temporaryFolder.newFile("short.bin").apply {
            writeBytes("short".toByteArray())
        }
        val artifact = artifactFor(expected)
        assertFalse(fileMatchesArtifact(shorter, artifact))
        assertFalse(fileMatchesArtifact(File(temporaryFolder.root, "missing.bin"), artifact))
    }

    @Test
    fun unchangedKernelBootIdIsTreatedAsSoftReboot() {
        assertFalse(shouldRunForBoot("boot-a", "boot-a"))
    }

    @Test
    fun changedKernelBootIdAllowsFullBootRestore() {
        assertTrue(shouldRunForBoot("boot-b", "boot-a"))
    }

    @Test
    fun missingBootReceiptFailsClosed() {
        assertFalse(shouldRunForBoot("boot-b", null))
        assertFalse(shouldRunForBoot("boot-b", ""))
        assertFalse(shouldRunForBoot("", "boot-a"))
    }

    private fun artifactFor(file: File): RemoteArtifact = RemoteArtifact(
        url = "https://example.invalid/payload",
        size = file.length(),
        sha256 = sha256(file),
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
