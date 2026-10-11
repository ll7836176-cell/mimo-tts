package com.jingyue.mimotts.core.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

class VoiceIdCodecTest {
    @Test
    fun `tts server voice name is human readable and locale stable`() {
        val voice = VoiceDefinition(
            providerId = "mimo",
            modelId = "mimo-v2.5-tts",
            profileId = "builtin-bingtang",
            kind = VoiceKind.BUILT_IN,
            requestVoiceId = "冰糖",
            displayName = "冰糖",
            locale = Locale.SIMPLIFIED_CHINESE,
        )

        assertEquals("冰糖::zh-CN", VoiceIdCodec.toTtsServerName(voice))
    }

    @Test
    fun `canonical voice round trips without losing locale or model`() {
        val voice = VoiceDefinition(
            providerId = "mimo",
            modelId = "mimo-v2.5-tts",
            profileId = "builtin-bingtang",
            kind = VoiceKind.BUILT_IN,
            requestVoiceId = "冰糖",
            displayName = "冰糖",
            locale = Locale.SIMPLIFIED_CHINESE,
        )

        val encoded = VoiceIdCodec.encode(voice)
        val decoded = VoiceIdCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(voice.providerId, decoded?.providerId)
        assertEquals(voice.modelId, decoded?.modelId)
        assertEquals(voice.requestVoiceId, decoded?.requestVoiceId)
        assertEquals(voice.locale, decoded?.locale)
    }

    @Test
    fun `legacy tts server voice remains readable`() {
        val decoded = VoiceIdCodec.decode("冰糖::zho-CN")

        assertNotNull(decoded)
        assertEquals(VoiceKind.BUILT_IN, decoded?.kind)
        assertEquals("冰糖", decoded?.requestVoiceId)
        assertEquals(Locale.SIMPLIFIED_CHINESE, decoded?.locale)
    }
}
