package dev.busung.s25uroot

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

object AutoRootAttestation {
    fun candidateBelongsToCurrentAppInstall(context: Context): Boolean = runCatching {
        val candidate = File(context.filesDir, CANDIDATE_PATH)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        candidate.isFile && candidate.lastModified() >= packageInfo.lastUpdateTime
    }.getOrDefault(false)

    fun record(context: Context): Boolean = runCatching {
        val target = attestationFile(context)
        target.parentFile?.mkdirs()
        val value = JSONObject()
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("helperSha256", currentHelperSha256(context))
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        true
    }.getOrDefault(false)

    fun isCurrent(context: Context): Boolean = runCatching {
        val value = JSONObject(
            AtomicFile(attestationFile(context)).openRead().use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            },
        )
        value.getInt("appVersionCode") == BuildConfig.VERSION_CODE &&
            value.getString("appVersionName") == BuildConfig.VERSION_NAME &&
            value.getString("helperSha256") == currentHelperSha256(context)
    }.getOrDefault(false)

    fun requireCurrent(context: Context) {
        require(isCurrent(context)) {
            context.getString(R.string.auto_root_cache_build_mismatch)
        }
    }

    private fun currentHelperSha256(context: Context): String {
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_FILE)
        require(helper.isFile) { context.getString(R.string.error_helper_unavailable) }
        return helper.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun attestationFile(context: Context) = File(context.filesDir, ATTESTATION_PATH)

    private const val CANDIDATE_PATH = "payloads/auto-root-candidate-v3.json"
    private const val ATTESTATION_PATH = "auto-root/attestation-v1.json"
    private const val HELPER_FILE = "libcve43499root.so"
}
