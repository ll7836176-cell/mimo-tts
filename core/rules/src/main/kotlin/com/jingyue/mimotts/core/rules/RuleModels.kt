package com.jingyue.mimotts.core.rules

import java.util.Locale

enum class RuleStage {
    INPUT_NORMALIZATION,
    TEXT_REWRITE,
    SEGMENTATION,
    PROFILE_ROUTING,
    INSTRUCTION,
    AUDIO_TAG,
    CHUNK,
}

data class RuleScope(
    val providerId: String? = null,
    val modelId: String? = null,
    val localeLanguageTag: String? = null,
    val profileId: String? = null,
) {
    fun matches(context: RuleContext): Boolean {
        return (providerId == null || providerId == context.providerId) &&
            (modelId == null || modelId == context.modelId) &&
            (localeLanguageTag == null || localeLanguageTag.equals(context.locale.toLanguageTag(), true)) &&
            (profileId == null || profileId == context.profileId)
    }
}

data class RuleContext(
    val providerId: String = "mimo",
    val modelId: String = "mimo-v2.5-tts",
    val locale: Locale = Locale.SIMPLIFIED_CHINESE,
    val profileId: String = "default",
)

sealed class TtsRule(
    open val id: String,
    open val order: Int,
    open val enabled: Boolean = true,
    open val scope: RuleScope = RuleScope(),
    open val maxInputLength: Int = 20_000,
    open val maxReplacements: Int = 1_000,
) {
    abstract val stage: RuleStage
}

data class LiteralReplaceRule(
    override val id: String,
    override val order: Int,
    val from: String,
    val to: String,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.TEXT_REWRITE
}

data class RegexReplaceRule(
    override val id: String,
    override val order: Int,
    val pattern: String,
    val replacement: String,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.TEXT_REWRITE
}

data class SegmentRule(
    override val id: String,
    override val order: Int,
    val delimiterPattern: String,
    val profileTag: String,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.SEGMENTATION
}

data class ProfileRoutingRule(
    override val id: String,
    override val order: Int,
    val profileTag: String,
    val profileId: String,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.PROFILE_ROUTING
}

data class InstructionRule(
    override val id: String,
    override val order: Int,
    val instruction: String,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.INSTRUCTION
}

enum class TagPosition {
    PREFIX,
    SUFFIX,
    INLINE,
}

data class AudioTagRule(
    override val id: String,
    override val order: Int,
    val tag: String,
    val position: TagPosition,
    val anchor: String? = null,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.AUDIO_TAG
}

data class ChunkRule(
    override val id: String,
    override val order: Int,
    val maxChunkLength: Int,
    override val enabled: Boolean = true,
    override val scope: RuleScope = RuleScope(),
    override val maxInputLength: Int = 20_000,
    override val maxReplacements: Int = 1_000,
) : TtsRule(id, order, enabled, scope, maxInputLength, maxReplacements) {
    override val stage: RuleStage = RuleStage.CHUNK
}

data class RuleSetSnapshot(
    val id: String,
    val version: Long,
    val serverOwnsPreprocessing: Boolean,
    val rules: List<TtsRule>,
) {
    val enabledRules: List<TtsRule> get() = rules.filter { it.enabled }
}

data class PlannedSegment(
    val profileId: String,
    val text: String,
    val instructions: List<String> = emptyList(),
    val audioTags: List<String> = emptyList(),
    val maxChunkLength: Int? = null,
)

data class RulePreview(
    val originalText: String,
    val transformedText: String,
    val segments: List<PlannedSegment>,
)
