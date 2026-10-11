package com.github.jing332.tts_server_android.model.rhino.speech_rule

import com.github.jing332.tts_server_android.model.speech.TtsServerSafeErrorCode
import com.github.jing332.tts_server_android.model.speech.utf8ByteLength

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
        if (raw.keys.any { it !in PROFILE_KEYS }) {
            return Result.Failure(TtsServerSafeErrorCode.PARAM_UNKNOWN_KEY)
        }

        val schemaVersion = when (val value = raw[KEY_SCHEMA_VERSION]) {
            null -> 1
            is Number -> {
                val number = value.toDouble()
                if (!number.isFinite() || number % 1.0 != 0.0 ||
                    number < Int.MIN_VALUE || number > Int.MAX_VALUE
                ) {
                    return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
                }
                number.toInt()
            }
            else -> return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (schemaVersion != 1) {
            return Result.Failure(TtsServerSafeErrorCode.REQUEST_UNSUPPORTED_PROTOCOL)
        }

        val voice = raw[KEY_VOICE]?.let { value ->
            value as? String ?: return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (voice != null && voice.isBlank()) {
            return Result.Failure(TtsServerSafeErrorCode.VOICE_INVALID)
        }

        val style = raw[KEY_STYLE]?.let { value ->
            value as? String ?: return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (style != null && style.utf8ByteLength() > MAX_STYLE_UTF8_BYTES) {
            return Result.Failure(TtsServerSafeErrorCode.STYLE_TOO_LONG)
        }
        return Result.Success(Profile(schemaVersion, voice, style))
    }

    companion object {
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_VOICE = "voice"
        private const val KEY_STYLE = "style"
        private val PROFILE_KEYS = setOf(KEY_SCHEMA_VERSION, KEY_VOICE, KEY_STYLE)
        const val MAX_STYLE_UTF8_BYTES = 1024
    }
}
