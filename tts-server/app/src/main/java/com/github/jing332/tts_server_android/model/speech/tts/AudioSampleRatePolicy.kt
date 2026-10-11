package com.github.jing332.tts_server_android.model.speech.tts

object AudioSampleRatePolicy {
    const val MIN_SAMPLE_RATE = 8_000
    const val MAX_SAMPLE_RATE = 192_000

    fun parse(value: String?): Int? {
        val sampleRate = value?.trim()?.toIntOrNull() ?: return null
        return sampleRate.takeIf { it in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE }
    }
}
