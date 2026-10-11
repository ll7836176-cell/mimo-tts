package com.jingyue.mimotts.provider.mimo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoRequestComposerTest {
    @Test
    fun `built in request carries selected voice assistant text and pcm stream`() {
        val result = MiMoRequestComposer().compose(
            MiMoSynthesisInput(
                endpoint = "https://api.example.test/v1/chat/completions",
                apiKey = "secret",
                profile = MiMoVoiceProfile.builtIn("bingtang", "冰糖"),
                text = "你好",
                instructions = listOf("温柔地朗读"),
            ),
        )

        assertTrue(result.isSuccess)
        val request = result.getOrThrow()
        assertTrue(request.body.contains("\"voice\":\"冰糖\""))
        assertTrue(request.body.contains("\"role\":\"assistant\""))
        assertTrue(request.body.contains("\"format\":\"pcm16\""))
        assertTrue(request.body.contains("\"stream\":true"))
        assertFalse(request.body.contains("secret"))
        assertEquals("secret", request.headers["api-key"])
    }

    @Test
    fun `design without description fails before request creation`() {
        val result = MiMoRequestComposer().compose(
            MiMoSynthesisInput(
                endpoint = "https://api.example.test/v1/chat/completions",
                apiKey = "secret",
                profile = MiMoVoiceProfile.design("design", description = ""),
                text = "你好",
            ),
        )

        assertTrue(result.isFailure)
    }
}
