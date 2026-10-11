package com.jingyue.mimotts.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mimoStudioDataStore by preferencesDataStore(name = "mimo_studio")

/** Async preference boundary for UI state; secrets deliberately stay in [SecretStore]. */
class StudioDataStore(private val context: Context) {
    val snapshot: Flow<StudioPreferences> = context.mimoStudioDataStore.data.map { preferences ->
        StudioPreferences(
            endpoint = preferences[Keys.ENDPOINT] ?: PreferencesStore.DEFAULT_ENDPOINT,
            modelId = preferences[Keys.MODEL] ?: "mimo-v2.5-tts",
            voiceProfileId = preferences[Keys.VOICE] ?: "builtin-冰糖",
            serverOwnsPreprocessing = preferences[Keys.SERVER_OWNS] ?: true,
        )
    }

    suspend fun update(transform: StudioPreferences.() -> StudioPreferences) {
        context.mimoStudioDataStore.edit { current ->
            val next = StudioPreferences(
                endpoint = current[Keys.ENDPOINT] ?: PreferencesStore.DEFAULT_ENDPOINT,
                modelId = current[Keys.MODEL] ?: "mimo-v2.5-tts",
                voiceProfileId = current[Keys.VOICE] ?: "builtin-冰糖",
                serverOwnsPreprocessing = current[Keys.SERVER_OWNS] ?: true,
            ).transform()
            current[Keys.ENDPOINT] = next.endpoint
            current[Keys.MODEL] = next.modelId
            current[Keys.VOICE] = next.voiceProfileId
            current[Keys.SERVER_OWNS] = next.serverOwnsPreprocessing
        }
    }

    private object Keys {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val VOICE = stringPreferencesKey("voice")
        val SERVER_OWNS = booleanPreferencesKey("server_owns_preprocessing")
    }
}

data class StudioPreferences(
    val endpoint: String,
    val modelId: String,
    val voiceProfileId: String,
    val serverOwnsPreprocessing: Boolean,
)
