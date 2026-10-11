package com.github.jing332.tts_server_android.model.speech.tts

import android.speech.tts.TextToSpeech

/**
 * Request timeout semantics for the Android-engine bridge.
 *
 * Direct playback has already handed the utterance to a child Android TTS
 * service. Its completion is governed by that utterance's callbacks, not by
 * the network/file request timeout used for audio that tts-server must fetch.
 */
object LocalTtsRequestPolicy {
    fun shouldApplyRequestTimeout(isDirectPlay: Boolean): Boolean = !isDirectPlay

    fun failureCategoryForAndroidError(errorCode: Int): EngineSynthesisOutcome.FailureCategory =
        if (errorCode == TextToSpeech.ERROR_INVALID_REQUEST) {
            EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
        } else {
            EngineSynthesisOutcome.FailureCategory.LISTENER
        }
}
