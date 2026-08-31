package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal object AutoRootSupport {
    private const val INSTALL_RECEIPT = "install_receipt"
    private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
    private const val RECEIPT_VERIFIED = "verified"
    private const val RECEIPT_ACTIVE_SNAPSHOT = "active_payload_snapshot"
    private const val AUTO_ROOT_STATE = "auto_root_state"
    private const val LAST_ATTEMPT_BOOT_TOKEN = "last_attempt_boot_id"
    private const val PENDING_ROOT = "payloads/pending"
    private const val SNAPSHOT_ROOT = "autoroot/snapshots"
    private const val SNAPSHOT_MANIFEST = "target-v3.json"
    private const val SNAPSHOT_BOOT_TOKEN = "download-boot-id"
    private const val SNAPSHOT_EXPLOIT = "cve-2026-43499-app.so"
    private const val SNAPSHOT_KSUD = "ksud-s25u-kdp"
    private val SNAPSHOT_ID = Regex("v3-[0-9a-f]{16}-[0-9a-f]{16}")

    fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    fun hasVerifiedInstall(context: Context): Boolean = runCatching {
        loadVerifiedLocalPayloads(context)
        true
    }.getOrDefault(false)

    fun verifiedBootToken(context: Context): String? {
        val preferences = context.getSharedPreferences(INSTALL_RECEIPT, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(RECEIPT_VERIFIED, false)) return null
        return preferences.getString(RECEIPT_BOOT_TOKEN, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    fun shouldRunForBoot(context: Context, currentBootToken: String): Boolean =
        shouldRunForBoot(currentBootToken, verifiedBootToken(context))

    fun markVerifiedForBoot(context: Context, bootToken: String) {
        val stored = context.getSharedPreferences(INSTALL_RECEIPT, Context.MODE_PRIVATE)
            .edit()
            .putString(RECEIPT_BOOT_TOKEN, bootToken)
            .putBoolean(RECEIPT_VERIFIED, true)
            .commit()
        require(stored) { context.getString(R.string.error_receipt) }
    }

    @Synchronized
    fun claimAttempt(context: Context, bootToken: String): Boolean {
        val preferences = context.getSharedPreferences(AUTO_ROOT_STATE, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_ATTEMPT_BOOT_TOKEN, null) == bootToken) return false
        return preferences.edit()
            .putString(LAST_ATTEMPT_BOOT_TOKEN, bootToken)
            .commit()
    }

    /**
     * Network-free by design. Auto Root always uses the last payload set from a
     * successful manual root. Manual root also calls this as its offline fallback.
     */
    fun loadVerifiedLocalPayloads(context: Context): VerifiedPayloads {
        val preferences = context.getSharedPreferences(INSTALL_RECEIPT, Context.MODE_PRIVATE)
        promoteSuccessfulPending(context, preferences)?.let { return it }

        val snapshotId = preferences.getString(RECEIPT_ACTIVE_SNAPSHOT, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: error(context.getString(R.string.autoroot_prior_install_required))
        require(SNAPSHOT_ID.matches(snapshotId)) {
            context.getString(R.string.autoroot_profile_invalid)
        }
        return loadSnapshot(context, File(context.filesDir, "$SNAPSHOT_ROOT/$snapshotId"))
    }

    /**
     * A remote manual download becomes active only if the normal installer later
     * wrote a verified receipt for the same kernel boot. A failed exploit/reboot
     * therefore leaves the previous snapshot untouched.
     */
    private fun promoteSuccessfulPending(
        context: Context,
        preferences: android.content.SharedPreferences,
    ): VerifiedPayloads? {
        if (!preferences.getBoolean(RECEIPT_VERIFIED, false)) return null
        val verifiedBootToken = preferences.getString(RECEIPT_BOOT_TOKEN, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val currentDevice = DeviceSnapshot.current()
        val candidates = File(context.filesDir, PENDING_ROOT).listFiles()
            ?.filter(File::isDirectory)
            ?.mapNotNull { directory ->
                runCatching {
                    val downloadBootToken = File(directory, SNAPSHOT_BOOT_TOKEN)
                        .readText(Charsets.US_ASCII)
                        .trim()
                    if (downloadBootToken != verifiedBootToken) return@runCatching null

                    val manifest = SupportManifest.parse(File(directory, SNAPSHOT_MANIFEST).readBytes())
                    val profile = manifest.targets.singleOrNull() ?: return@runCatching null
                    if (profile.exactMatch == null || !profile.matches(currentDevice)) {
                        return@runCatching null
                    }

                    val exploit = File(directory, SNAPSHOT_EXPLOIT)
                    val kernelSu = File(directory, SNAPSHOT_KSUD)
                    if (!fileMatchesArtifact(exploit, profile.exploit)) return@runCatching null
                    if (!fileMatchesArtifact(kernelSu, profile.kernelSu.artifact)) return@runCatching null
                    VerifiedPayloads(profile, exploit, kernelSu)
                }.getOrNull()
            }
            .orEmpty()

        if (candidates.isEmpty()) return null
        require(candidates.size == 1) { context.getString(R.string.autoroot_profile_invalid) }

        val candidate = candidates.single()
        val snapshotId = snapshotId(candidate.profile)
        ensureSnapshot(context, candidate, snapshotId)
        val stored = preferences.edit()
            .putString(RECEIPT_ACTIVE_SNAPSHOT, snapshotId)
            .commit()
        require(stored) { context.getString(R.string.error_receipt) }
        cleanupSnapshots(context, snapshotId)
        candidate.exploit.parentFile?.deleteRecursively()
        return loadSnapshot(context, File(context.filesDir, "$SNAPSHOT_ROOT/$snapshotId"))
    }

    private fun loadSnapshot(context: Context, directory: File): VerifiedPayloads {
        require(directory.isDirectory) { context.getString(R.string.autoroot_profile_invalid) }
        val manifestFile = File(directory, SNAPSHOT_MANIFEST)
        require(manifestFile.isFile) { context.getString(R.string.autoroot_profile_invalid) }
        val manifest = SupportManifest.parse(manifestFile.readBytes())
        require(manifest.targets.size == 1) { context.getString(R.string.autoroot_profile_invalid) }
        val profile = manifest.targets.single()
        require(profile.exactMatch != null) { context.getString(R.string.autoroot_profile_invalid) }
        require(profile.matches(DeviceSnapshot.current())) {
            context.getString(R.string.autoroot_unsupported_firmware)
        }

        val exploit = File(directory, SNAPSHOT_EXPLOIT)
        val kernelSu = File(directory, SNAPSHOT_KSUD)
        require(fileMatchesArtifact(exploit, profile.exploit)) {
            context.getString(R.string.autoroot_cached_payload_invalid, exploit.name)
        }
        require(fileMatchesArtifact(kernelSu, profile.kernelSu.artifact)) {
            context.getString(R.string.autoroot_cached_payload_invalid, kernelSu.name)
        }
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    private fun snapshotId(profile: TargetProfile): String =
        "v3-${profile.exploit.sha256.take(16)}-${profile.kernelSu.artifact.sha256.take(16)}"

    private fun ensureSnapshot(context: Context, payloads: VerifiedPayloads, snapshotId: String) {
        val root = File(context.filesDir, SNAPSHOT_ROOT).apply {
            require(mkdirs() || isDirectory) { context.getString(R.string.autoroot_profile_invalid) }
        }
        val destination = File(root, snapshotId)
        if (snapshotMatches(destination, payloads.profile)) return

        val temporary = File(root, ".$snapshotId-${System.nanoTime()}.tmp")
        temporary.deleteRecursively()
        require(temporary.mkdirs()) { context.getString(R.string.autoroot_profile_invalid) }
        try {
            copyVerified(payloads.exploit, File(temporary, SNAPSHOT_EXPLOIT), payloads.profile.exploit)
            copyVerified(payloads.kernelSu, File(temporary, SNAPSHOT_KSUD), payloads.profile.kernelSu.artifact)
            writeSynced(
                File(temporary, SNAPSHOT_MANIFEST),
                SupportManifest(3, listOf(payloads.profile)).toJsonBytes(),
            )
            require(snapshotMatches(temporary, payloads.profile)) {
                context.getString(R.string.autoroot_profile_invalid)
            }

            if (destination.exists()) destination.deleteRecursively()
            require(temporary.renameTo(destination)) {
                context.getString(R.string.autoroot_profile_invalid)
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun snapshotMatches(directory: File, profile: TargetProfile): Boolean = runCatching {
        if (!directory.isDirectory) return@runCatching false
        val manifest = SupportManifest.parse(File(directory, SNAPSHOT_MANIFEST).readBytes())
        if (manifest.targets.singleOrNull() != profile) return@runCatching false
        fileMatchesArtifact(File(directory, SNAPSHOT_EXPLOIT), profile.exploit) &&
            fileMatchesArtifact(File(directory, SNAPSHOT_KSUD), profile.kernelSu.artifact)
    }.getOrDefault(false)

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

    private fun cleanupSnapshots(context: Context, activeSnapshotId: String) {
        File(context.filesDir, SNAPSHOT_ROOT).listFiles()
            ?.filter { it.name != activeSnapshotId }
            ?.forEach(File::deleteRecursively)
    }
}

internal fun shouldRunForBoot(currentBootToken: String, verifiedBootToken: String?): Boolean {
    if (currentBootToken.isBlank() || verifiedBootToken.isNullOrBlank()) return false
    return currentBootToken != verifiedBootToken
}

internal fun fileMatchesArtifact(file: File, artifact: RemoteArtifact): Boolean {
    if (!file.isFile || file.length() != artifact.size) return false
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256
    }.getOrDefault(false)
}
