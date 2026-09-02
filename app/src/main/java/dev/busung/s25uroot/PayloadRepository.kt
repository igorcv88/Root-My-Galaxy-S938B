package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
    val source: PayloadSource = PayloadSource.ManualOnline,
    val artifactSource: PayloadArtifactSource = PayloadArtifactSource.RemoteDownload,
)

enum class PayloadSource(val wireValue: String) {
    ManualOnline("manual_online"),
    ManualOffline("manual_offline"),
}

enum class PayloadArtifactSource(val wireValue: String) {
    RemoteDownload("remote_download"),
    VerifiedLocalSnapshot("verified_local_snapshot"),
}

private class PayloadNetworkException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class PayloadRepository(private val context: Context) {
    @Volatile
    private var lastResolutionSource: PayloadSource = PayloadSource.ManualOnline

    /**
     * Every online manual-root lookup starts from the current Payloads `main`.
     * `main` is resolved once to a commit so the manifest and artifacts are an
     * atomic repository snapshot during that one install attempt.
     */
    fun loadTargets(): List<TargetProfile> {
        val commit = resolveMainCommit()
        val manifestBytes = downloadBytes(rawUrl(commit, MANIFEST_PATH), MAX_MANIFEST_BYTES)
        return SupportManifest.parse(manifestBytes).targets.map { profile ->
            profile.copy(
                exploit = profile.exploit.copy(url = pinArtifactUrl(profile.exploit.url, commit)),
                kernelSu = profile.kernelSu.copy(
                    artifact = profile.kernelSu.artifact.copy(
                        url = pinArtifactUrl(profile.kernelSu.artifact.url, commit),
                    ),
                ),
            )
        }
    }

    fun resolveTarget(snapshot: DeviceSnapshot): TargetProfile = try {
        val profile = loadTargets().firstOrNull { it.matches(snapshot) }
            ?: error(context.getString(R.string.repo_no_profile))
        lastResolutionSource = PayloadSource.ManualOnline
        profile
    } catch (error: PayloadNetworkException) {
        val cached = AutoRootSupport.loadVerifiedLocalPayloads(context)
        require(cached.profile.matches(snapshot)) {
            context.getString(R.string.repo_no_profile)
        }
        lastResolutionSource = PayloadSource.ManualOffline
        cached.profile
    }

    fun resolveTarget(profileId: String): TargetProfile = try {
        val profile = loadTargets().firstOrNull { it.profileId == profileId }
            ?: error(context.getString(R.string.repo_profile_missing, profileId))
        lastResolutionSource = PayloadSource.ManualOnline
        profile
    } catch (error: PayloadNetworkException) {
        val cached = AutoRootSupport.loadVerifiedLocalPayloads(context)
        require(cached.profile.profileId == profileId) {
            context.getString(R.string.repo_profile_missing, profileId)
        }
        lastResolutionSource = PayloadSource.ManualOffline
        cached.profile
    }

