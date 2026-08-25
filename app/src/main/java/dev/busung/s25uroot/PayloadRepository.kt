package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
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

    fun resolveTarget(profileId: String): TargetProfile = loadTargets()
        .firstOrNull { it.profileId == profileId }
        ?: error(context.getString(R.string.repo_profile_missing, profileId))

    /**
     * Auto Root never consults the mutable network feed during boot. It uses the
     * exact profile that was cached only after a prior manual payload download
     * completed size and SHA-256 verification.
     */
    fun resolveCachedTarget(snapshot: DeviceSnapshot): TargetProfile {
        val profile = loadCachedAutoRootProfile()
        require(profile.matches(snapshot)) {
            context.getString(R.string.auto_root_cached_profile_missing)
        }
        return profile
    }

    fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        val directory = payloadDirectory(profile).apply { mkdirs() }
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
        if (profile.exactMatch != null) cacheAutoRootProfile(profile)
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    fun cachedPayloads(
        profile: TargetProfile,
        onProgress: (String) -> Unit = {},
    ): VerifiedPayloads {
        require(profile.exactMatch != null) {
            context.getString(R.string.auto_root_cached_profile_missing)
        }
        val directory = payloadDirectory(profile)
        val exploit = verifyCachedArtifact(
            profile.exploit,
            File(directory, "cve-2026-43499-app.so"),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = verifyCachedArtifact(
            profile.kernelSu.artifact,
            File(directory, "ksud-s25u-kdp"),
            context.getString(R.string.artifact_kernelsu),
            onProgress,
        )
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    fun isAutoRootPrepared(snapshot: DeviceSnapshot): Boolean = runCatching {
        cachedPayloads(resolveCachedTarget(snapshot))
    }.isSuccess

    private fun payloadDirectory(profile: TargetProfile) =
        File(context.filesDir, "payloads/${profile.profileId}")

    private fun downloadArtifact(
        artifact: RemoteArtifact,
        destination: File,
        label: String,
        onProgress: (String) -> Unit,
    ): File {
        if (artifactMatches(destination, artifact)) {
            onProgress(context.getString(R.string.repo_cached_verified, label))
            return destination
        }

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

    private fun verifyCachedArtifact(
        artifact: RemoteArtifact,
        file: File,
        label: String,
        onProgress: (String) -> Unit,
    ): File {
        require(file.exists() && file.isFile) {
            context.getString(R.string.auto_root_cached_artifact_missing, label)
        }
        require(artifactMatches(file, artifact)) {
            context.getString(R.string.auto_root_cached_artifact_invalid, label)
        }
        onProgress(context.getString(R.string.repo_cached_verified, label))
        return file
    }

    private fun artifactMatches(file: File, artifact: RemoteArtifact): Boolean = runCatching {
        file.exists() &&
            file.isFile &&
            file.length() == artifact.size &&
            sha256(file) == artifact.sha256
    }.getOrDefault(false)

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cacheAutoRootProfile(profile: TargetProfile) {
        require(profile.exactMatch != null)
        requirePinnedArtifactUrl(profile.exploit.url)
        requirePinnedArtifactUrl(profile.kernelSu.artifact.url)
        val directory = File(context.filesDir, "payloads").apply { mkdirs() }
        val atomicFile = AtomicFile(File(directory, AUTO_ROOT_PROFILE_CACHE))
        val output = atomicFile.startWrite()
        try {
            output.write(encodeProfile(profile))
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun loadCachedAutoRootProfile(): TargetProfile {
        val file = AtomicFile(File(context.filesDir, "payloads/$AUTO_ROOT_PROFILE_CACHE"))
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (error: Throwable) {
            throw IllegalStateException(
                context.getString(R.string.auto_root_cached_profile_missing),
                error,
            )
        }
        val manifest = SupportManifest.parse(bytes)
        require(manifest.targets.size == 1) {
            context.getString(R.string.auto_root_cached_profile_missing)
        }
        val profile = manifest.targets.single()
        require(profile.exactMatch != null) {
            context.getString(R.string.auto_root_cached_profile_missing)
        }
        requirePinnedArtifactUrl(profile.exploit.url)
        requirePinnedArtifactUrl(profile.kernelSu.artifact.url)
        return profile
    }

    private fun encodeProfile(profile: TargetProfile): ByteArray {
        val exact = requireNotNull(profile.exactMatch)
        val payload = JSONObject()
            .put("payloadId", profile.profileId)
            .put("displayName", profile.displayName)
            .put("models", JSONArray(profile.models.toList()))
            .put("kernelVersions", JSONArray(profile.kernelVersions.toList()))
            .put(
                "exactMatch",
                JSONObject()
                    .put("manufacturer", exact.manufacturer)
                    .put("model", exact.model)
                    .put("device", exact.device)
                    .put("buildDisplay", exact.buildDisplay)
                    .put("buildFingerprint", exact.buildFingerprint)
                    .put("kernelRelease", exact.kernelRelease)
                    .put("kernelVersionInfo", exact.kernelVersionInfo)
                    .put("machine", exact.machine)
                    .put("sdk", exact.sdk)
                    .put("abi", exact.abi)
                    .put("pageSize", exact.pageSize),
            )
            .put("exploit", artifactJson(profile.exploit))
            .put(
                "kernelsu",
                artifactJson(profile.kernelSu.artifact)
                    .put("kmi", profile.kernelSu.kmi)
                    .put("managerPackage", profile.kernelSu.managerPackage),
            )
        return JSONObject()
            .put("schemaVersion", 3)
            .put("payloads", JSONArray().put(payload))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun artifactJson(artifact: RemoteArtifact) = JSONObject()
        .put("url", artifact.url)
        .put("size", artifact.size)
        .put("sha256", artifact.sha256)

    private fun requirePinnedArtifactUrl(url: String) {
        val prefix = "$RAW_REPOSITORY/"
        require(url.startsWith(prefix)) { context.getString(R.string.repo_url_invalid) }
        val immutableRef = url.removePrefix(prefix).substringBefore('/')
        require(immutableRef.matches(Regex("[0-9a-f]{40}"))) {
            context.getString(R.string.repo_url_invalid)
        }
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
        private const val PAYLOAD_REPOSITORY = "igorcv88/Root-My-Galaxy-Payloads-S938B"
        private const val COMMIT_API_URL =
            "https://api.github.com/repos/$PAYLOAD_REPOSITORY/git/ref/heads/main"
        private const val RAW_REPOSITORY =
            "https://raw.githubusercontent.com/$PAYLOAD_REPOSITORY"
        private const val MUTABLE_RAW_PREFIX = "$RAW_REPOSITORY/main/"
        private const val AUTO_ROOT_PROFILE_CACHE = "auto-root-profile-v3.json"
        private const val MAX_COMMIT_RESPONSE_BYTES = 16 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
