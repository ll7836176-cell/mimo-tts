package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.ProviderConfigSnapshot
import com.jingyue.mimotts.core.spi.RequestSource
import com.jingyue.mimotts.core.spi.SynthesisEvent
import com.jingyue.mimotts.core.spi.SynthesisRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiMoProviderTest {
    @Test
    fun `missing api key fails before transport is opened`() {
        val transport = RecordingTransport()
        val events = mutableListOf<SynthesisEvent>()
        val finished = CountDownLatch(1)
        val request = SynthesisRequest(
            requestId = "request",
            generation = 1,
            attempt = 1,
            originalText = "hello",
            segments = listOf(com.jingyue.mimotts.core.spi.SynthesisSegment("builtin-Mia", "hello")),
            locale = Locale.US,
            voice = null,
            providerId = "mimo",
            modelId = MiMoModelIds.BUILT_IN,
            profileId = "builtin-Mia",
            speechRate = 1f,
            pitch = 1f,
            volume = 1f,
            source = RequestSource.APP_PREVIEW,
            providerConfig = ProviderConfigSnapshot(mapOf("endpoint" to "https://api.example.test")),
            ruleSetId = "default",
            ruleSetVersion = 1,
        )

        MiMoProvider(transport = transport).synthesize(request) {
            events += it
            finished.countDown()
        }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, transport.calls)
        assertTrue(events.single() is SynthesisEvent.Failed)
    }

    private class RecordingTransport : MiMoTransport {
        var calls = 0

        override fun newCall(request: MiMoHttpRequest): MiMoTransportCall {
            calls++
            return object : MiMoTransportCall {
                override fun execute(onLine: (String) -> Unit): MiMoTransportResponse = MiMoTransportResponse(500, null)
                override fun cancel() = Unit
            }
        }
    }
}
