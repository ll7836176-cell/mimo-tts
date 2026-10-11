package com.github.jing332.tts_server_android.model.speech.tts

/** Compatibility interpretation for the existing LocalTTS persisted integers. */
object LocalTtsParameterPolicy {
    const val FOLLOW_SYSTEM = 0

    fun rateMultiplier(value: Int): Float = normalize(value / 50f, 0.25f, 3.0f)

    fun pitchMultiplier(value: Int): Float = normalize(value / 100f, 0.25f, 2.0f)

    fun rateMultiplierFromSystem(sysRate: Int): Float = normalize(sysRate / 20f, 0.25f, 3.0f)

    fun pitchMultiplierFromSystem(sysPitch: Int): Float = normalize((sysPitch + 100) / 100f, 0.25f, 2.0f)

    fun normalize(value: Float, min: Float, max: Float): Float =
        if (!value.isFinite() || value <= 0f) 1.0f else value.coerceIn(min, max)
}
