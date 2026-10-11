package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRequestFailurePolicyTest {
    @Test
    fun `invalid requests do not enter retry or standby`() {
        val invalid = EngineSynthesisOutcome.Failure(
            category = EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST,
        )
        val network = EngineSynthesisOutcome.Failure(
            category = EngineSynthesisOutcome.FailureCategory.TIMEOUT,
        )

        assertFalse(TtsRequestFailurePolicy.shouldRetry(invalid))
        assertFalse(TtsRequestFailurePolicy.shouldStandby(invalid))
        assertTrue(TtsRequestFailurePolicy.shouldRetry(network))
    }
}
