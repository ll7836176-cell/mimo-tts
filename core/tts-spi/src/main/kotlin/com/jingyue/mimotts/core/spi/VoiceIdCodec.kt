package com.jingyue.mimotts.core.spi

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

object VoiceIdCodec {
    private const val PREFIX = "mimotts-v1"

    /**
     * Stable, human-readable Android TTS voice name used by tts-server.
     *
     * tts-server persists Voice.name and later passes it back to setVoice(),
     * so the request voice ID and locale must both remain visible and stable.
     */
    fun toTtsServerName(voice: VoiceDefinition): String =
        "${voice.requestVoiceId}::${voice.locale.toLanguageTag()}"

    fun encode(voice: VoiceDefinition): String {
        return listOf(
            PREFIX,
            encodePart(voice.providerId),
            encodePart(voice.modelId),
            encodePart(voice.profileId),
            voice.kind.name.lowercase(Locale.ROOT),
            encodePart(voice.requestVoiceId),
            encodePart(voice.locale.toLanguageTag()),
        ).joinToString(".")
    }

    fun decode(value: String): VoiceDefinition? {
        val parts = value.split('.')
        if (parts.size == 7 && parts[0] == PREFIX) {
            val kind = parts[4].uppercase(Locale.ROOT).let {
                runCatching { VoiceKind.valueOf(it) }.getOrNull()
            } ?: return null
            val locale = Locale.forLanguageTag(decodePart(parts[6]) ?: return null)
            return VoiceDefinition(
                providerId = decodePart(parts[1]) ?: return null,
                modelId = decodePart(parts[2]) ?: return null,
                profileId = decodePart(parts[3]) ?: return null,
                kind = kind,
                requestVoiceId = decodePart(parts[5]) ?: return null,
                displayName = decodePart(parts[5]) ?: return null,
                locale = locale,
            )
        }

        val legacy = value.split("::", limit = 2)
        if (legacy.size == 2 && legacy[0].isNotBlank()) {
            val locale = when (legacy[1].lowercase(Locale.ROOT)) {
                "zho-cn", "chi-cn", "zh-cn" -> Locale.SIMPLIFIED_CHINESE
                "eng-us", "en-us" -> Locale.US
                else -> Locale.forLanguageTag(legacy[1])
            }
            return VoiceDefinition(
                providerId = "mimo",
                modelId = "mimo-v2.5-tts",
                profileId = "builtin-${legacy[0]}",
                kind = VoiceKind.BUILT_IN,
                requestVoiceId = legacy[0],
                displayName = legacy[0],
                locale = locale,
            )
        }
        return null
    }

    private fun encodePart(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()
}
