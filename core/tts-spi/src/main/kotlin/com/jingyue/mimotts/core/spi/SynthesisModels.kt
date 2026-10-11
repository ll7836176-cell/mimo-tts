package com.jingyue.mimotts.core.spi

import java.time.Instant
import java.util.Locale

enum class AudioEncoding {
    PCM_16,
}

enum class AudioContainer {
    RAW_PCM,
    WAV,
    MP3,
}

data class AudioSpec(
    val sampleRate: Int,
    val encoding: AudioEncoding,
    val channelCount: Int,
    val container: AudioContainer,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
    }
}

data class AudioChunk(
    val requestId: String,
    val generation: Long,
    val sequence: Long,
    val bytes: ByteArray,
    val spec: AudioSpec,
)

data class ProviderConfigSnapshot(
    val values: Map<String, String>,
) {
    private val snapshot: Map<String, String> = values.toMap()

    operator fun get(key: String): String? = snapshot[key]

    fun asMap(): Map<String, String> = snapshot
}

data class SynthesisSegment(
    val profileId: String,
    val text: String,
    val instructions: List<String> = emptyList(),
    val audioTags: List<String> = emptyList(),
    val maxChunkLength: Int? = null,
    val styleInstruction: String? = null,
)

data class SynthesisRequest(
    val requestId: String,
    val generation: Long,
    val attempt: Int,
    val originalText: String,
    val segments: List<SynthesisSegment>,
    val locale: Locale,
    val voice: VoiceDefinition?,
    val providerId: String,
    val modelId: String,
    val profileId: String,
    val speechRate: Float,
    val pitch: Float,
    val volume: Float,
    val source: RequestSource,
    val providerConfig: ProviderConfigSnapshot,
    val ruleSetId: String,
    val ruleSetVersion: Long,
    val deadline: Instant? = null,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(generation >= 0) { "generation must not be negative" }
        require(attempt >= 1) { "attempt must start at one" }
    }
}

enum class RequestSource {
    SYSTEM_TTS,
    APP_PREVIEW,
}

sealed interface SynthesisEvent {
    val requestId: String
    val generation: Long

    data class Started(
        override val requestId: String,
        override val generation: Long,
        val spec: AudioSpec,
    ) : SynthesisEvent

    data class Audio(
        val chunk: AudioChunk,
    ) : SynthesisEvent {
        override val requestId: String get() = chunk.requestId
        override val generation: Long get() = chunk.generation
    }

    data class Completed(
        override val requestId: String,
        override val generation: Long,
    ) : SynthesisEvent

    data class Failed(
        override val requestId: String,
        override val generation: Long,
        val error: StructuredError,
    ) : SynthesisEvent

    data class Cancelled(
        override val requestId: String,
        override val generation: Long,
    ) : SynthesisEvent
}

enum class ErrorCategory {
    INVALID_REQUEST,
    NOT_CONFIGURED,
    INVALID_RESOURCE,
    AUTHENTICATION,
    QUOTA,
    NETWORK,
    TIMEOUT,
    DECODING,
    EMPTY_AUDIO,
    CANCELLED,
    UNKNOWN,
}

data class StructuredError(
    val category: ErrorCategory,
    val message: String,
    val retryable: Boolean = false,
    val code: String = MiMoSafeErrorCode.INTERNAL.wireValue,
)
