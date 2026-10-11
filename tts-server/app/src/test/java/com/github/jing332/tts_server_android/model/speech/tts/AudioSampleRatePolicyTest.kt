package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSampleRatePolicyTest {
    @Test
    fun `accepts supported sample rate range boundaries`() {
        assertEquals(8_000, AudioSampleRatePolicy.parse("8000"))
        assertEquals(192_000, AudioSampleRatePolicy.parse("192000"))
        assertEquals(16_000, AudioSampleRatePolicy.parse(" 16000 "))
    }

    @Test
    fun `rejects empty malformed overflow and out of range sample rates`() {
        listOf("", "   ", "NaN", "2147483648", "7999", "192001").forEach {
            assertNull(AudioSampleRatePolicy.parse(it))
        }
    }
}
