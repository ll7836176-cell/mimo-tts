package com.github.jing332.tts_server_android.ui.systts.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTestTextPolicyTest {
    @Test
    fun `uses fallback text when the preview input is blank`() {
        assertEquals("fallback", TtsTestTextPolicy.effectiveText("   ", "fallback"))
    }

    @Test
    fun `keeps the entered preview text`() {
        assertEquals("hello", TtsTestTextPolicy.effectiveText("hello", "fallback"))
    }
}
