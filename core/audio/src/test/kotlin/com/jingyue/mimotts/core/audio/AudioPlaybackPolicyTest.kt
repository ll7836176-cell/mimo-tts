package com.jingyue.mimotts.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackPolicyTest {
    @Test
    fun `android percentages are normalized once and provider remains neutral`() {
        assertEquals(0.5f, AudioPlaybackPolicy.fromAndroidPercent(50, 150).speechRate)
        assertEquals(1.5f, AudioPlaybackPolicy.fromAndroidPercent(50, 150).pitch)
        assertEquals(PlaybackParameters(1f, 1f), AudioPlaybackPolicy.providerParameters())
    }

    @Test
    fun `pcm transform changes samples only at playback boundary`() {
        val source = byteArrayOf(0, 0, 1, 0, 2, 0, 3, 0)
        val transformed = Pcm16PlaybackTransform.apply(source, channelCount = 1, PlaybackParameters(2f, 1f))

        assertNotEquals(source.toList(), transformed.toList())
        assertEquals(4, transformed.size)
    }

    @Test
    fun `changing speed alone preserves the source pitch`() {
        val sampleRate = 24_000
        val sourceFrequency = 440
        val source = sinePcm(sampleRate, sourceFrequency, sampleRate)

        val transformed = Pcm16PlaybackTransform.apply(
            source,
            channelCount = 1,
            PlaybackParameters(speechRate = 2f, pitch = 1f),
        )

        val measuredFrequency = estimateFrequency(transformed, sampleRate)
        assertTrue(
            "speed-only transform shifted $sourceFrequency Hz to about $measuredFrequency Hz",
            kotlin.math.abs(measuredFrequency - sourceFrequency) <= 35,
        )
    }

    @Test
    fun `changing pitch alone changes the source pitch`() {
        val sampleRate = 24_000
        val sourceFrequency = 440
        val source = sinePcm(sampleRate, sourceFrequency, sampleRate)

        val transformed = Pcm16PlaybackTransform.apply(
            source,
            channelCount = 1,
            PlaybackParameters(speechRate = 1f, pitch = 2f),
        )

        val measuredFrequency = estimateFrequency(transformed, sampleRate)
        assertTrue(
            "pitch-only transform did not shift $sourceFrequency Hz to about 880 Hz: $measuredFrequency Hz",
            kotlin.math.abs(measuredFrequency - sourceFrequency * 2) <= 70,
        )
    }

    private fun sinePcm(sampleRate: Int, frequency: Int, frames: Int): ByteArray {
        val result = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (kotlin.math.sin(2.0 * Math.PI * frequency * frame / sampleRate) * 20_000)
                .toInt()
                .toShort()
            result[frame * 2] = value.toByte()
            result[frame * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        return result
    }

    private fun estimateFrequency(pcm: ByteArray, sampleRate: Int): Int {
        val samples = ShortArray(pcm.size / 2) { index ->
            ((pcm[index * 2].toInt() and 0xff) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()
        }
        val start = samples.size / 10
        val end = samples.size * 9 / 10
        var crossings = 0
        for (index in start + 1 until end) {
            if (samples[index - 1] <= 0 && samples[index] > 0) crossings++
        }
        return (crossings * sampleRate / (end - start)).coerceAtLeast(0)
    }
}
