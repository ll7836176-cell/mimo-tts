package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot
import com.jingyue.mimotts.core.spi.VoiceIdCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoVoiceResolverTest {
    private val catalog = MiMoVoiceCatalog()

    @Test
    fun `snapshot has one fixed default and eight selectable built in voices`() {
        val snapshot = catalog.snapshot()

        assertEquals("mimo-built-in-voices-v1", snapshot.sourceVersion)
        assertEquals("mimo_default", snapshot.defaultVoiceId)
        assertEquals(9, snapshot.voices.size)
        assertEquals(1, snapshot.voices.count { it.requestVoiceId == snapshot.defaultVoiceId })
    }

    @Test
    fun `resolver accepts advertised and encoded identifiers but rejects unknown ids`() {
        val snapshot: MiMoVoiceCatalogSnapshot = catalog.snapshot()
        val voice = snapshot.voices.first { it.requestVoiceId == "冰糖" }
        val resolver = MiMoVoiceResolver()

        assertTrue(resolver.resolve(snapshot, FieldState.Present("冰糖")).isSuccess)
        assertTrue(resolver.resolve(snapshot, FieldState.Present("builtin-冰糖")).isSuccess)
        assertTrue(resolver.resolve(snapshot, FieldState.Present(VoiceIdCodec.encode(voice))).isSuccess)
        assertEquals(
            MiMoSafeErrorCode.VOICE_INVALID,
            resolver.resolve(snapshot, FieldState.Present("unknown")).errorCode,
        )
    }

    @Test
    fun `absent override resolves explicit default without first item fallback`() {
        val snapshot = catalog.snapshot().copy(
            voices = catalog.snapshot().voices.filterNot { it.requestVoiceId == "mimo_default" },
            defaultVoiceId = "mimo_default",
        )

        assertEquals(
            MiMoSafeErrorCode.VOICE_INVALID,
            MiMoVoiceResolver().resolve(snapshot, FieldState.Absent).errorCode,
        )
    }

    @Test
    fun `framework and bundle voices must resolve to the same catalog voice`() {
        val snapshot = catalog.snapshot()
        val resolver = MiMoVoiceResolver()

        assertEquals(
            MiMoSafeErrorCode.VOICE_CONFLICT,
            resolver.resolve(
                snapshot,
                FieldState.Present("冰糖"),
                VoiceIdCodec.toTtsServerName(snapshot.voices.first { it.requestVoiceId == "Mia" }),
            ).errorCode,
        )
        assertTrue(
            resolver.resolve(
                snapshot,
                FieldState.Present("冰糖"),
                VoiceIdCodec.toTtsServerName(snapshot.voices.first { it.requestVoiceId == "冰糖" }),
            ).isSuccess,
        )
    }

    @Test
    fun `explicit bundle voice survives an unknown bridge default voice`() {
        val snapshot = catalog.snapshot()

        assertTrue(
            MiMoVoiceResolver().resolve(
                snapshot = snapshot,
                override = FieldState.Present("白桦"),
                frameworkVoiceName = "DEFAULT_默认",
            ).isSuccess,
        )
    }

    @Test
    fun `catalog metadata mismatch is rejected before voice resolution`() {
        val snapshot = catalog.snapshot().copy(
            voices = catalog.snapshot().voices.map { voice ->
                if (voice.requestVoiceId == "冰糖") {
                    voice.copy(modelId = MiMoModelIds.VOICE_CLONE)
                } else {
                    voice
                }
            },
        )

        assertEquals(
            MiMoSafeErrorCode.VOICE_INVALID,
            MiMoVoiceResolver().resolve(snapshot, FieldState.Present("冰糖")).errorCode,
        )
    }
}
