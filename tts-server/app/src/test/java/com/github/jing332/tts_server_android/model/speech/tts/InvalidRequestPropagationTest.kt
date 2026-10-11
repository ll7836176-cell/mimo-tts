package com.github.jing332.tts_server_android.model.speech.tts

import com.github.jing332.tts_server_android.service.systts.help.exception.TtsRequestValidationException
import org.junit.Assert.assertEquals
import org.junit.Test

class InvalidRequestPropagationTest {
    @Test
    fun validationFailureRemainsTerminalTypedOutcome() {
        val outcome = TtsRequestFailurePolicy.toTerminalOutcome(
            TtsRequestValidationException(TtsServerSafeErrorCode.PARAM_CONFLICT),
        )

        assertEquals(EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST, outcome.category)
        assertEquals(TtsServerSafeErrorCode.PARAM_CONFLICT.wireValue, outcome.cause)
    }
}
