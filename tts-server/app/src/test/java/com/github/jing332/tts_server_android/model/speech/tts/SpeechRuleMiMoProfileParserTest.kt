package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRuleMiMoProfileParserTest {
    private val parser = SpeechRuleMiMoProfileParser()

    @Test
    fun `parses the versioned voice and style profile`() {
        val result = parser.parse(
            mapOf(
                "schemaVersion" to 1,
                "voice" to "冰糖",
                "style" to "温柔地朗读",
            ),
        )

        assertTrue(result is SpeechRuleMiMoProfileParser.Result.Success)
        val profile = (result as SpeechRuleMiMoProfileParser.Result.Success).profile
        assertEquals(1, profile.schemaVersion)
        assertEquals("冰糖", profile.voice)
        assertEquals("温柔地朗读", profile.style)
    }

    @Test
    fun `unknown keys are rejected with a safe code`() {
        val result = parser.parse(mapOf("schemaVersion" to 1, "voice" to "冰糖", "leak" to "secret"))

        assertEquals(
            TtsServerSafeErrorCode.PARAM_UNKNOWN_KEY,
            (result as SpeechRuleMiMoProfileParser.Result.Failure).code,
        )
    }

    @Test
    fun `unsupported version and oversized style are rejected`() {
        val versionResult = parser.parse(mapOf("schemaVersion" to 2))
        assertEquals(
            TtsServerSafeErrorCode.REQUEST_UNSUPPORTED_PROTOCOL,
            (versionResult as SpeechRuleMiMoProfileParser.Result.Failure).code,
        )

        val styleResult = parser.parse(mapOf("schemaVersion" to 1, "style" to "é".repeat(600)))
        assertEquals(
            TtsServerSafeErrorCode.STYLE_TOO_LONG,
            (styleResult as SpeechRuleMiMoProfileParser.Result.Failure).code,
        )
    }
}
