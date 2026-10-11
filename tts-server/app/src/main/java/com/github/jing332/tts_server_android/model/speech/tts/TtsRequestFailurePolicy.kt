package com.github.jing332.tts_server_android.model.speech.tts

import android.speech.tts.TextToSpeech
import com.github.jing332.tts_server_android.service.systts.help.exception.TtsRequestValidationException

object TtsRequestFailurePolicy {
    fun shouldRetry(outcome: EngineSynthesisOutcome): Boolean = when (outcome) {
        is EngineSynthesisOutcome.Failure -> outcome.category != EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
        EngineSynthesisOutcome.Stopped -> false
        is EngineSynthesisOutcome.Success -> false
    }

    fun shouldStandby(outcome: EngineSynthesisOutcome): Boolean = shouldRetry(outcome)

    fun toTerminalOutcome(exception: TtsRequestValidationException): EngineSynthesisOutcome.Failure =
        EngineSynthesisOutcome.Failure(
            category = EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST,
            androidErrorCode = TextToSpeech.ERROR_INVALID_REQUEST,
            cause = exception.code.wireValue,
        )
}
