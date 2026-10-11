package com.github.jing332.tts_server_android.ui.systts.edit.local

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTtsEngineSwitchPolicyTest {
    @Test
    fun switchingEngineKeepsLocaleAndReplacesVoiceThatIsMissingFromTargetCatalog() {
        val selection = rebaseLocalTtsSelection(
            previousLocaleTag = "zh-CN",
            previousVoiceName = "old-engine-voice",
            availableLocales = listOf(Locale.forLanguageTag("zh-CN")),
            availableVoices = listOf(
                LocalTtsVoiceOption("new-engine-voice", Locale.forLanguageTag("zh-CN")),
            ),
            systemLocale = Locale.US,
        )

        assertEquals("zh-CN", selection.localeTag)
        assertEquals("new-engine-voice", selection.voiceName)
    }

    @Test
    fun switchingEngineFallsBackToTheSystemLocaleWhenPreviousLocaleIsUnsupported() {
        val selection = rebaseLocalTtsSelection(
            previousLocaleTag = "ja-JP",
            previousVoiceName = "japanese-voice",
            availableLocales = listOf(
                Locale.forLanguageTag("en-US"),
                Locale.forLanguageTag("zh-CN"),
            ),
            availableVoices = listOf(
                LocalTtsVoiceOption("english-voice", Locale.forLanguageTag("en-US")),
                LocalTtsVoiceOption("chinese-voice", Locale.forLanguageTag("zh-CN")),
            ),
            systemLocale = Locale.US,
        )

        assertEquals("en-US", selection.localeTag)
        assertEquals("english-voice", selection.voiceName)
    }

    @Test
    fun switchingEnginePreservesAStillValidVoice() {
        val selection = rebaseLocalTtsSelection(
            previousLocaleTag = "zho-CN",
            previousVoiceName = "shared-voice",
            availableLocales = listOf(Locale.forLanguageTag("zh-CN")),
            availableVoices = listOf(
                LocalTtsVoiceOption("shared-voice", Locale.forLanguageTag("zho-CN")),
                LocalTtsVoiceOption("other-voice", Locale.forLanguageTag("zh-CN")),
            ),
            systemLocale = Locale.US,
        )

        assertEquals("zh-CN", selection.localeTag)
        assertEquals("shared-voice", selection.voiceName)
    }

    @Test
    fun anEngineWithoutLocalesClearsTheInvalidPersistedSelection() {
        assertEquals(
            LocalTtsSelection(localeTag = "", voiceName = null),
            rebaseLocalTtsSelection(
                previousLocaleTag = "zh-CN",
                previousVoiceName = "stale",
                availableLocales = emptyList(),
                availableVoices = emptyList(),
                systemLocale = Locale.US,
            ),
        )
    }
}
