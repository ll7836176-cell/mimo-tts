package com.jingyue.mimotts.core.spi

/** Stable error identifiers; messages must not contain request/config payloads. */
enum class MiMoSafeErrorCode(val wireValue: String) {
    REQUEST_INVALID("request.invalid"),
    REQUEST_UNSUPPORTED_PROTOCOL("request.unsupported_protocol"),
    TARGET_ID_INVALID("target.id_invalid"),
    TARGET_NOT_FOUND("target.not_found"),
    TARGET_AMBIGUOUS("target.ambiguous"),
    ROUTE_DECLARATION_INVALID("route.declaration_invalid"),
    PARAM_UNKNOWN_KEY("param.unknown_key"),
    PARAM_TYPE_UNSUPPORTED("param.type_unsupported"),
    PARAM_CONFLICT("param.conflict"),
    VOICE_INVALID("voice.invalid"),
    VOICE_CONFLICT("voice.conflict"),
    STYLE_INVALID("style.invalid"),
    STYLE_TOO_LONG("style.too_long"),
    AUTH_FAILURE("auth.failure"),
    RATE_LIMIT("rate_limit"),
    NETWORK_TIMEOUT("network.timeout"),
    NETWORK_FAILURE("network.failure"),
    PROVIDER_UNAVAILABLE("provider.unavailable"),
    RESPONSE_INVALID("response.invalid"),
    AUDIO_EMPTY("audio.empty"),
    AUDIO_DECODING("audio.decoding"),
    CANCELLED("cancelled"),
    ENGINE_CONFIG_INVALID("engine.config_invalid"),
    SETTINGS_MIGRATION_FAILED("settings.migration_failed"),
    RUNTIME_PUBLISH_FAILED("runtime.publish_failed"),
    CONTROL_INVALID_ARGUMENT("control.invalid_argument"),
    CONTROL_INVALID_TOKEN("control.invalid_token"),
    CONTROL_NOT_DEBUGGABLE("control.not_debuggable"),
    CONTROL_ALREADY_CREATED("control.already_created"),
    CONTROL_UNSUPPORTED_MODE("control.unsupported_mode"),
    INTERNAL("internal"),
}

sealed interface FieldState<out T> {
    object Absent : FieldState<Nothing>
    data class Present<T>(val value: T) : FieldState<T>
    data class Invalid(val code: MiMoSafeErrorCode) : FieldState<Nothing>
}

data class MiMoOverridePresenceSnapshot(
    val voice: FieldState<String> = FieldState.Absent,
    val style: FieldState<String> = FieldState.Absent,
) {
    val voiceId: FieldState<String> get() = voice
}

data class MiMoVoiceCatalogSnapshot(
    val generation: Long,
    val sourceVersion: String,
    val voices: List<VoiceDefinition>,
    val defaultVoiceId: String,
    val parameterSchemaVersion: String,
) {
    init {
        require(generation >= 0) { "generation must not be negative" }
        require(sourceVersion.isNotBlank()) { "sourceVersion must not be blank" }
        require(parameterSchemaVersion.isNotBlank()) { "parameterSchemaVersion must not be blank" }
        require(voices.map { it.requestVoiceId }.distinct().size == voices.size) {
            "voice ids must be unique"
        }
    }

    fun voiceById(id: String): VoiceDefinition? = voices.singleOrNull { it.requestVoiceId == id }

    /**
     * Validates the catalog boundary without exposing any mutable or verbose
     * diagnostic data.  MiMo's built-in catalog is deliberately fixed: a
     * matching request id alone is not enough to make a voice trustworthy.
     */
    fun validationError(): MiMoSafeErrorCode? {
        if (sourceVersion != SOURCE_VERSION || parameterSchemaVersion != PARAMETER_SCHEMA_VERSION) {
            return MiMoSafeErrorCode.VOICE_INVALID
        }
        if (defaultVoiceId != DEFAULT_VOICE_ID || voices.size != EXPECTED_VOICE_IDS.size) {
            return MiMoSafeErrorCode.VOICE_INVALID
        }
        if (voices.map { it.requestVoiceId }.toSet() != EXPECTED_VOICE_IDS) {
            return MiMoSafeErrorCode.VOICE_INVALID
        }
        if (voices.count { it.requestVoiceId == DEFAULT_VOICE_ID } != 1) {
            return MiMoSafeErrorCode.VOICE_INVALID
        }
        if (voices.any { voice ->
                voice.providerId != PROVIDER_ID ||
                    voice.modelId != BUILT_IN_MODEL_ID ||
                    voice.kind != VoiceKind.BUILT_IN ||
                    voice.profileId != "builtin-${voice.requestVoiceId}" ||
                    voice.displayName != voice.requestVoiceId ||
                    voice.description.isNotEmpty() ||
                    voice.requiresUserResource ||
                    voice.locale != expectedLocale(voice.requestVoiceId)
            }
        ) {
            return MiMoSafeErrorCode.VOICE_INVALID
        }
        return null
    }

    private fun expectedLocale(requestVoiceId: String): java.util.Locale =
        if (requestVoiceId in CHINESE_VOICE_IDS) {
            java.util.Locale.SIMPLIFIED_CHINESE
        } else {
            java.util.Locale.US
        }

    companion object {
        private const val PROVIDER_ID = "mimo"
        private const val BUILT_IN_MODEL_ID = "mimo-v2.5-tts"
        private const val DEFAULT_VOICE_ID = "mimo_default"
        private const val SOURCE_VERSION = "mimo-built-in-voices-v1"
        private const val PARAMETER_SCHEMA_VERSION = "mimo-tts-params-v1"
        private val CHINESE_VOICE_IDS = setOf(DEFAULT_VOICE_ID, "冰糖", "茉莉", "苏打", "白桦")
        private val EXPECTED_VOICE_IDS = CHINESE_VOICE_IDS + setOf("Mia", "Chloe", "Milo", "Dean")
    }
}
