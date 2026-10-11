package com.github.jing332.tts_server_android.model.speech.tts

import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

class LocalTtsRequestState(
    val utteranceId: String,
    val generation: Long,
) {
    private val terminal = AtomicReference<EngineSynthesisOutcome?>(null)
    private val completion = CompletableDeferred<EngineSynthesisOutcome>()

    @Volatile
    var descriptor: AudioDescriptor? = null
        private set

    @Volatile
    var hasStarted: Boolean = false
        private set

    fun accepts(candidateUtteranceId: String?, candidateGeneration: Long): Boolean =
        candidateUtteranceId == utteranceId && candidateGeneration == generation && terminal.get() == null

    fun recordDescriptor(
        candidateUtteranceId: String?,
        candidateGeneration: Long,
        value: AudioDescriptor,
    ): Boolean {
        if (!accepts(candidateUtteranceId, candidateGeneration)) return false
        descriptor = value
        return true
    }

    fun markStarted(): Boolean {
        if (terminal.get() != null) return false
        hasStarted = true
        return true
    }

    fun success(
        audio: com.github.jing332.tts_server_android.model.speech.ITextToSpeechSynthesizer.AudioResult? = null,
        descriptor: AudioDescriptor? = null,
        direct: Boolean = false,
    ): Boolean = claim(EngineSynthesisOutcome.Success(audio, descriptor, direct))

    fun done(): Boolean = success(direct = true)

    fun failure(
        category: EngineSynthesisOutcome.FailureCategory = EngineSynthesisOutcome.FailureCategory.LISTENER,
        androidErrorCode: Int? = null,
        cause: String? = null,
    ): Boolean = claim(EngineSynthesisOutcome.Failure(category, androidErrorCode, cause))

    fun error(): Boolean = failure(androidErrorCode = TextToSpeech.ERROR_SYNTHESIS)

    fun stop(): Boolean = claim(EngineSynthesisOutcome.Stopped)

    suspend fun await(): EngineSynthesisOutcome = completion.await()

    val outcome: EngineSynthesisOutcome?
        get() = terminal.get()

    private fun claim(outcome: EngineSynthesisOutcome): Boolean {
        if (!terminal.compareAndSet(null, outcome)) return false
        completion.complete(outcome)
        return true
    }
}
