package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot
import com.jingyue.mimotts.core.spi.VoiceDefinition
import com.jingyue.mimotts.core.spi.VoiceIdCodec

sealed interface MiMoVoiceResolution {
    val voice: VoiceDefinition?
    val errorCode: MiMoSafeErrorCode?
    val isSuccess: Boolean

    data class Success(override val voice: VoiceDefinition) : MiMoVoiceResolution {
        override val errorCode: MiMoSafeErrorCode? = null
        override val isSuccess: Boolean = true
    }

    data class Failure(override val errorCode: MiMoSafeErrorCode) : MiMoVoiceResolution {
        override val voice: VoiceDefinition? = null
        override val isSuccess: Boolean = false
    }
}

class MiMoVoiceResolver {
    fun resolve(
        snapshot: MiMoVoiceCatalogSnapshot,
        override: FieldState<String>,
        frameworkVoiceName: String? = null,
    ): MiMoVoiceResolution {
        snapshot.validationError()?.let { return MiMoVoiceResolution.Failure(it) }
        val default = snapshot.voiceById(snapshot.defaultVoiceId)
            ?: return MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_INVALID)
        return when (override) {
            FieldState.Absent -> {
                if (frameworkVoiceName.isNullOrBlank()) MiMoVoiceResolution.Success(default)
                else resolveRaw(snapshot, frameworkVoiceName)
            }
            is FieldState.Invalid -> MiMoVoiceResolution.Failure(override.code)
            is FieldState.Present -> {
                if (override.value.isBlank()) MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_INVALID)
                else {
                    val requested = resolveRaw(snapshot, override.value)
                    if (requested !is MiMoVoiceResolution.Success) {
                        MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_INVALID)
                    } else if (frameworkVoiceName.isNullOrBlank()) {
                        requested
                    } else {
                        val framework = resolveRaw(snapshot, frameworkVoiceName)
                        if (framework !is MiMoVoiceResolution.Success) {
                            requested
                        } else if (framework.voice.requestVoiceId != requested.voice.requestVoiceId) {
                            MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_CONFLICT)
                        } else {
                            requested
                        }
                    }
                }
            }
        }
    }

    fun resolveRaw(snapshot: MiMoVoiceCatalogSnapshot, raw: String): MiMoVoiceResolution {
        snapshot.validationError()?.let { return MiMoVoiceResolution.Failure(it) }
        if (raw.isBlank()) return MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_INVALID)
        val exact = snapshot.voices.firstOrNull { candidate ->
            raw == candidate.requestVoiceId ||
                raw == candidate.profileId ||
                raw == VoiceIdCodec.toTtsServerName(candidate) ||
                raw == VoiceIdCodec.encode(candidate)
        }
        if (exact != null) return MiMoVoiceResolution.Success(exact)

        val decoded = VoiceIdCodec.decode(raw) ?: return MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_INVALID)
        val canonical = snapshot.voices.firstOrNull { candidate ->
            candidate.providerId == decoded.providerId &&
                candidate.modelId == decoded.modelId &&
                candidate.profileId == decoded.profileId &&
                candidate.kind == decoded.kind &&
                candidate.requestVoiceId == decoded.requestVoiceId &&
                candidate.locale == decoded.locale
        }
        return canonical?.let(MiMoVoiceResolution::Success)
            ?: MiMoVoiceResolution.Failure(MiMoSafeErrorCode.VOICE_INVALID)
    }
}
