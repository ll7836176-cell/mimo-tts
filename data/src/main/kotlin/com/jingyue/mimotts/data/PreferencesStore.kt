package com.jingyue.mimotts.data

import android.content.Context
import java.net.URI

class PreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences("mimo_preferences", Context.MODE_PRIVATE)

    var endpoint: String
        get() = preferences.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT)
            ?.trim()
            ?.takeIf(::isHttpsEndpoint)
            ?: DEFAULT_ENDPOINT
        set(value) {
            preferences.edit().putString(KEY_ENDPOINT, value.trim()).commit()
        }

    var modelId: String
        get() = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            preferences.edit().putString(KEY_MODEL, value.trim()).commit()
        }

    var voiceProfileId: String
        get() = preferences.getString(KEY_VOICE, "builtin-冰糖") ?: "builtin-冰糖"
        set(value) {
            preferences.edit().putString(KEY_VOICE, value.trim()).commit()
        }

    var serverOwnsPreprocessing: Boolean
        get() = preferences.getBoolean(KEY_SERVER_OWNS, true)
        set(value) {
            preferences.edit().putBoolean(KEY_SERVER_OWNS, value).commit()
        }

    /**
     * Persist the editable settings as one durable SharedPreferences transaction.
     * The UI uses this instead of separate asynchronous setters so a successful save
     * really means the next process start can read the same values.
     */
    fun save(endpoint: String, modelId: String): Boolean {
        val normalizedEndpoint = endpoint.trim()
        require(isHttpsEndpoint(normalizedEndpoint)) { "endpoint_must_use_https" }
        val normalizedModel = modelId.trim().ifBlank { DEFAULT_MODEL }
        return preferences.edit()
            .putString(KEY_ENDPOINT, normalizedEndpoint)
            .putString(KEY_MODEL, normalizedModel)
            .commit()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
        const val DEFAULT_MODEL = "mimo-v2.5-tts"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_SERVER_OWNS = "server_owns_preprocessing"

        private fun isHttpsEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
