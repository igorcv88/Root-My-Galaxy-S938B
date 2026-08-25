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

    /** Auto Root never consults the mutable network feed during boot. */
    fun resolveCachedTarget(snapshot: DeviceSnapshot): TargetProfile {
        AutoRootAttestation.requireCurrent(context)
        val profile = loadProfile(autoRootProfileFile())
        require(profile.matches(snapshot)) {
            context.getString(R.string.auto_root_cached_profile_missing)
        }
        return profile
    }

    fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        val directory = manualPayloadDirectory(profile).apply { mkdirs() }
        val exploit = downloadArtifact(
            profile.exploit,
            File(directory, EXPLOIT_FILE),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = downloadArtifact(
            profile.kernelSu.artifact,
            File(directory, KERNELSU_FILE),
            context.getString(R.string.artifact_kernelsu),
            onProgress,
        )
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)

        // This is only a candidate. It becomes Auto Root's known-good profile
        // after the manual install receipt proves KernelSU late-load succeeded.
        if (profile.exactMatch != null) cacheProfile(profile, candidateProfileFile())
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    /**
     * Promotes the candidate from the current successful manual installation to
     * an immutable app-private known-good snapshot. The old known-good snapshot
     * remains untouched until the new copies and profile are fully verified.
     */
    fun prepareAutoRoot(snapshot: DeviceSnapshot): Boolean = runCatching {
        require(AutoRootAttestation.candidateBelongsToCurrentAppInstall(context)) {
            context.getString(R.string.auto_root_cache_build_mismatch)
        }
        require(NativeProbe.isKernelSuActive()) {
            context.getString(R.string.auto_root_not_prepared)
        }
        require(successReceiptMatchesCurrentBoot()) {
            context.getString(R.string.auto_root_not_prepared)
        }
        val profile = loadProfile(candidateProfileFile())
        require(profile.matches(snapshot)) {
            context.getString(R.string.auto_root_not_prepared)
        }

        val manual = verifiedManualPayloads(profile)
        val knownGood = autoRootPayloadDirectory(profile).apply { mkdirs() }
        val exploit = copyVerifiedArtifact(
            manual.exploit,
            profile.exploit,
            File(knownGood, EXPLOIT_FILE),
            context.getString(R.string.artifact_exploit),
        )
        val kernelSu = copyVerifiedArtifact(
            manual.kernelSu,
            profile.kernelSu.artifact,
            File(knownGood, KERNELSU_FILE),
            context.getString(R.string.artifact_kernelsu),
        )
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        cacheProfile(profile, autoRootProfileFile())
        require(AutoRootAttestation.record(context)) {
            context.getString(R.string.auto_root_cache_build_mismatch)
        }
        cachedPayloads(profile)
        true
    }.getOrDefault(false)

    fun cachedPayloads(
        profile: TargetProfile,
        onProgress: (String) -> Unit = {},
    ): VerifiedPayloads {
        require(profile.exactMatch != null) {
            context.getString(R.string.auto_root_cached_profile_missing)
        }
        val directory = autoRootPayloadDirectory(profile)
        val exploit = verifyCachedArtifact(
            profile.exploit,
            File(directory, EXPLOIT_FILE),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = verifyCachedArtifact(
            profile.kernelSu.artifact,
            File(directory, KERNELSU_FILE),
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

    private fun verifiedManualPayloads(profile: TargetProfile): VerifiedPayloads {
        val directory = manualPayloadDirectory(profile)
        val exploit = verifyCachedArtifact(
            profile.exploit,
            File(directory, EXPLOIT_FILE),
            context.getString(R.string.artifact_exploit),
            {},
        )
        val kernelSu = verifyCachedArtifact(
            profile.kernelSu.artifact,
            File(directory, KERNELSU_FILE),
            context.getString(R.string.artifact_kernelsu),
            {},
        )
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    private fun manualPayloadDirectory(profile: TargetProfile) =
        File(context.filesDir, "payloads/${profile.profileId}")

    private fun autoRootPayloadDirectory(profile: TargetProfile) = File(
        context.filesDir,
        "auto-root/payloads/${profile.profileId}/" +
            "${profile.exploit.sha256}-${profile.kernelSu.artifact.sha256}",
    )

    private fun candidateProfileFile() =
        File(context.filesDir, "payloads/$AUTO_ROOT_CANDIDATE_CACHE")

    private fun autoRootProfileFile() =
        File(context.filesDir, "auto-root/$AUTO_ROOT_PROFILE_CACHE")

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

    private fun copyVerifiedArtifact(
        source: File,
        artifact: RemoteArtifact,
        destination: File,
        label: String,
    ): File {
        require(artifactMatches(source, artifact)) {
            context.getString(R.string.auto_root_cached_artifact_invalid, label)
        }
        if (artifactMatches(destination, artifact)) return destination
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        temporary.delete()
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            require(artifactMatches(temporary, artifact)) {
                context.getString(R.string.auto_root_cached_artifact_invalid, label)
            }
            if (destination.exists()) destination.delete()
            require(temporary.renameTo(destination)) {
                context.getString(R.string.repo_finalize_failed, label)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
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

    private fun successReceiptMatchesCurrentBoot(): Boolean {
        val bootToken = currentBootToken() ?: return false
        val receipt = context.getSharedPreferences(INSTALL_RECEIPT, Context.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken &&
            receipt.getBoolean(RECEIPT_VERIFIED, false)
    }

    private fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun cacheProfile(profile: TargetProfile, target: File) {
        require(profile.exactMatch != null)
        requirePinnedArtifactUrl(profile.exploit.url)
        requirePinnedArtifactUrl(profile.kernelSu.artifact.url)
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
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

    private fun loadProfile(file: File): TargetProfile {
        val bytes = try {
            AtomicFile(file).openRead().use { it.readBytes() }
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
        private const val AUTO_ROOT_CANDIDATE_CACHE = "auto-root-candidate-v3.json"
        private const val AUTO_ROOT_PROFILE_CACHE = "auto-root-profile-v3.json"
        private const val EXPLOIT_FILE = "cve-2026-43499-app.so"
        private const val KERNELSU_FILE = "ksud-s25u-kdp"
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
        private const val MAX_COMMIT_RESPONSE_BYTES = 16 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
