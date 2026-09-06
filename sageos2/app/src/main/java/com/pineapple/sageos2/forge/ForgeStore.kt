package com.pineapple.sageos2.forge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ForgePairing(
    val url: String,
    val certSha256: String,
    val deviceId: String
)

class ForgeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pairing(): ForgePairing? {
        val url = prefs.getString(KEY_URL, "").orEmpty()
        val pin = prefs.getString(KEY_PIN, "").orEmpty()
        val deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        val protectedToken = prefs.getString(KEY_PROTECTED_TOKEN, "").orEmpty()
        if (url.isBlank() || pin.isBlank() || protectedToken.isBlank()) return null
        return ForgePairing(url, normalizePin(pin), deviceId)
    }

    fun isPaired(): Boolean = pairing() != null

    @Throws(Exception::class)
    fun savePairing(url: String, certSha256: String, deviceId: String, deviceToken: String) {
        require(deviceToken.isNotBlank()) { "Forge device token is blank" }
        val normalizedUrl = ForgeProtocol.normalizeOrigin(url)
        val pin = normalizePin(certSha256)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(deviceToken.toByteArray(StandardCharsets.UTF_8))
        val protected = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_URL, normalizedUrl)
            .putString(KEY_PIN, pin)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_PROTECTED_TOKEN, protected)
            .apply()
    }

    @Throws(Exception::class)
    fun token(): String {
        val protected = prefs.getString(KEY_PROTECTED_TOKEN, "").orEmpty()
        val parts = protected.split(':', limit = 2)
        require(parts.size == 2) { "paired Forge token is unavailable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_URL)
            .remove(KEY_PIN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PROTECTED_TOKEN)
            .remove(KEY_ACTIVE_JOB)
            .apply()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    fun saveActiveJob(jobId: String?) {
        if (jobId.isNullOrBlank()) prefs.edit().remove(KEY_ACTIVE_JOB).apply()
        else prefs.edit().putString(KEY_ACTIVE_JOB, ForgeProtocol.safeJobId(jobId)).apply()
    }

    fun activeJob(): String? = prefs.getString(KEY_ACTIVE_JOB, null)?.takeIf { it.isNotBlank() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(KEY_ALIAS)) return store.getKey(KEY_ALIAS, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFS = "sage_forge"
        const val KEY_ALIAS = "sage_forge_pairing_token_v1"
        private const val KEY_URL = "url"
        private const val KEY_PIN = "pin"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PROTECTED_TOKEN = "protected_token"
        private const val KEY_ACTIVE_JOB = "active_job"

        fun normalizePin(value: String): String {
            val pin = value.lowercase(Locale.US).replace(":", "").replace(" ", "").trim()
            require(pin.matches(Regex("[0-9a-f]{64}"))) {
                "certificate SHA-256 must contain exactly 64 hexadecimal characters"
            }
            return pin
        }
    }
}
