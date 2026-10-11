package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.AudioContainer
import com.jingyue.mimotts.core.spi.ModelCapability
import com.jingyue.mimotts.core.spi.ModelDescriptor
import com.jingyue.mimotts.core.spi.StreamingSemantics
import com.jingyue.mimotts.core.spi.VoiceDefinition
import com.jingyue.mimotts.core.spi.VoiceKind
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object MiMoModelIds {
    const val BUILT_IN = "mimo-v2.5-tts"
    const val VOICE_DESIGN = "mimo-v2.5-tts-voicedesign"
    const val VOICE_CLONE = "mimo-v2.5-tts-voiceclone"
}

data class VoiceSample(
    val mimeType: String,
    val bytes: ByteArray,
) {
    fun validationErrors(): List<String> {
        val normalized = mimeType.lowercase(Locale.ROOT)
        val errors = mutableListOf<String>()
        if (normalized !in setOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav")) {
            errors += "Sample must be MP3 or WAV"
        }
        val encodedSize = ((bytes.size + 2) / 3) * 4
        if (encodedSize > MAX_BASE64_BYTES) errors += "Base64 sample must not exceed 10 MB"
        if (bytes.isEmpty()) errors += "Sample is empty"
        return errors
    }

    fun dataUrl(): String {
        require(validationErrors().isEmpty()) { validationErrors().joinToString("; ") }
        val mime = when (mimeType.lowercase(Locale.ROOT)) {
            "audio/mp3" -> "audio/mpeg"
            "audio/x-wav" -> "audio/wav"
            else -> mimeType.lowercase(Locale.ROOT)
        }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    companion object {
        const val MAX_BASE64_BYTES = 10 * 1024 * 1024
    }
}

data class MiMoVoiceProfile(
    val id: String,
    val kind: VoiceKind,
    val modelId: String,
    val voiceId: String? = null,
    val voiceDescription: String? = null,
    val sample: VoiceSample? = null,
    val optimizeTextPreview: Boolean = false,
    val displayName: String = id,
) {
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (id.isBlank()) errors += "Profile id is required"
        when (kind) {
            VoiceKind.BUILT_IN -> if (voiceId.isNullOrBlank()) errors += "Built-in Voice is required"
            VoiceKind.DESIGN -> if (voiceDescription.isNullOrBlank()) errors += "Voice description is required"
            VoiceKind.CLONE -> {
                if (sample == null) errors += "Voice clone sample is required"
                else errors += sample.validationErrors()
            }
        }
        return errors
    }

    companion object {
        fun builtIn(id: String, voiceId: String): MiMoVoiceProfile = MiMoVoiceProfile(
            id = id,
            kind = VoiceKind.BUILT_IN,
            modelId = MiMoModelIds.BUILT_IN,
            voiceId = voiceId,
            displayName = voiceId,
        )

        fun design(id: String, description: String, optimizeTextPreview: Boolean = false): MiMoVoiceProfile =
            MiMoVoiceProfile(
                id = id,
                kind = VoiceKind.DESIGN,
                modelId = MiMoModelIds.VOICE_DESIGN,
                voiceDescription = description,
                optimizeTextPreview = optimizeTextPreview,
            )

        fun clone(id: String, sample: VoiceSample, optimizeTextPreview: Boolean = false): MiMoVoiceProfile =
            MiMoVoiceProfile(
                id = id,
                kind = VoiceKind.CLONE,
                modelId = MiMoModelIds.VOICE_CLONE,
                sample = sample,
                optimizeTextPreview = optimizeTextPreview,
            )
    }
}

data class MiMoModelProfile(
    val descriptor: ModelDescriptor,
    val forbidsBuiltInVoice: Boolean,
)

object MiMoModelProfiles {
    val builtIn = MiMoModelProfile(
        descriptor = ModelDescriptor(
            id = MiMoModelIds.BUILT_IN,
            displayName = "MiMo v2.5 TTS",
            capabilities = setOf(
                ModelCapability.STYLE_INSTRUCTION,
                ModelCapability.AUDIO_TAGS,
                ModelCapability.STREAMING_PCM,
                ModelCapability.MULTI_ROLE,
            ),
            supportedVoiceKinds = setOf(VoiceKind.BUILT_IN),
            supportedFormats = setOf(AudioContainer.RAW_PCM, AudioContainer.WAV, AudioContainer.MP3),
            streaming = StreamingSemantics.REAL_TIME,
            requiredConfigKeys = setOf("apiKey"),
        ),
        forbidsBuiltInVoice = false,
    )

