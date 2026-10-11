package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSynthesisOutcomeTest {
    @Test
    fun outcomeStatesAreMutuallyExclusive() {
        val state = EngineSynthesisOutcomeState()

        assertTrue(state.success())
        assertFalse(state.failure())
        assertFalse(state.stopped())
    }
}