    /**
     * Online manual root normally downloads the selected payload again for
     * every run. CZG3 Shizuku is the one deliberately narrow exception: after
     * target resolution, reuse the last verified local files when their payload
     * identities are byte-for-byte identical. The invocation provenance remains
     * whatever target resolution actually observed: an offline manifest fallback
     * stays ManualOffline even though the artifact itself is a verified snapshot.
     *
     * Only network unavailability falls back to the last successful manual-root
     * snapshot. Manifest/hash/compatibility failures remain hard failures.
     */
    fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        val resolutionSource = lastResolutionSource
        onProgress("Payload source: Root-My-Galaxy-Payloads-S938B/main")
        onProgress("RMG_PAYLOAD_V1|resolution_source=${resolutionSource.wireValue}")
        if (
            profile.profileId == CZG3_PROFILE_ID_FOR_DIAGNOSTICS &&
            AppPreferences.shizukuMode(context)
        ) {
            verifiedLocalPayloadsIfCurrent(profile)?.let { cached ->
                onProgress("Remote payload unchanged; reusing verified local snapshot for quiet Shizuku launch")
                onProgress(
                    "RMG_PAYLOAD_V1|invocation_source=${resolutionSource.wireValue}|" +
                        "artifact_source=${PayloadArtifactSource.VerifiedLocalSnapshot.wireValue}",
                )
                return cached.copy(
                    profile = profile,
                    source = resolutionSource,
                    artifactSource = PayloadArtifactSource.VerifiedLocalSnapshot,
                )
            }
        }
        return try {
            downloadRemote(profile, onProgress).also {
                onProgress(
                    "RMG_PAYLOAD_V1|invocation_source=${PayloadSource.ManualOnline.wireValue}|" +
                        "artifact_source=${PayloadArtifactSource.RemoteDownload.wireValue}",
                )
            }
        } catch (error: PayloadNetworkException) {
            onProgress("Payloads/main unavailable; using last successful manual-root snapshot")
            val cached = AutoRootSupport.loadVerifiedLocalPayloads(context)
            require(cached.profile.profileId == profile.profileId) {
                context.getString(R.string.repo_profile_missing, profile.profileId)
            }
            onProgress(
                "RMG_PAYLOAD_V1|invocation_source=${PayloadSource.ManualOffline.wireValue}|" +
                    "artifact_source=${PayloadArtifactSource.VerifiedLocalSnapshot.wireValue}",
            )
            cached.copy(
                source = PayloadSource.ManualOffline,
                artifactSource = PayloadArtifactSource.VerifiedLocalSnapshot,
            )
        }
    }

    private fun verifiedLocalPayloadsIfCurrent(profile: TargetProfile): VerifiedPayloads? {
        val cached = runCatching { AutoRootSupport.loadVerifiedLocalPayloads(context) }
            .getOrNull()
            ?: return null
        if (!samePayloadIdentity(cached.profile, profile)) return null
        if (!fileMatchesArtifact(cached.exploit, profile.exploit)) return null
        if (!fileMatchesArtifact(cached.kernelSu, profile.kernelSu.artifact)) return null
        return cached
    }

    private fun samePayloadIdentity(cached: TargetProfile, current: TargetProfile): Boolean =
        cached.profileId == current.profileId &&
            cached.exploit.size == current.exploit.size &&
            cached.exploit.sha256 == current.exploit.sha256 &&
            cached.kernelSu.artifact.size == current.kernelSu.artifact.size &&
            cached.kernelSu.artifact.sha256 == current.kernelSu.artifact.sha256 &&
            cached.kernelSu.kmi == current.kernelSu.kmi &&
            cached.kernelSu.managerPackage == current.kernelSu.managerPackage

    private fun downloadRemote(
        profile: TargetProfile,
        onProgress: (String) -> Unit,
    ): VerifiedPayloads {
        val directory = File(context.filesDir, "payloads/pending/${profile.profileId}").apply {
            deleteRecursively()
            require(mkdirs() || isDirectory) { context.getString(R.string.repo_finalize_failed, name) }
        }
        val exploit = downloadArtifact(
            profile.exploit,
            File(directory, "cve-2026-43499-app.so"),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = downloadArtifact(
            profile.kernelSu.artifact,
            File(directory, "ksud-s25u-kdp"),
            context.getString(R.string.artifact_kernelsu),
            onProgress,
        )
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)

        val bootToken = AutoRootSupport.currentBootToken()
            ?: error(context.getString(R.string.error_boot_id))
        writeSynced(
            File(directory, "target-v3.json"),
            SupportManifest(3, listOf(profile)).toJsonBytes(),
        )
        writeSynced(
            File(directory, "download-boot-id"),
            "$bootToken\n".toByteArray(Charsets.US_ASCII),
        )
        return VerifiedPayloads(
            profile,
            exploit,
            kernelSu,
            source = PayloadSource.ManualOnline,
            artifactSource = PayloadArtifactSource.RemoteDownload,
        )
    }

    private fun downloadArtifact(
        artifact: RemoteArtifact,
        destination: File,
        label: String,
        onProgress: (String) -> Unit,
    ): File {
        onProgress(context.getString(R.string.repo_downloading, label))
        val temporary = File(destination.parentFile, "${destination.name}.part")
        if (temporary.exists()) temporary.delete()
        val connection = open(artifact.url)
        try {
            require(connection.contentLengthLong == -1L || connection.contentLengthLong == artifact.size) {
                context.getString(R.string.repo_size_mismatch, label)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= artifact.size) {
                                context.getString(R.string.repo_size_exceeded, label)
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: IOException) {
                throw PayloadNetworkException("Network failed while downloading $label", error)
            }
            require(total == artifact.size) { context.getString(R.string.repo_incomplete, label) }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualSha256 == artifact.sha256) {
                "$label SHA-256 does not match the support manifest"
            }
            if (destination.exists()) destination.delete()
            require(temporary.renameTo(destination)) {
                context.getString(R.string.repo_finalize_failed, label)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        onProgress(context.getString(R.string.repo_verified, label))
        return destination
    }

    private fun writeSynced(destination: File, bytes: ByteArray) {
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun resolveMainCommit(): String {
        val response = downloadBytes("$COMMIT_API_PREFIX$MAIN_BRANCH", MAX_COMMIT_RESPONSE_BYTES)
        val commit = JSONObject(response.toString(Charsets.UTF_8))
            .getJSONObject("object")
            .getString("sha")
        require(commit.matches(Regex("[0-9a-f]{40}"))) {
            context.getString(R.string.repo_commit_invalid)
        }
        return commit
    }

    private fun rawUrl(commit: String, path: String) = "$RAW_REPOSITORY/$commit/$path"

    private fun pinArtifactUrl(url: String, commit: String): String {
        require(url.startsWith(MUTABLE_RAW_PREFIX)) { context.getString(R.string.repo_url_invalid) }
        return "$RAW_REPOSITORY/$commit/${url.removePrefix(MUTABLE_RAW_PREFIX)}"
    }

    private fun downloadBytes(url: String, maximum: Int): ByteArray {
        val connection = open(url)
        return try {
            try {
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= maximum) {
                            context.getString(R.string.repo_response_too_large)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (error: IOException) {
                throw PayloadNetworkException("Network failed while reading Payloads/main", error)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "S25URoot/${BuildConfig.VERSION_NAME}")
                connect()
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val code = connection.responseCode
                connection.disconnect()
                throw PayloadNetworkException("Payloads/main returned HTTP $code")
            }
            return connection
        } catch (error: PayloadNetworkException) {
            throw error
        } catch (error: IOException) {
            throw PayloadNetworkException("Unable to reach Payloads/main", error)
        }
    }

    companion object {
        private const val PAYLOAD_REPOSITORY = "igorcv88/Root-My-Galaxy-Payloads-S938B"
        private const val MAIN_BRANCH = "main"
        private const val MANIFEST_PATH = "support/targets-v3.json"
        private const val COMMIT_API_PREFIX =
            "https://api.github.com/repos/$PAYLOAD_REPOSITORY/git/ref/heads/"
        private const val RAW_REPOSITORY =
            "https://raw.githubusercontent.com/$PAYLOAD_REPOSITORY"
        private const val MUTABLE_RAW_PREFIX = "$RAW_REPOSITORY/main/"
        private const val MAX_COMMIT_RESPONSE_BYTES = 16 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
