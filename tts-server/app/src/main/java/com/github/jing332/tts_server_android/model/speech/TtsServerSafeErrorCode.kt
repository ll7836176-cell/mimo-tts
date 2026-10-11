package com.github.jing332.tts_server_android.model.speech

/** Stable, non-sensitive error identifiers owned by the tts-server boundary. */
enum class TtsServerSafeErrorCode(val wireValue: String) {
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

data class TtsServerSafeFailure(val code: TtsServerSafeErrorCode) {
    val wireValue: String get() = code.wireValue
}
