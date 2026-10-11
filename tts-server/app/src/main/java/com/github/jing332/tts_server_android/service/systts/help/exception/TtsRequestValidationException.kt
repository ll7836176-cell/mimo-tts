package com.github.jing332.tts_server_android.service.systts.help.exception

import com.github.jing332.tts_server_android.model.speech.tts.TtsServerSafeErrorCode

/** A terminal, non-retryable validation failure carrying only a stable safe code. */
class TtsRequestValidationException(
    val code: TtsServerSafeErrorCode,
) : TtsManagerException(message = code.wireValue)
