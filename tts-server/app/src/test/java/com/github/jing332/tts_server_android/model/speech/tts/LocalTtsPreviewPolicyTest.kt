package com.github.jing332.tts_server_android.model.speech.tts

import com.github.jing332.tts_server_android.model.speech.ITextToSpeechSynthesizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalTtsPreviewPolicyTest {
    @Test
    fun `maps direct playback and audio success distinctly`() {
        assertEquals(
            LocalTtsPreviewResult.DirectPlayCompleted,
            LocalTtsPreviewPolicy.map(EngineSynthesisOutcome.Success(direct = true)),
        )
        val audio = ITextToSpeechSynthesizer.AudioResult(bytes = byteArrayOf(1, 2))
        val result = LocalTtsPreviewPolicy.map(EngineSynthesisOutcome.Success(audio = audio))
        assertSame(audio, (result as LocalTtsPreviewResult.AudioReady).audio)
    }

    @Test
    fun `preserves engine failures and explicit stops`() {
        assertEquals(
            LocalTtsPreviewResult.Failed("engine_init_failed"),
            LocalTtsPreviewPolicy.map(
                EngineSynthesisOutcome.Failure(
                    EngineSynthesisOutcome.FailureCategory.INITIALIZATION,
                    cause = "engine_init_failed",
                ),
            ),
        )
        assertEquals(
            LocalTtsPreviewResult.Stopped,
            LocalTtsPreviewPolicy.map(EngineSynthesisOutcome.Stopped),
        )
    }
}
