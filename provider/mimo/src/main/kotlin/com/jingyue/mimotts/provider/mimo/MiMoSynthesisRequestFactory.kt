package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.MiMoOverridePresenceSnapshot
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot
import com.jingyue.mimotts.core.spi.VoiceKind

data class MiMoPreparedSynthesis(
    val voice: com.jingyue.mimotts.core.spi.VoiceDefinition,
    val profile: MiMoVoiceProfile,
    val input: MiMoSynthesisInput,
)

sealed interface MiMoSynthesisFactoryResult {
    val errorCode: MiMoSafeErrorCode?

    data class Success(val prepared: MiMoPreparedSynthesis) : MiMoSynthesisFactoryResult {
        override val errorCode: MiMoSafeErrorCode? = null
    }

    data class Failure(override val errorCode: MiMoSafeErrorCode) : MiMoSynthesisFactoryResult
}

class MiMoSynthesisRequestFactory(
    private val resolver: MiMoVoiceResolver = MiMoVoiceResolver(),
    private val instructionAssembler: MiMoInstructionAssembler = MiMoInstructionAssembler(),
) {
    fun create(
        catalog: MiMoVoiceCatalogSnapshot,
        overrides: MiMoOverridePresenceSnapshot,
        frameworkVoiceName: String?,
        endpoint: String,
        apiKey: String,
        text: String,
        baseInstructions: List<String> = emptyList(),
        audioTags: List<String> = emptyList(),
        modelId: String = MiMoModelIds.BUILT_IN,
        speechRate: Float = 1f,
        pitch: Float = 1f,
        volume: Float = 1f,
    ): MiMoSynthesisFactoryResult {
        if (apiKey.isBlank()) return MiMoSynthesisFactoryResult.Failure(MiMoSafeErrorCode.AUTH_FAILURE)
        if (endpoint.isBlank() || text.isBlank()) {
            return MiMoSynthesisFactoryResult.Failure(MiMoSafeErrorCode.REQUEST_INVALID)
        }
        if (!speechRate.isFinite() || !pitch.isFinite() || !volume.isFinite()) {
            return MiMoSynthesisFactoryResult.Failure(MiMoSafeErrorCode.REQUEST_INVALID)
        }
        val resolved = resolver.resolve(catalog, overrides.voice, frameworkVoiceName)
        if (resolved !is MiMoVoiceResolution.Success) {
            return MiMoSynthesisFactoryResult.Failure(resolved.errorCode ?: MiMoSafeErrorCode.VOICE_INVALID)
        }
        val voice = resolved.voice
        if (voice.kind != VoiceKind.BUILT_IN || voice.modelId != MiMoModelIds.BUILT_IN) {
            return MiMoSynthesisFactoryResult.Failure(MiMoSafeErrorCode.VOICE_INVALID)
        }
        if (modelId != MiMoModelIds.BUILT_IN) {
            return MiMoSynthesisFactoryResult.Failure(MiMoSafeErrorCode.REQUEST_INVALID)
        }
        val assembled: MiMoInstructionAssemblyResult = instructionAssembler.assemble(
            baseInstructions,
            overrides.style,
        )
        if (!assembled.isSuccess) {
            return MiMoSynthesisFactoryResult.Failure(
                assembled.errorCode ?: MiMoSafeErrorCode.STYLE_INVALID,
            )
        }
        val profile = MiMoVoiceProfile.builtIn(voice.profileId, voice.requestVoiceId)
        return MiMoSynthesisFactoryResult.Success(
            MiMoPreparedSynthesis(
                voice = voice,
                profile = profile,
                input = MiMoSynthesisInput(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    profile = profile,
                    text = text,
                    instructions = assembled.instructions,
                    audioTags = audioTags.toList(),
                    modelId = modelId,
                    speechRate = speechRate,
                    pitch = pitch,
                    volume = volume,
                ),
            ),
        )
    }

    fun create(
        catalog: MiMoVoiceCatalogSnapshot,
        overrides: MiMoOverridePresenceSnapshot,
        endpoint: String,
        apiKey: String,
        text: String,
        baseInstructions: List<String> = emptyList(),
        audioTags: List<String> = emptyList(),
        modelId: String = MiMoModelIds.BUILT_IN,
    ): MiMoSynthesisFactoryResult = create(
        catalog = catalog,
        overrides = overrides,
        frameworkVoiceName = null,
        endpoint = endpoint,
        apiKey = apiKey,
        text = text,
        baseInstructions = baseInstructions,
        audioTags = audioTags,
        modelId = modelId,
    )
}
