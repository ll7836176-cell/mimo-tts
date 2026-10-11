package com.jingyue.mimotts.core.spi

import java.util.Locale

enum class VoiceKind {
    BUILT_IN,
    DESIGN,
    CLONE,
}

enum class ModelCapability {
    STYLE_INSTRUCTION,
    AUDIO_TAGS,
    STREAMING_PCM,
    VOICE_DESIGN,
    VOICE_CLONE,
    MULTI_ROLE,
    OPTIMIZE_TEXT_PREVIEW,
}

enum class ConfigFieldType {
    TEXT,
    SECRET,
    BOOLEAN,
    SELECT,
}

data class ConfigField(
    val key: String,
    val type: ConfigFieldType,
    val required: Boolean,
    val sensitive: Boolean = false,
    val defaultValue: String = "",
    val labelKey: String = key,
    val advanced: Boolean = false,
    val options: List<String> = emptyList(),
)

data class ConfigSchema(
    val version: Int,
    val fields: List<ConfigField>,
)

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<ModelCapability>,
    val supportedVoiceKinds: Set<VoiceKind>,
    val supportedFormats: Set<AudioContainer>,
    val streaming: StreamingSemantics,
    val maxTextLength: Int = 4_000,
    val requiredConfigKeys: Set<String> = emptySet(),
)

enum class StreamingSemantics {
    REAL_TIME,
    COMPATIBILITY_STREAM,
    BATCH,
}

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val defaultModelId: String,
    val moduleVersion: String,
    val configSchema: ConfigSchema,
)

interface EnginePlugin {
    val descriptor: ProviderDescriptor
    val models: List<ModelDescriptor>
    val voices: List<VoiceDefinition>
    fun createProvider(): SynthesisProvider
}

/** Compatibility name used by the implementation specification. */
typealias ProviderModule = EnginePlugin

interface SynthesisProvider {
    fun synthesize(request: SynthesisRequest, sink: SynthesisEventSink): RequestHandle
}

fun interface SynthesisEventSink {
    fun onEvent(event: SynthesisEvent)
}

fun interface RequestHandle {
    fun cancel()
}

class ProviderModuleRegistry private constructor(
    private val modules: Map<String, EnginePlugin>,
) {
    fun get(providerId: String): EnginePlugin? = modules[providerId]

    fun all(): List<EnginePlugin> = modules.values.toList()

    companion object {
        fun create(modules: Iterable<EnginePlugin>): ProviderModuleRegistry {
            val list = modules.toList()
            val indexed = list.associateBy { it.descriptor.id }
            require(indexed.size == list.size) { "Provider IDs must be unique" }
            return ProviderModuleRegistry(indexed)
        }
    }
}

data class VoiceDefinition(
    val providerId: String,
    val modelId: String,
    val profileId: String,
    val kind: VoiceKind,
    val requestVoiceId: String,
    val displayName: String,
    val locale: Locale,
    val description: String = "",
    val requiresUserResource: Boolean = false,
)
