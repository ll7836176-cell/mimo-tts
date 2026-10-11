package com.github.jing332.tts_server_android.model.speech.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTtsRequestPolicyTest {
    @Test
    fun directPlaybackDoesNotUseAudioFetchTimeout() {
        assertFalse(LocalTtsRequestPolicy.shouldApplyRequestTimeout(isDirectPlay = true))
        assertTrue(LocalTtsRequestPolicy.shouldApplyRequestTimeout(isDirectPlay = false))
    }

    @Test
    fun invalidAndroidCallbackIsAValidationFailure() {
        assertEquals(
            EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST,
            LocalTtsRequestPolicy.failureCategoryForAndroidError(TextToSpeech.ERROR_INVALID_REQUEST),
        )
        assertEquals(
            EngineSynthesisOutcome.FailureCategory.LISTENER,
            LocalTtsRequestPolicy.failureCategoryForAndroidError(TextToSpeech.ERROR_SYNTHESIS),
        )
    }
}
