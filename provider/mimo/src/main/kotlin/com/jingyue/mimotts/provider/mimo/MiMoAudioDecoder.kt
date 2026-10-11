package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.AudioContainer
import com.jingyue.mimotts.core.spi.AudioEncoding
import com.jingyue.mimotts.core.spi.AudioSpec
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(
    val pcm16: ByteArray,
    val spec: AudioSpec,
)

class MiMoAudioDecoder {
    fun decode(
        bytes: ByteArray,
        format: String,
        sampleRate: Int,
        channelCount: Int,
    ): DecodedAudio {
        require(bytes.isNotEmpty()) { "Audio data is empty" }
        return when (format.lowercase()) {
            "pcm", "pcm16", "raw_pcm" -> {
                require(sampleRate > 0) { "Raw PCM response must include sample rate" }
                val channels = channelCount.coerceAtLeast(1)
                require(bytes.size % (2 * channels) == 0) {
                    "Raw PCM response ends with a partial frame"
                }
                DecodedAudio(
                    pcm16 = bytes,
                    spec = AudioSpec(sampleRate, AudioEncoding.PCM_16, channels, AudioContainer.RAW_PCM),
                )
            }
            "wav", "wave" -> decodeWav(bytes)
            "mp3", "mpeg" -> decodeMp3(bytes)
            else -> error("Unsupported MiMo audio format: $format")
        }
    }

    private fun decodeWav(bytes: ByteArray): DecodedAudio {
        require(bytes.size >= 44) { "WAV header is truncated" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "Invalid WAV header" }
        var cursor = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataStart = -1
        var dataLength = 0
        while (cursor + 8 <= bytes.size) {
            val chunkId = ascii(bytes, cursor, 4)
            val chunkLength = intLE(bytes, cursor + 4)
            val chunkStart = cursor + 8
            require(chunkLength >= 0 && chunkStart + chunkLength <= bytes.size) { "Invalid WAV chunk" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkLength >= 16) { "WAV fmt chunk is truncated" }
                    require(shortLE(bytes, chunkStart) == 1) { "Only PCM WAV is supported" }
                    channels = shortLE(bytes, chunkStart + 2)
                    sampleRate = intLE(bytes, chunkStart + 4)
                    bits = shortLE(bytes, chunkStart + 14)
                }
                "data" -> {
                    dataStart = chunkStart
                    dataLength = chunkLength
                }
            }
            cursor = chunkStart + chunkLength + (chunkLength and 1)
        }
        require(sampleRate > 0 && channels > 0 && bits == 16 && dataStart >= 0) { "Unsupported WAV format" }
        require(dataLength % (2 * channels) == 0) { "WAV data ends with a partial frame" }
        return DecodedAudio(
            pcm16 = bytes.copyOfRange(dataStart, dataStart + dataLength),
            spec = AudioSpec(sampleRate, AudioEncoding.PCM_16, channels, AudioContainer.RAW_PCM),
        )
    }

    private fun decodeMp3(bytes: ByteArray): DecodedAudio {
        val output = ByteArrayOutputStream()
        var sampleRate = 0
        var channels = 0
        val bitstream = Bitstream(ByteArrayInputStream(bytes))
        val decoder = Decoder()
        try {
            while (true) {
                val header: Header = bitstream.readFrame() ?: break
                val buffer = decoder.decodeFrame(header, bitstream) as SampleBuffer
                sampleRate = buffer.sampleFrequency
                channels = buffer.channelCount
                for (index in 0 until buffer.bufferLength) {
                    val sample = buffer.buffer[index].toInt()
                    output.write(sample and 0xff)
                    output.write((sample shr 8) and 0xff)
                }
                bitstream.closeFrame()
            }
        } finally {
            runCatching { bitstream.close() }
        }
        require(output.size() > 0 && sampleRate > 0 && channels > 0) { "MP3 contains no decodable audio" }
        return DecodedAudio(
            pcm16 = output.toByteArray(),
            spec = AudioSpec(sampleRate, AudioEncoding.PCM_16, channels, AudioContainer.RAW_PCM),
        )
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun shortLE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun intLE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
