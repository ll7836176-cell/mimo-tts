package com.jingyue.mimotts.core.rules

import com.jingyue.mimotts.core.spi.SynthesisSegment

class RuleEngine(
    private val validator: RuleValidator = RuleValidator(),
) {
    fun preview(
        originalText: String,
        snapshot: RuleSetSnapshot,
        context: RuleContext = RuleContext(),
    ): RulePreview {
        val applicable = snapshot.enabledRules
            .filter { it.scope.matches(context) }
            .sortedWith(compareBy<TtsRule> { it.stage.ordinal }.thenBy { it.order }.thenBy { it.id })
        val allowed = validator.validate(snapshot).filterNot { it.contains("invalid regex") }
        if (allowed.isNotEmpty()) {
            val fatal = allowed.filterNot { it.contains("source is required") }
            require(fatal.isEmpty()) { fatal.joinToString("; ") }
        }

        var text = normalize(originalText)
        if (!snapshot.serverOwnsPreprocessing) {
            applicable.filter { it.stage == RuleStage.TEXT_REWRITE }.forEach { rule ->
                text = when (rule) {
                    is LiteralReplaceRule -> replaceLiteral(text, rule)
                    is RegexReplaceRule -> replaceRegex(text, rule)
                    else -> text
                }
            }
        }

        val segmentRules = if (snapshot.serverOwnsPreprocessing) emptyList()
        else applicable.filterIsInstance<SegmentRule>()
        val mutableSegments = if (segmentRules.isEmpty()) {
            mutableListOf(MutableSegment(profileId = context.profileId, text = text))
        } else {
            splitSegments(text, segmentRules.last())
        }

        if (!snapshot.serverOwnsPreprocessing) {
            applicable.filterIsInstance<ProfileRoutingRule>().forEach { rule ->
                mutableSegments.filter { it.profileTag == rule.profileTag }.forEach { it.profileId = rule.profileId }
            }
        }

        applicable.filterIsInstance<InstructionRule>().forEach { rule ->
            mutableSegments.forEach { it.instructions += rule.instruction }
        }
        applicable.filterIsInstance<AudioTagRule>().forEach { rule ->
            mutableSegments.forEach { segment ->
                applyTag(segment, rule)
                segment.audioTags += rule.tag
            }
        }

        val chunkLimit = applicable.filterIsInstance<ChunkRule>().lastOrNull()?.maxChunkLength
        val planned = mutableSegments.map {
            PlannedSegment(
                profileId = it.profileId,
                text = it.text,
                instructions = it.instructions.toList(),
                audioTags = it.audioTags.toList(),
                maxChunkLength = chunkLimit,
            )
        }
        return RulePreview(
            originalText = originalText,
            transformedText = planned.joinToString(separator = "") { it.text },
            segments = planned,
        )
    }

    fun toSynthesisSegments(preview: RulePreview): List<SynthesisSegment> = preview.segments.map {
        SynthesisSegment(
            profileId = it.profileId,
            text = it.text,
            instructions = it.instructions,
            audioTags = it.audioTags,
            maxChunkLength = it.maxChunkLength,
        )
    }

    private fun normalize(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun replaceLiteral(text: String, rule: LiteralReplaceRule): String {
        if (rule.from.isEmpty() || text.length > rule.maxInputLength) return text
        var remaining = text
        val builder = StringBuilder()
        var count = 0
        while (count < rule.maxReplacements) {
            val index = remaining.indexOf(rule.from)
            if (index < 0) {
                builder.append(remaining)
                return builder.toString()
            }
            builder.append(remaining, 0, index).append(rule.to)
            remaining = remaining.substring(index + rule.from.length)
            count++
        }
        return builder.append(remaining).toString()
    }

    private fun replaceRegex(text: String, rule: RegexReplaceRule): String {
        if (text.length > rule.maxInputLength) return text
        val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: return text
        val matches = regex.findAll(text).take(rule.maxReplacements).toList()
        if (matches.isEmpty()) return text
        val replacementEnd = matches.last().range.last + 1
        val prefix = text.substring(0, replacementEnd)
        return runCatching { regex.replace(prefix, rule.replacement) }
            .getOrElse { text }
            .let { replaced -> replaced + text.substring(replacementEnd) }
    }

    private fun splitSegments(text: String, rule: SegmentRule): MutableList<MutableSegment> {
        val regex = runCatching { Regex(rule.delimiterPattern) }.getOrNull() ?: return mutableListOf(
            MutableSegment(rule.profileTag, text),
        )
        return regex.split(text)
            .filter { it.isNotEmpty() }
            .map { MutableSegment(rule.profileTag, it) }
            .toMutableList()
            .ifEmpty { mutableListOf(MutableSegment(rule.profileTag, text)) }
    }

    private fun applyTag(segment: MutableSegment, rule: AudioTagRule) {
        segment.text = when (rule.position) {
            TagPosition.PREFIX -> rule.tag + segment.text
            TagPosition.SUFFIX -> segment.text + rule.tag
            TagPosition.INLINE -> {
                val anchor = rule.anchor.orEmpty()
                if (anchor.isBlank()) rule.tag + segment.text
                else segment.text.replace(anchor, rule.tag + anchor)
            }
        }
    }

    private class MutableSegment(
        var profileId: String,
        var text: String,
        var profileTag: String = profileId,
        val instructions: MutableList<String> = mutableListOf(),
        val audioTags: MutableList<String> = mutableListOf(),
    )
}
