package com.github.jing332.tts_server_android.model.speech.tts

import com.github.jing332.tts_server_android.model.speech.ITextToSpeechSynthesizer

sealed interface LocalTtsPreviewResult {
    object DirectPlayCompleted : LocalTtsPreviewResult
    data class AudioReady(
        val audio: ITextToSpeechSynthesizer.AudioResult,
        val bytes: ByteArray? = null,
        val sampleRate: Int = 0,
    ) : LocalTtsPreviewResult
    data class Failed(val message: String) : LocalTtsPreviewResult
    object Stopped : LocalTtsPreviewResult
}

object LocalTtsPreviewPolicy {
    fun map(outcome: EngineSynthesisOutcome): LocalTtsPreviewResult = when (outcome) {
        is EngineSynthesisOutcome.Success -> when {
            outcome.direct -> LocalTtsPreviewResult.DirectPlayCompleted
            outcome.audio != null -> LocalTtsPreviewResult.AudioReady(outcome.audio)
            else -> LocalTtsPreviewResult.Failed(
                EngineSynthesisOutcome.FailureCategory.EMPTY_AUDIO.name
            )
        }
        is EngineSynthesisOutcome.Failure -> LocalTtsPreviewResult.Failed(
            outcome.cause ?: outcome.category.name
        )
        EngineSynthesisOutcome.Stopped -> LocalTtsPreviewResult.Stopped
    }
}
