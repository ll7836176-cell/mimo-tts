package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.ProviderConfigSnapshot
import com.jingyue.mimotts.core.spi.RequestSource
import com.jingyue.mimotts.core.spi.SynthesisEvent
import com.jingyue.mimotts.core.spi.SynthesisRequest
import com.jingyue.mimotts.core.spi.VoiceDefinition
import com.jingyue.mimotts.core.spi.VoiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiMoSynthesisRequestFactoryTest {
    @Test
    fun unsupportedVoiceKindsFailBeforeTransport() {
        val transport = RecordingTransport()
        val events = mutableListOf<SynthesisEvent>()
        val finished = CountDownLatch(1)
        val profile = MiMoVoiceProfile.design("design-profile", "a designed voice")
        val request = SynthesisRequest(
            requestId = "request",
            generation = 1,
            attempt = 1,
            originalText = "hello",
            segments = listOf(
                com.jingyue.mimotts.core.spi.SynthesisSegment("design-profile", "hello"),
            ),
            locale = Locale.US,
            voice = VoiceDefinition(
                providerId = "mimo",
                modelId = MiMoModelIds.VOICE_DESIGN,
                profileId = "design-profile",
                kind = VoiceKind.DESIGN,
                requestVoiceId = "design-profile",
                displayName = "design-profile",
                locale = Locale.US,
            ),
            providerId = "mimo",
            modelId = MiMoModelIds.VOICE_DESIGN,
            profileId = "design-profile",
            speechRate = 1f,
            pitch = 1f,
            volume = 1f,
            source = RequestSource.APP_PREVIEW,
            providerConfig = ProviderConfigSnapshot(
                mapOf(
                    "apiKey" to "secret",
                    "endpoint" to "https://api.example.test/v1/chat/completions",
                ),
            ),
            ruleSetId = "default",
            ruleSetVersion = 1,
        )

        MiMoProvider(
            transport = transport,
            profiles = mapOf(profile.id to profile),
        ).synthesize(request) {
            events += it
            if (it is SynthesisEvent.Failed) finished.countDown()
        }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, transport.calls)
        assertEquals(
            "voice.invalid",
            (events.single() as SynthesisEvent.Failed).error.code,
        )
    }

    private class RecordingTransport : MiMoTransport {
        var calls = 0

        override fun newCall(request: MiMoHttpRequest): MiMoTransportCall {
            calls++
            return object : MiMoTransportCall {
                override fun execute(onLine: (String) -> Unit): MiMoTransportResponse =
                    MiMoTransportResponse(500, null)

                override fun cancel() = Unit
            }
        }
    }
}
