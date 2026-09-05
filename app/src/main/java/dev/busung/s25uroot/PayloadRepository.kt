package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
    val source: PayloadSource = PayloadSource.Online,
)

class PayloadRepository(private val context: Context) {
    fun loadTargets(): List<TargetProfile> {
        val commit = resolveMainCommit()
        val manifestBytes = downloadBytes(rawUrl(commit, "support/targets-v3.json"), MAX_MANIFEST_BYTES)
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

    fun resolveTarget(profileId: String): TargetProfile {
        if (profileId.startsWith(OFFLINE_REQUEST_PREFIX)) {
            val requestedProfileId = profileId
                .removePrefix(OFFLINE_REQUEST_PREFIX)
                .takeIf(String::isNotBlank)
            return KnownGoodPayloadStore.load(context, requestedProfileId)
                .profile
                .copy(source = PayloadSource.Offline)
        }
        return loadTargets()
            .firstOrNull { it.profileId == profileId }
            ?: error(context.getString(R.string.repo_profile_missing, profileId))
    }

    fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        if (profile.source == PayloadSource.Offline) {
            onProgress("Payload source: last-known-good offline cache")
            return KnownGoodPayloadStore.load(context, profile.profileId)
        }

        onProgress("Payload source: online support feed")
        val directory = File(context.filesDir, "payloads/manual-online/${profile.profileId}")
        directory.deleteRecursively()
        require(directory.mkdirs() || directory.isDirectory) {
            context.getString(R.string.repo_finalize_failed, directory.name)
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
        val payloads = VerifiedPayloads(profile, exploit, kernelSu, PayloadSource.Online)
        runCatching { KnownGoodPayloadStore.stageCandidate(context, payloads) }
            .onFailure { error ->
                onProgress("Offline cache candidate was not staged: ${error.message ?: error.javaClass.simpleName}")
            }
        return payloads
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

    private fun resolveMainCommit(): String {
        val response = downloadBytes(COMMIT_API_URL, MAX_COMMIT_RESPONSE_BYTES)
        val commit = JSONObject(response.toString(Charsets.UTF_8))
            .getJSONObject("object")
            .getString("sha")
        require(commit.matches(Regex("[0-9a-f]{40}"))) { context.getString(R.string.repo_commit_invalid) }
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
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "S25URoot/${BuildConfig.VERSION_NAME}")
            connect()
            require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode" }
        }

    companion object {
        private const val OFFLINE_REQUEST_PREFIX = "offline-cache:"
        private const val PAYLOAD_REPOSITORY = "igorcv88/Root-My-Galaxy-Payloads-S938B"
        private const val COMMIT_API_URL =
            "https://api.github.com/repos/$PAYLOAD_REPOSITORY/git/ref/heads/main"
        private const val RAW_REPOSITORY =
            "https://raw.githubusercontent.com/$PAYLOAD_REPOSITORY"
        private const val MUTABLE_RAW_PREFIX = "$RAW_REPOSITORY/main/"
        private const val MAX_COMMIT_RESPONSE_BYTES = 16 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024

        fun offlineRequest(profileId: String?): String =
            OFFLINE_REQUEST_PREFIX + profileId.orEmpty()
    }
}