    val voiceDesign = MiMoModelProfile(
        descriptor = ModelDescriptor(
            id = MiMoModelIds.VOICE_DESIGN,
            displayName = "MiMo v2.5 Voice Design",
            capabilities = setOf(ModelCapability.VOICE_DESIGN, ModelCapability.STYLE_INSTRUCTION),
            supportedVoiceKinds = setOf(VoiceKind.DESIGN),
            supportedFormats = setOf(AudioContainer.RAW_PCM, AudioContainer.WAV, AudioContainer.MP3),
            streaming = StreamingSemantics.COMPATIBILITY_STREAM,
            requiredConfigKeys = setOf("apiKey", "voiceDescription"),
        ),
        forbidsBuiltInVoice = true,
    )

    val voiceClone = MiMoModelProfile(
        descriptor = ModelDescriptor(
            id = MiMoModelIds.VOICE_CLONE,
            displayName = "MiMo v2.5 Voice Clone",
            capabilities = setOf(ModelCapability.VOICE_CLONE, ModelCapability.STYLE_INSTRUCTION, ModelCapability.AUDIO_TAGS),
            supportedVoiceKinds = setOf(VoiceKind.CLONE),
            supportedFormats = setOf(AudioContainer.RAW_PCM, AudioContainer.WAV, AudioContainer.MP3),
            streaming = StreamingSemantics.COMPATIBILITY_STREAM,
            requiredConfigKeys = setOf("apiKey", "voiceSample"),
        ),
        forbidsBuiltInVoice = true,
    )

    fun forModel(modelId: String): MiMoModelProfile? = when (modelId) {
        MiMoModelIds.BUILT_IN -> builtIn
        MiMoModelIds.VOICE_DESIGN -> voiceDesign
        MiMoModelIds.VOICE_CLONE -> voiceClone
        else -> null
    }
}

class MiMoVoiceCatalog(
    private val defaultAliasAvailable: Boolean = true,
) {
    private val generation = AtomicLong(0)

    fun snapshot(): com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot {
        val voices = fixedBuiltInVoices()
        return com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot(
            generation = generation.incrementAndGet(),
            sourceVersion = SOURCE_VERSION,
            voices = voices.toList(),
            defaultVoiceId = DEFAULT_VOICE_ID,
            parameterSchemaVersion = PARAMETER_SCHEMA_VERSION,
        )
    }

    fun builtInVoices(): List<VoiceDefinition> {
        return fixedBuiltInVoices()
    }

    fun voiceFor(profile: MiMoVoiceProfile, locale: Locale = Locale.SIMPLIFIED_CHINESE): VoiceDefinition? {
        if (profile.validationErrors().isNotEmpty()) return null
        return when (profile.kind) {
            VoiceKind.BUILT_IN -> voice(
                requestVoiceId = profile.voiceId.orEmpty(),
                locale = locale,
                profileId = profile.id,
            )
            VoiceKind.DESIGN -> VoiceDefinition(
                providerId = "mimo",
                modelId = profile.modelId,
                profileId = profile.id,
                kind = VoiceKind.DESIGN,
                requestVoiceId = profile.id,
                displayName = profile.displayName,
                locale = locale,
                description = profile.voiceDescription.orEmpty(),
                requiresUserResource = true,
            )
            VoiceKind.CLONE -> VoiceDefinition(
                providerId = "mimo",
                modelId = profile.modelId,
                profileId = profile.id,
                kind = VoiceKind.CLONE,
                requestVoiceId = profile.id,
                displayName = profile.displayName,
                locale = locale,
                requiresUserResource = true,
            )
        }
    }

    private fun voice(requestVoiceId: String, locale: Locale, profileId: String = "builtin-$requestVoiceId") =
        VoiceDefinition(
            providerId = "mimo",
            modelId = MiMoModelIds.BUILT_IN,
            profileId = profileId,
            kind = VoiceKind.BUILT_IN,
            requestVoiceId = requestVoiceId,
            displayName = requestVoiceId,
            locale = locale,
        )

    private fun fixedBuiltInVoices(): List<VoiceDefinition> {
        val chinese = listOf("冰糖", "茉莉", "苏打", "白桦")
        val english = listOf("Mia", "Chloe", "Milo", "Dean")
        val result = listOf(voice(DEFAULT_VOICE_ID, Locale.SIMPLIFIED_CHINESE))
        return (result + chinese.map { voice(it, Locale.SIMPLIFIED_CHINESE) } +
            english.map { voice(it, Locale.US) }).toList()
    }

    companion object {
        const val DEFAULT_VOICE_ID = "mimo_default"
        const val SOURCE_VERSION = "mimo-built-in-voices-v1"
        const val PARAMETER_SCHEMA_VERSION = "mimo-tts-params-v1"
    }
}
