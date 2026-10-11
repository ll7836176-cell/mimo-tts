package com.jingyue.mimotts.core.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PlaybackParameters(
    val speechRate: Float,
    val pitch: Float,
)

object AudioPlaybackPolicy {
    fun normalizeAndroidParameters(speechRate: Float, pitch: Float): PlaybackParameters {
        return PlaybackParameters(
            speechRate = speechRate.coerceIn(0.1f, 5f),
            pitch = pitch.coerceIn(0.1f, 5f),
        )
    }

    /** Provider requests stay neutral; rate/pitch are applied exactly once by the audio sink. */
    fun providerParameters(): PlaybackParameters = PlaybackParameters(1f, 1f)

    fun fromAndroidPercent(speechRatePercent: Int, pitchPercent: Int): PlaybackParameters =
        normalizeAndroidParameters(speechRatePercent / 100f, pitchPercent / 100f)
}

/**
 * Small PCM16 time-stretching transform used at the Android sink boundary. Provider requests stay neutral;
 * this is the only place where caller playback parameters alter samples.
 */
object Pcm16PlaybackTransform {
    /**
     * Applies both parameters to a complete PCM buffer. The Android service uses
     * [Pcm16PlaybackProcessor] directly so that streaming chunks share one Sonic
     * state; this helper keeps the core transform convenient to test.
     */
    fun apply(
        bytes: ByteArray,
        channelCount: Int,
        parameters: PlaybackParameters,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): ByteArray {
        if (bytes.isEmpty()) return bytes
        val output = ByteArrayOutputStream()
        val frameBytes = channelCount * BYTES_PER_SAMPLE
        val processor = Pcm16PlaybackProcessor(
            speechRate = parameters.speechRate,
            pitch = parameters.pitch,
            maxOutputBytes = maxOf(bytes.size, frameBytes),
            output = { chunk ->
                output.write(chunk)
                true
            },
        )
        check(processor.accept(bytes, sampleRate, PCM_16_BIT, channelCount)) {
            processor.failureReason ?: "pcm_transform_failed"
        }
        check(processor.finish()) {
            processor.failureReason ?: "pcm_transform_failed"
        }
        return output.toByteArray()
    }

    private const val DEFAULT_SAMPLE_RATE = 24_000
    private const val PCM_16_BIT = 2
    private const val BYTES_PER_SAMPLE = 2
}

/**
 * Stateful PCM16 playback transform. Sonic performs time stretching and pitch
 * shifting independently; a plain resampler cannot do that because changing
 * the sample step inevitably changes pitch.
 */
class Pcm16PlaybackProcessor(
    private val speechRate: Float,
    private val pitch: Float = 1f,
    private val output: (ByteArray) -> Boolean,
    private val maxOutputBytes: Int = 4096,
) {
    private var sampleRate: Int? = null
    private var audioFormat: Int? = null
    private var channelCount: Int? = null
    private var pendingBytes = ByteArray(0)
    private var sonic: Sonic? = null
    private var cancelled = false
    private var finished = false

    var failureReason: String? = null
        private set

    private val passthrough: Boolean = speechRate == 1f && pitch == 1f

    fun accept(bytes: ByteArray, sampleRate: Int, audioFormat: Int, channelCount: Int): Boolean {
        if (cancelled || finished || failureReason != null) return false
        if (audioFormat != PCM_16_BIT || sampleRate <= 0 || channelCount <= 0) {
            return fail("unsupported_pcm_format")
        }

        if (this.sampleRate == null) {
            this.sampleRate = sampleRate
            this.audioFormat = audioFormat
            this.channelCount = channelCount
            if (!passthrough) {
                sonic = Sonic(sampleRate, channelCount).also {
                    it.setSpeed(speechRate)
                    it.setPitch(pitch)
                }
            }
        } else if (
            this.sampleRate != sampleRate ||
            this.audioFormat != audioFormat ||
            this.channelCount != channelCount
        ) {
            return fail("pcm_format_changed")
        }

        val frameBytes = channelCount * BYTES_PER_SAMPLE
        if (maxOutputBytes < frameBytes) return fail("max_buffer_too_small")
        val alignedMaxOutputBytes = maxOutputBytes - maxOutputBytes % frameBytes
        if (alignedMaxOutputBytes < frameBytes) return fail("max_buffer_too_small")

        val combined = ByteArray(pendingBytes.size + bytes.size)
        pendingBytes.copyInto(combined)
        bytes.copyInto(combined, pendingBytes.size)
        val completeBytes = combined.size - combined.size % frameBytes
        pendingBytes = combined.copyOfRange(completeBytes, combined.size)
        if (completeBytes == 0) return true

        if (passthrough) {
            emitChunked(combined.copyOf(completeBytes), alignedMaxOutputBytes)
        } else {
            val frames = completeBytes / frameBytes
            val samples = ShortArray(frames * channelCount)
            val buffer = ByteBuffer.wrap(combined, 0, completeBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (index in samples.indices) samples[index] = buffer.short
            sonic?.writeShortToStream(samples, frames)
            drainSonic(alignedMaxOutputBytes)
        }
        return failureReason == null
    }

    fun finish(): Boolean {
        if (cancelled) return false
        if (finished) return failureReason == null
        finished = true
        if (pendingBytes.isNotEmpty()) return fail("odd_terminating_pcm_bytes")
        if (!passthrough) {
            sonic?.flushStream()
            val channels = channelCount ?: return failureReason == null
            val frameBytes = channels * BYTES_PER_SAMPLE
            val alignedMaxOutputBytes = maxOutputBytes - maxOutputBytes % frameBytes
            if (alignedMaxOutputBytes < frameBytes) return fail("max_buffer_too_small")
            drainSonic(alignedMaxOutputBytes)
        }
        return failureReason == null
    }

    fun cancel() {
        cancelled = true
        pendingBytes = ByteArray(0)
        sonic = null
    }

    private fun drainSonic(alignedMaxOutputBytes: Int) {
        val engine = sonic ?: return
        val channels = channelCount ?: return
        val samples = ShortArray(1024 * channels)
        while (engine.samplesAvailable() > 0) {
            val frames = engine.readShortFromStream(samples, 1024)
            if (frames <= 0) break
            val bytes = ByteBuffer.allocate(frames * channels * BYTES_PER_SAMPLE)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until frames * channels) bytes.putShort(samples[index])
            if (!emitChunked(bytes.array(), alignedMaxOutputBytes)) return
        }
    }

    private fun emitChunked(bytes: ByteArray, alignedMaxOutputBytes: Int): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(alignedMaxOutputBytes, bytes.size - offset)
            if (size <= 0 || size % frameBytes() != 0) return fail("output_frame_alignment")
            if (!output(bytes.copyOfRange(offset, offset + size))) {
                return fail("callback_rejected")
            }
            offset += size
        }
        return true
    }

    private fun frameBytes(): Int = (channelCount ?: 1) * BYTES_PER_SAMPLE

    private fun fail(reason: String): Boolean {
        failureReason = reason
        pendingBytes = ByteArray(0)
        sonic = null
        return false
    }

    private companion object {
        const val PCM_16_BIT = 2
        const val BYTES_PER_SAMPLE = 2
    }
}
