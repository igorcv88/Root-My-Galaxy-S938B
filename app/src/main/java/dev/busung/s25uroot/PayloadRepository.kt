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
)

private class PayloadNetworkException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class PayloadRepository(private val context: Context) {
    /**
     * Manual root always starts from the current Payloads `main`. The branch is
     * resolved to one commit at the beginning of the request so the manifest and
     * both artifacts come from the same repository state even if `main` moves
     * while files are being downloaded.
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

    fun resolveTarget(snapshot: DeviceSnapshot): TargetProfile = loadTargets()
        .firstOrNull { it.matches(snapshot) }
        ?: error(context.getString(R.string.repo_no_profile))

    fun resolveTarget(profileId: String): TargetProfile = loadTargets()
        .firstOrNull { it.profileId == profileId }
        ?: error(context.getString(R.string.repo_profile_missing, profileId))

    /**
     * Exact manual-root policy:
     *  - online: resolve and download the current Payloads/main payload;
     *  - network unavailable: use the last successful manual-root snapshot;
     *  - no prior successful manual snapshot: fail instead of inventing an APK
     *    embedded fallback.
     */
    fun prepareManualPayloads(
        profileId: String? = null,
        onProgress: (String) -> Unit,
    ): VerifiedPayloads {
        return try {
            val profile = if (profileId == null) {
                resolveTarget(DeviceSnapshot.current())
            } else {
                resolveTarget(profileId)
            }
            onProgress("Payload source: Root-My-Galaxy-Payloads-S938B/main")
            download(profile, onProgress)
        } catch (error: PayloadNetworkException) {
            onProgress("Payloads/main unavailable; using last successful manual-root snapshot")
            val cached = AutoRootSupport.loadVerifiedLocalPayloads(context)
            if (profileId != null && cached.profile.profileId != profileId) {
                error(context.getString(R.string.repo_profile_missing, profileId))
            }
            cached
        }
    }

    private fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        // A remote manual download stays separate from the active snapshot until
        // this exact payload set completes exploit + KernelSU verification.
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
        writeSynced(
            File(directory, "target-v3.json"),
            SupportManifest(3, listOf(profile)).toJsonBytes(),
        )
        return VerifiedPayloads(profile, exploit, kernelSu)
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
