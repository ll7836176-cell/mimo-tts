package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTtsRequestStateTest {
    @Test
    fun onlyMatchingUtteranceAndGenerationCanCompleteRequest() {
        val state = LocalTtsRequestState("request-a", generation = 4)

        assertFalse(state.accepts("old", candidateGeneration = 4))
        assertFalse(state.accepts("request-a", candidateGeneration = 3))
        assertTrue(state.accepts("request-a", candidateGeneration = 4))
    }

    @Test
    fun terminalOutcomeIsExactOnceAndStopWinsBeforeLateDone() {
        val state = LocalTtsRequestState("request-a", generation = 4)

        assertTrue(state.stop())
        assertFalse(state.done())
        assertFalse(state.error())
    }
}
