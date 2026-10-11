package com.jingyue.mimotts.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecretStore(
    context: Context,
    storageName: String = DEFAULT_STORAGE_NAME,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val keyStore: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    @Synchronized
    fun putApiKey(value: String): Boolean {
        require(value.isNotBlank()) { "API key must not be blank" }

        val hardwarePayload = encryptWithAndroidKeyStore(value)
        if (hardwarePayload != null) {
            return preferences.edit()
                .putString(API_KEY, encode(hardwarePayload))
                .putString(STORAGE_MODE, HARDWARE_STORAGE)
                .remove(SOFTWARE_KEY)
                .commit()
        }

        // Some vendor KeyMint implementations cannot generate any key at all. Keep
        // the service usable with a randomly generated software key in app-private
        // storage when the user has explicitly accepted this compatibility mode.
        val softwareKey = loadOrCreateSoftwareKey()
        val softwarePayload = encrypt(value, softwareKey.secretKey)
        return preferences.edit()
            .putString(API_KEY, encode(softwarePayload))
            .putString(STORAGE_MODE, SOFTWARE_STORAGE)
            .putString(SOFTWARE_KEY, softwareKey.encoded)
            .commit()
    }

    fun hasStoredApiKey(): Boolean = preferences.contains(API_KEY)

    fun isUsingSoftwareFallback(): Boolean =
        preferences.getString(STORAGE_MODE, HARDWARE_STORAGE) == SOFTWARE_STORAGE

    fun getApiKey(): String? {
        val encoded = preferences.getString(API_KEY, null) ?: return null
        val storageMode = preferences.getString(STORAGE_MODE, HARDWARE_STORAGE)
        return runCatching {
            val key = when (storageMode) {
                SOFTWARE_STORAGE -> loadSoftwareKey()?.secretKey
                else -> keyStore?.getKey(keyAlias, null) as? SecretKey
            } ?: return@runCatching null
            decrypt(decode(encoded), key)
        }.getOrNull()
    }

    fun clear(): Boolean = preferences.edit()
        .remove(API_KEY)
        .remove(STORAGE_MODE)
        .remove(SOFTWARE_KEY)
        .commit()

    private fun encryptWithAndroidKeyStore(value: String): EncryptedPayload? {
        val store = keyStore ?: return null
        runCatching {
            encrypt(value, getOrCreateAndroidKey(store))
        }.getOrNull()?.let { return it }

        // A previously created key can become invalid after a credential or vendor
        // security-state change. Replacing that key is safe because a new API key is
        // being explicitly written and the old encrypted value is not retained.
        runCatching { store.deleteEntry(keyAlias) }
        return runCatching {
            encrypt(value, getOrCreateAndroidKey(store))
        }.getOrNull()
    }

    private fun encrypt(value: String, key: SecretKey): EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedPayload(cipher.iv, cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(payload: EncryptedPayload, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.iv))
        return String(cipher.doFinal(payload.encrypted), Charsets.UTF_8)
    }

    private fun encode(payload: EncryptedPayload): String {
        val bytes = ByteBuffer.allocate(4 + payload.iv.size + payload.encrypted.size)
            .putInt(payload.iv.size)
            .put(payload.iv)
            .put(payload.encrypted)
            .array()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun decode(encoded: String): EncryptedPayload {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.remaining() >= IV_LENGTH_PREFIX_BYTES) { "Invalid encrypted API key" }
        val ivLength = buffer.int
        require(ivLength > 0 && ivLength <= buffer.remaining()) { "Invalid encrypted API key IV" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        require(encrypted.isNotEmpty()) { "Invalid encrypted API key payload" }
        return EncryptedPayload(iv, encrypted)
    }

    private fun getOrCreateAndroidKey(store: KeyStore): SecretKey {
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadOrCreateSoftwareKey(): SoftwareKey {
        loadSoftwareKey()?.let { return it }
        val bytes = ByteArray(SOFTWARE_KEY_BYTES).also(SecureRandom()::nextBytes)
        return SoftwareKey(
            secretKey = SecretKeySpec(bytes, KeyProperties.KEY_ALGORITHM_AES),
            encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
        )
    }

    private fun loadSoftwareKey(): SoftwareKey? {
        val encoded = preferences.getString(SOFTWARE_KEY, null) ?: return null
        val bytes = runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (bytes.size != SOFTWARE_KEY_BYTES) return null
        return SoftwareKey(
            secretKey = SecretKeySpec(bytes, KeyProperties.KEY_ALGORITHM_AES),
            encoded = encoded,
        )
    }

    private data class EncryptedPayload(
        val iv: ByteArray,
        val encrypted: ByteArray,
    )

    private data class SoftwareKey(
        val secretKey: SecretKey,
        val encoded: String,
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_STORAGE_NAME = "mimo_secret_store"
        private const val DEFAULT_KEY_ALIAS = "com.jingyue.mimotts.api-key"
        private const val API_KEY = "api_key"
        private const val STORAGE_MODE = "api_key_storage_mode"
        private const val SOFTWARE_KEY = "api_key_software_key"
        private const val HARDWARE_STORAGE = "android_keystore"
        private const val SOFTWARE_STORAGE = "software_compatibility"
        private const val SOFTWARE_KEY_BYTES = 32
        private const val IV_LENGTH_PREFIX_BYTES = 4
    }
}
