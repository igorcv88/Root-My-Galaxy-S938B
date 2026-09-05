package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream

/**
 * Minimal offline cache.
 *
 * Only a payload set that has already completed a manual root is promoted here.
 * Auto Root and Manual Offline never download and never trust the mutable online
 * working directory; they re-verify size/SHA-256 and the exact device identity
 * every time they load the active known-good set.
 */
internal object KnownGoodPayloadStore {
    private const val PREFS = "known_good_payload"
    private const val ACTIVE = "active"
    private const val ROOT = "payloads/known-good"
    private const val MANIFEST = "target-v3.json"
    private const val EXPLOIT = "cve-2026-43499-app.so"
    private const val KSUD = "ksud-s25u-kdp"
    private val CACHE_ID = Regex("v3-[0-9a-f]{16}-[0-9a-f]{16}")

    fun hasValid(context: Context): Boolean = runCatching {
        load(context)
        true
    }.getOrDefault(false)

    fun load(context: Context, profileId: String? = null): VerifiedPayloads {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: error("No last-known-good payload is available. Run Manual Online successfully first.")
        require(CACHE_ID.matches(id)) { "Invalid last-known-good payload reference" }

        val payloads = loadDirectory(context, File(context.filesDir, "$ROOT/$id"))
        if (profileId != null) {
            require(payloads.profile.profileId == profileId) {
                "The cached payload does not match the selected profile"
            }
        }
        return payloads
    }

    @Synchronized
    fun promote(context: Context, payloads: VerifiedPayloads): VerifiedPayloads {
        val profile = payloads.profile
        require(profile.exactMatch != null && profile.matches(DeviceSnapshot.current())) {
            "Only the exact verified target can become the offline payload"
        }
        require(fileMatchesArtifact(payloads.exploit, profile.exploit)) {
            "Exploit failed final cache verification"
        }
        require(fileMatchesArtifact(payloads.kernelSu, profile.kernelSu.artifact)) {
            "KernelSU failed final cache verification"
        }

        val id = cacheId(profile)
        val root = File(context.filesDir, ROOT).apply {
            require(mkdirs() || isDirectory) { "Unable to create offline payload cache" }
        }
        val destination = File(root, id)

        val reusable = runCatching {
            val existing = loadDirectory(context, destination)
            existing.profile == profile
        }.getOrDefault(false)

        if (!reusable) {
            val temporary = File(root, ".$id-${System.nanoTime()}.tmp")
            temporary.deleteRecursively()
            require(temporary.mkdirs()) { "Unable to create offline payload candidate" }
            try {
                copyVerified(payloads.exploit, File(temporary, EXPLOIT), profile.exploit)
                copyVerified(payloads.kernelSu, File(temporary, KSUD), profile.kernelSu.artifact)
                writeSynced(
                    File(temporary, MANIFEST),
                    SupportManifest(3, listOf(profile)).toJsonBytes(),
                )
                loadDirectory(context, temporary)

                if (destination.exists()) destination.deleteRecursively()
                require(temporary.renameTo(destination)) { "Unable to publish offline payload cache" }
            } finally {
                temporary.deleteRecursively()
            }
        }

        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE, id)
            .commit()
        require(stored) { "Unable to activate offline payload cache" }

        root.listFiles()
            ?.filter { it.name != id }
            ?.forEach(File::deleteRecursively)

        return load(context)
    }

    private fun loadDirectory(context: Context, directory: File): VerifiedPayloads {
        require(directory.isDirectory) { "Offline payload directory is missing" }
        val manifestFile = File(directory, MANIFEST)
        require(manifestFile.isFile) { "Offline payload manifest is missing" }
        val manifest = SupportManifest.parse(manifestFile.readBytes())
        require(manifest.targets.size == 1) { "Offline payload manifest is invalid" }
        val profile = manifest.targets.single()
        require(profile.exactMatch != null && profile.matches(DeviceSnapshot.current())) {
            context.getString(R.string.autoroot_unsupported_firmware)
        }

        val exploit = File(directory, EXPLOIT)
        val kernelSu = File(directory, KSUD)
        require(fileMatchesArtifact(exploit, profile.exploit)) {
            context.getString(R.string.autoroot_cached_payload_invalid, exploit.name)
        }
        require(fileMatchesArtifact(kernelSu, profile.kernelSu.artifact)) {
            context.getString(R.string.autoroot_cached_payload_invalid, kernelSu.name)
        }
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        return VerifiedPayloads(profile, exploit, kernelSu, PayloadSource.Offline)
    }

    private fun cacheId(profile: TargetProfile): String =
        "v3-${profile.exploit.sha256.take(16)}-${profile.kernelSu.artifact.sha256.take(16)}"

    private fun copyVerified(source: File, destination: File, artifact: RemoteArtifact) {
        require(fileMatchesArtifact(source, artifact))
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        Os.chmod(destination.absolutePath, 0b100100100)
        require(fileMatchesArtifact(destination, artifact))
    }

    private fun writeSynced(destination: File, bytes: ByteArray) {
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}
