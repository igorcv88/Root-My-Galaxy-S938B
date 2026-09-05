package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal object AutoRootSupport {
    private const val INSTALL_RECEIPT = "install_receipt"
    private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
    private const val RECEIPT_VERIFIED = "verified"
    private const val AUTO_ROOT_STATE = "auto_root_state"
    private const val LAST_ATTEMPT_BOOT_TOKEN = "last_attempt_boot_id"

    fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    fun hasVerifiedInstall(context: Context): Boolean {
        val receipt = context.getSharedPreferences(INSTALL_RECEIPT, Context.MODE_PRIVATE)
            .getBoolean(RECEIPT_VERIFIED, false)
        return receipt && KnownGoodPayloadStore.hasValid(context)
    }

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

    /** Auto Root is deliberately network-free and always consumes known-good cache. */
    fun loadVerifiedLocalPayloads(context: Context): VerifiedPayloads =
        KnownGoodPayloadStore.load(context)
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
