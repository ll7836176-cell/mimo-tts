package com.github.jing332.tts_server_android.model.speech.tts

import com.github.jing332.tts_server_android.model.speech.ITextToSpeechSynthesizer
import java.util.concurrent.atomic.AtomicReference

data class AudioDescriptor(
    val sampleRate: Int,
    val encoding: Int,
    val channelCount: Int,
)

sealed interface EngineSynthesisOutcome {
    data class Success(
        val audio: ITextToSpeechSynthesizer.AudioResult? = null,
        val descriptor: AudioDescriptor? = null,
        val direct: Boolean = false,
    ) : EngineSynthesisOutcome

    data class Failure(
        val category: FailureCategory,
        val androidErrorCode: Int? = null,
        val cause: String? = null,
    ) : EngineSynthesisOutcome

    object Stopped : EngineSynthesisOutcome

    enum class FailureCategory {
        INITIALIZATION,
        INVALID_REQUEST,
        IMMEDIATE_API,
        LISTENER,
        EMPTY_AUDIO,
        TIMEOUT,
        CALLBACK,
        UNKNOWN,
    }
}

/** Small exact-once state primitive shared by request and manager tests. */
class EngineSynthesisOutcomeState {
    private val terminal = AtomicReference<EngineSynthesisOutcome?>(null)

    fun success(): Boolean = claim(EngineSynthesisOutcome.Success(direct = true))

    fun failure(): Boolean = claim(
        EngineSynthesisOutcome.Failure(EngineSynthesisOutcome.FailureCategory.UNKNOWN)
    )

    fun stopped(): Boolean = claim(EngineSynthesisOutcome.Stopped)

    val outcome: EngineSynthesisOutcome?
        get() = terminal.get()

    private fun claim(outcome: EngineSynthesisOutcome): Boolean =
        terminal.compareAndSet(null, outcome)
}
