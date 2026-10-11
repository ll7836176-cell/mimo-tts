package com.jingyue.mimotts.provider.mimo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class MiMoAudioDecoderTest {
    @Test
    fun `wav decoder reports the sample rate from the container`() {
        val pcm = byteArrayOf(1, 0, 2, 0)
        val decoded = MiMoAudioDecoder().decode(wav(sampleRate = 16_000, pcm = pcm), "wav", 0, 1)

        assertEquals(16_000, decoded.spec.sampleRate)
        assertEquals(1, decoded.spec.channelCount)
        assertArrayEquals(pcm, decoded.pcm16)
    }

    @Test
    fun `raw pcm without response sample rate fails instead of assuming 24 kHz`() {
        assertThrows(IllegalArgumentException::class.java) {
            MiMoAudioDecoder().decode(byteArrayOf(1, 2), "pcm16", sampleRate = 0, channelCount = 1)
        }
    }

    @Test
    fun `raw pcm rejects a partial multi-channel frame`() {
        assertThrows(IllegalArgumentException::class.java) {
            MiMoAudioDecoder().decode(byteArrayOf(1, 2, 3, 4, 5, 6), "pcm16", 24_000, 2)
        }
    }

    private fun wav(sampleRate: Int, pcm: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("RIFF".toByteArray())
        writeInt(output, 36 + pcm.size)
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        writeInt(output, 16)
        writeShort(output, 1)
        writeShort(output, 1)
        writeInt(output, sampleRate)
        writeInt(output, sampleRate * 2)
        writeShort(output, 2)
        writeShort(output, 16)
        output.write("data".toByteArray())
        writeInt(output, pcm.size)
        output.write(pcm)
        return output.toByteArray()
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
        output.write((value shr 16) and 0xff)
        output.write((value shr 24) and 0xff)
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value shr 8) and 0xff)
    }
}
