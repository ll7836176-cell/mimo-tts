package com.github.jing332.tts_server_android.ui.systts.edit.local

import java.util.Locale

/** A platform-independent voice entry used while rebasing an engine switch. */
internal data class LocalTtsVoiceOption(
    val name: String,
    val locale: Locale,
)

internal data class LocalTtsSelection(
    val localeTag: String,
    val voiceName: String?,
)

/**
 * Reconciles a persisted local-TTS selection with the catalog of a newly
 * selected Android engine. Persisted locale/voice values are engine-scoped:
 * retaining a value that is absent from the new catalog makes the editor show
 * MissingLocale/MissingVoice and blocks saving forever. A user-triggered
 * engine switch therefore rebases to the closest supported selection.
 */
internal fun rebaseLocalTtsSelection(
    previousLocaleTag: String?,
    previousVoiceName: String?,
    availableLocales: List<Locale>,
    availableVoices: List<LocalTtsVoiceOption>,
    systemLocale: Locale = Locale.getDefault(),
): LocalTtsSelection {
    val locales = availableLocales
        .asSequence()
        .filter { it.language.isNotBlank() }
        .distinctBy { localeKey(it) }
        .sortedBy { it.toLanguageTag() }
        .toList()
    if (locales.isEmpty()) return LocalTtsSelection("", null)

    val voices = availableVoices
        .asSequence()
        .filter { it.name.isNotBlank() && it.locale.language.isNotBlank() }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
        .toList()
    val previous = previousLocaleTag?.let(::localeKey)
    val system = localeKey(systemLocale)

    fun hasVoice(locale: Locale): Boolean = voices.any { voice ->
        localeMatches(localeKey(locale), localeKey(voice.locale))
    }

    fun firstMatch(predicate: (Locale) -> Boolean): Locale? =
        locales.firstOrNull(predicate)

    val selectedLocale = firstMatch { candidate ->
        previous != null && exactLocale(previous, localeKey(candidate)) && hasVoice(candidate)
    } ?: firstMatch { candidate ->
        previous != null && localeMatches(previous, localeKey(candidate)) && hasVoice(candidate)
    } ?: firstMatch { candidate ->
        system != null && exactLocale(system, localeKey(candidate)) && hasVoice(candidate)
    } ?: firstMatch { candidate ->
        system != null && localeMatches(system, localeKey(candidate)) && hasVoice(candidate)
    } ?: firstMatch(::hasVoice)
        ?: firstMatch { candidate -> previous != null && exactLocale(previous, localeKey(candidate)) }
        ?: firstMatch { candidate -> previous != null && localeMatches(previous, localeKey(candidate)) }
        ?: firstMatch { candidate -> system != null && exactLocale(system, localeKey(candidate)) }
        ?: firstMatch { candidate -> system != null && localeMatches(system, localeKey(candidate)) }
        ?: locales.first()

    val selectedKey = localeKey(selectedLocale)
    val matchingVoices = voices.filter { voice ->
        localeMatches(selectedKey, localeKey(voice.locale))
    }
    val retainedVoice = previousVoiceName
        ?.trim()
        ?.takeIf { name -> matchingVoices.any { it.name == name } }
    val selectedVoice = retainedVoice ?: matchingVoices.firstOrNull()?.name

    return LocalTtsSelection(
        localeTag = selectedLocale.toLanguageTag(),
        voiceName = selectedVoice,
    )
}

private data class LocaleKey(
    val language: String,
    val country: String,
)

private val iso3LanguageAliases: Map<String, String> by lazy {
    buildMap {
        Locale.getAvailableLocales().forEach { locale ->
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isNotBlank()) {
                runCatching { put(locale.isO3Language.lowercase(Locale.ROOT), language) }
            }
        }
        // Android TTS bridges commonly expose these ISO 639-2 aliases.
        put("chi", "zh")
        put("zho", "zh")
        put("eng", "en")
    }
}

private val iso3CountryAliases: Map<String, String> by lazy {
    buildMap {
        Locale.getAvailableLocales().forEach { locale ->
            val country = locale.country.uppercase(Locale.ROOT)
            if (country.isNotBlank()) {
                runCatching { put(locale.isO3Country.uppercase(Locale.ROOT), country) }
            }
        }
    }
}

private fun localeKey(locale: Locale): LocaleKey? {
    val language = normalizeLanguage(locale.language)
    if (language.isBlank()) return null
    return LocaleKey(language, normalizeCountry(locale.country))
}

private fun localeKey(raw: String): LocaleKey? {
    val normalized = raw.trim().replace('_', '-')
    if (normalized.isBlank()) return null
    val parsed = Locale.forLanguageTag(normalized)
    val parts = normalized.split('-')
    val language = normalizeLanguage(parsed.language.ifBlank { parts.firstOrNull().orEmpty() })
    if (language.isBlank()) return null
    val countryPart = parts.drop(1).firstOrNull { part ->
        part.length == 2 || part.length == 3
    }.orEmpty()
    val country = normalizeCountry(parsed.country.ifBlank { countryPart })
    return LocaleKey(language, country)
}

private fun normalizeLanguage(language: String): String {
    val value = language.trim().lowercase(Locale.ROOT)
    return iso3LanguageAliases[value] ?: value
}

private fun normalizeCountry(country: String): String {
    val value = country.trim().uppercase(Locale.ROOT)
    return if (value.length == 3) iso3CountryAliases[value] ?: value else value
}

private fun exactLocale(left: LocaleKey?, right: LocaleKey?): Boolean =
    left != null && right != null && left == right

private fun localeMatches(left: LocaleKey?, right: LocaleKey?): Boolean {
    if (left == null || right == null || left.language != right.language) return false
    return left.country.isBlank() || right.country.isBlank() || left.country == right.country
}
