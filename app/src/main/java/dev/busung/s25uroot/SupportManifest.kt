package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject

data class RemoteArtifact(
    val url: String,
    val size: Long,
    val sha256: String,
) {
    init {
        require(size > 0) { "Artifact size must be positive" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid artifact SHA-256" }
    }
}

data class KernelSuArtifact(
    val artifact: RemoteArtifact,
    val kmi: String = "",
    val managerPackage: String = "me.weishu.kernelsu",
)

data class ExactTargetMatch(
    val manufacturer: String,
    val model: String,
    val device: String,
    val buildDisplay: String,
    val buildFingerprint: String,
    val kernelRelease: String,
    val kernelVersionInfo: String,
    val machine: String,
    val sdk: Int,
    val abi: String,
    val pageSize: Long,
) {
    fun matches(snapshot: DeviceSnapshot): Boolean =
        manufacturer.equals(snapshot.manufacturer, ignoreCase = true) &&
            model.equals(snapshot.model, ignoreCase = true) &&
            device.equals(snapshot.device, ignoreCase = true) &&
            buildDisplay == snapshot.buildId &&
            buildFingerprint == snapshot.fingerprint &&
            kernelRelease == snapshot.kernelRelease &&
            kernelVersionInfo == snapshot.kernelVersionInfo &&
            machine == snapshot.machine &&
            sdk == snapshot.sdk &&
            abi == snapshot.abi &&
            pageSize == snapshot.pageSize
}

data class TargetProfile(
    val profileId: String,
    val displayName: String,
    val models: Set<String>,
    val kernelVersions: Set<String>,
    val exactMatch: ExactTargetMatch?,
    val exploit: RemoteArtifact,
    val kernelSu: KernelSuArtifact,
) {
    init {
        require(models.isNotEmpty()) { "Payload must support at least one model" }
        require(kernelVersions.isNotEmpty()) { "Payload must support at least one kernel version" }
    }

    val manufacturer: String
        get() = exactMatch?.manufacturer.orEmpty()

    val model: String
        get() = exactMatch?.model ?: models.firstOrNull().orEmpty()

    val device: String
        get() = exactMatch?.device.orEmpty()

    val kernelRelease: String
        get() = exactMatch?.kernelRelease.orEmpty()

    val kernelBuildVersion: String
        get() = exactMatch?.kernelVersionInfo.orEmpty()

    val buildDisplay: String
        get() = exactMatch?.buildDisplay.orEmpty()

    val buildFingerprint: String
        get() = exactMatch?.buildFingerprint.orEmpty()

    val sdk: Int
        get() = exactMatch?.sdk ?: -1

    val abi: String
        get() = exactMatch?.abi.orEmpty()

    val pageSize: Long
        get() = exactMatch?.pageSize ?: -1L

    val supportedModels: String
        get() = models.joinToString()

    val supportedKernelVersions: String
        get() = kernelVersions.joinToString()

    fun matchesDevice(snapshot: DeviceSnapshot): Boolean =
        models.any { it.equals(snapshot.model, ignoreCase = true) }

    fun matchesKernelVersion(snapshot: DeviceSnapshot): Boolean =
        snapshot.kernelVersion in kernelVersions

    fun matchesKernel(snapshot: DeviceSnapshot): Boolean =
        exactMatch?.let {
            it.kernelRelease == snapshot.kernelRelease &&
                it.kernelVersionInfo == snapshot.kernelVersionInfo &&
                it.machine == snapshot.machine
        } == true

    /**
     * Automatic selection is intentionally fail-closed. Generic v3 payloads can
     * still appear in Advanced mode, but they are never selected automatically
     * unless the controlled feed supplies a complete exactMatch identity.
     */
    fun matches(snapshot: DeviceSnapshot): Boolean = exactMatch?.matches(snapshot) == true
}

data class SupportManifest(
    val schemaVersion: Int,
    val targets: List<TargetProfile>,
) {
    companion object {
        fun parse(bytes: ByteArray): SupportManifest {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val schemaVersion = root.getInt("schemaVersion")
            require(schemaVersion == 3) { "Unsupported support manifest schema" }
            val payloadsJson = root.getJSONArray("payloads")
            val payloads = buildList {
                for (index in 0 until payloadsJson.length()) {
                    val payload = payloadsJson.getJSONObject(index)
                    val exploit = payload.getJSONObject("exploit")
                    val kernelSu = payload.getJSONObject("kernelsu")
                    val exact = payload.optJSONObject("exactMatch")
                    add(
                        TargetProfile(
                            profileId = payload.getString("payloadId"),
                            displayName = payload.getString("displayName"),
                            models = payload.getJSONArray("models").strings(),
                            kernelVersions = payload.getJSONArray("kernelVersions").strings(),
                            exactMatch = exact?.let {
                                ExactTargetMatch(
                                    manufacturer = it.getString("manufacturer"),
                                    model = it.getString("model"),
                                    device = it.getString("device"),
                                    buildDisplay = it.getString("buildDisplay"),
                                    buildFingerprint = it.getString("buildFingerprint"),
                                    kernelRelease = it.getString("kernelRelease"),
                                    kernelVersionInfo = it.getString("kernelVersionInfo"),
                                    machine = it.getString("machine"),
                                    sdk = it.getInt("sdk"),
                                    abi = it.getString("abi"),
                                    pageSize = it.getLong("pageSize"),
                                )
                            },
                            exploit = exploit.artifact(),
                            kernelSu = KernelSuArtifact(
                                artifact = kernelSu.artifact(),
                                kmi = kernelSu.optString("kmi", ""),
                                managerPackage = kernelSu.optString(
                                    "managerPackage",
                                    "me.weishu.kernelsu",
                                ),
                            ),
                        ),
                    )
                }
            }
            return SupportManifest(schemaVersion, payloads)
        }

        private fun JSONObject.artifact(): RemoteArtifact = RemoteArtifact(
            url = getString("url"),
            size = getLong("size"),
            sha256 = getString("sha256").lowercase(),
        )

        private fun JSONArray.strings(): Set<String> = buildSet {
            for (index in 0 until length()) add(getString(index))
        }
    }
}
