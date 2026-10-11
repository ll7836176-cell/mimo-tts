package com.github.jing332.tts_server_android.model.speech.tts

class SpeechRuleMiMoProfileParser {
    data class Profile(
        val schemaVersion: Int,
        val voice: String? = null,
        val style: String? = null,
    )

    sealed interface Result {
        object Absent : Result
        data class Success(val profile: Profile) : Result
        data class Failure(val code: TtsServerSafeErrorCode) : Result
    }

    fun parse(raw: Map<String, Any?>): Result {
        if (raw.isEmpty()) return Result.Absent
        val unknown = raw.keys.firstOrNull { it !in setOf("schemaVersion", "voice", "style") }
        if (unknown != null) return Result.Failure(TtsServerSafeErrorCode.PARAM_UNKNOWN_KEY)

        val schemaVersion = when (val value = raw["schemaVersion"]) {
            null -> 1
            is Number -> {
                val number = value.toDouble()
                if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
                    return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
                }
                number.toInt()
            }
            else -> return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (schemaVersion != 1) return Result.Failure(TtsServerSafeErrorCode.REQUEST_UNSUPPORTED_PROTOCOL)

        val voice = raw["voice"]?.let { value ->
            value as? String ?: return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (voice != null && voice.isBlank()) return Result.Failure(TtsServerSafeErrorCode.VOICE_INVALID)

        val style = raw["style"]?.let { value ->
            value as? String ?: return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (style != null && style.utf8ByteLength() > MAX_STYLE_UTF8_BYTES) {
            return Result.Failure(TtsServerSafeErrorCode.STYLE_TOO_LONG)
        }
        return Result.Success(Profile(schemaVersion, voice, style))
    }

    companion object {
        const val MAX_STYLE_UTF8_BYTES = 1024
    }
}
