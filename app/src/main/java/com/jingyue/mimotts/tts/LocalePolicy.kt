package com.jingyue.mimotts.tts

import android.speech.tts.TextToSpeech
import java.util.Locale

internal object LocalePolicy {
    val supported: List<Locale> = listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)

    fun availability(language: String?, country: String?): Int {
        if (language.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
        val parsed = language.replace('_', '-').takeIf { it.contains('-') }?.let(Locale::forLanguageTag)
        val normalizedLanguage = when ((parsed?.language ?: language).lowercase(Locale.ROOT)) {
            "zho", "chi" -> "zh"
            "eng" -> "en"
            else -> (parsed?.language ?: language).lowercase(Locale.ROOT)
        }
        val candidates = supported.filter { it.language == normalizedLanguage }
        if (candidates.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE
        val normalizedCountry = when (country.uppercase(Locale.ROOT)) {
            "CHN" -> "CN"
            "USA" -> "US"
            else -> country.uppercase(Locale.ROOT)
        }
        return if (candidates.any { it.country.equals(normalizedCountry, true) }) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    fun sampleText(language: String?): String = when (language?.replace('_', '-')?.let(Locale::forLanguageTag)?.language
        ?: language?.lowercase(Locale.ROOT)) {
        "zh", "zho", "chi" -> "这是 MiMo TTS 的语音合成示例。"
        else -> "This is a MiMo TTS speech synthesis sample."
    }
}
