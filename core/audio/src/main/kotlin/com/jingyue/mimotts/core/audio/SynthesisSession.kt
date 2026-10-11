package com.jingyue.mimotts.core.audio

import com.jingyue.mimotts.core.spi.AudioSpec
import com.jingyue.mimotts.core.spi.ErrorCategory
import com.jingyue.mimotts.core.spi.StructuredError
import com.jingyue.mimotts.core.spi.SynthesisEvent

interface SessionOutput {
    fun onStarted(spec: AudioSpec)
    fun onAudio(bytes: ByteArray, spec: AudioSpec)
    fun onCompleted()
    fun onFailed(error: StructuredError)
    fun onCancelled()
}

class SynthesisSession(
    private val requestId: String,
    private val generation: Long,
    private val maxBufferSize: Int,
    private val output: SessionOutput,
) {
    private val lock = Any()
    private var started = false
    private var startedSpec: AudioSpec? = null
    private var audioSeen = false
    private var terminal = false

    init {
        require(requestId.isNotBlank()) { "requestId is required" }
        require(generation >= 0) { "generation must not be negative" }
        require(maxBufferSize > 0) { "maxBufferSize must be positive" }
    }

    fun accept(event: SynthesisEvent) {
        val action: (() -> Unit)? = synchronized(lock) {
            if (terminal || event.requestId != requestId || event.generation != generation) return
            when (event) {
                is SynthesisEvent.Started -> {
                    if (started) {
                        null
                    } else {
                        started = true
                        startedSpec = event.spec
                        { output.onStarted(event.spec) }
                    }
                }

                is SynthesisEvent.Audio -> {
                    if (!started || event.chunk.bytes.isEmpty()) {
                        null
                    } else if (event.chunk.spec != startedSpec) {
                        terminal = true
                        failureAction(
                            StructuredError(
                                category = ErrorCategory.DECODING,
                                message = com.jingyue.mimotts.core.spi.MiMoSafeErrorCode.AUDIO_DECODING.wireValue,
                                code = com.jingyue.mimotts.core.spi.MiMoSafeErrorCode.AUDIO_DECODING.wireValue,
                            ),
                        )
                    } else {
                        val frameBytes = 2 * event.chunk.spec.channelCount
                        val outputBufferSize = maxBufferSize - (maxBufferSize % frameBytes)
                        if (event.chunk.bytes.size % frameBytes != 0 || outputBufferSize < frameBytes) {
                            terminal = true
                            failureAction(
                                StructuredError(
                                    category = ErrorCategory.DECODING,
                                    message = com.jingyue.mimotts.core.spi.MiMoSafeErrorCode.AUDIO_DECODING.wireValue,
                                    code = com.jingyue.mimotts.core.spi.MiMoSafeErrorCode.AUDIO_DECODING.wireValue,
                                ),
                            )
                        } else {
                        val chunks = event.chunk.bytes
                            .asSequence()
                            .chunked(outputBufferSize)
                            .map { it.toByteArray() }
                            .toList()
                        audioSeen = true
                        { chunks.forEach { bytes -> output.onAudio(bytes, event.chunk.spec) } }
                        }
                    }
                }

                is SynthesisEvent.Completed -> {
                    terminal = true
                    if (!started || !audioSeen) {
                        failureAction(
                            StructuredError(
                                category = ErrorCategory.EMPTY_AUDIO,
                                message = com.jingyue.mimotts.core.spi.MiMoSafeErrorCode.AUDIO_EMPTY.wireValue,
                                code = com.jingyue.mimotts.core.spi.MiMoSafeErrorCode.AUDIO_EMPTY.wireValue,
                            ),
                        )
                    } else {
                        { output.onCompleted() }
                    }
                }

                is SynthesisEvent.Failed -> {
                    terminal = true
                    failureAction(event.error)
                }

                is SynthesisEvent.Cancelled -> {
                    terminal = true
                    { output.onCancelled() }
                }
            }
        }
        action?.invoke()
    }

    /** Invalidates the generation without producing a second terminal callback. */
    fun cancel() {
        synchronized(lock) {
            terminal = true
        }
    }

    private fun failureAction(error: StructuredError): () -> Unit = {
        output.onFailed(error.copy(message = error.code, code = error.code))
    }
}
