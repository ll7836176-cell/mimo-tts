package com.jingyue.mimotts.provider.mimo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MiMoResponseParserTest {
    @Test
    fun `sse parser joins multiline data and ignores event metadata`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(6, 7))
        val events = MiMoResponseParser().parseSse(
            listOf(
                "event: audio",
                "id: 7",
                "data: {\"audio\":{",
                "data: \"data\":\"$encoded\",\"format\":\"pcm16\"}}",
                "",
                "retry: 1000",
                "data: [DONE]",
            ),
        )

        assertEquals(2, events.size)
        assertArrayEquals(byteArrayOf(6, 7), (events[0] as MiMoResponseEvent.Audio).bytes)
        assertTrue(events[1] is MiMoResponseEvent.Done)
    }

    @Test
    fun `sse parser accepts delta audio and done marker`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val events = MiMoResponseParser().parseSse(
            listOf(
                "data: {\"choices\":[{\"delta\":{\"audio\":null}}]}",
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"$encoded\",\"format\":\"pcm16\",\"sample_rate\":22050}}}]}" ,
                "data: [DONE]",
            ),
        )

        assertEquals(2, events.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), (events[0] as MiMoResponseEvent.Audio).bytes)
        assertTrue(events[1] is MiMoResponseEvent.Done)
    }

    @Test
    fun `batch parser accepts message audio`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8))
        val events = MiMoResponseParser().parseBatch(
            "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"$encoded\",\"format\":\"pcm16\",\"sample_rate\":16000}}}]}"
        )

        assertEquals(1, events.size)
        assertEquals(16_000, (events.single() as MiMoResponseEvent.Audio).sampleRate)
    }

    @Test
    fun `pcm16 audio defaults to Mimo streaming sample rate when omitted`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(4, 5))
        val event = MiMoResponseParser().parseSse(
            listOf("data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"$encoded\",\"format\":\"pcm16\"}}}]}")
        ).single() as MiMoResponseEvent.Audio

        assertEquals(MiMoResponseParser.DEFAULT_PCM16_SAMPLE_RATE, event.sampleRate)
        assertEquals(1, event.channelCount)
    }
}
