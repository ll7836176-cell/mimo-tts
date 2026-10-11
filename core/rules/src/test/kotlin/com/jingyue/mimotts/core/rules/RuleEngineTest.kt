package com.jingyue.mimotts.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    @Test
    fun `ordered rewrite and instruction rules produce deterministic preview`() {
        val rules = RuleSetSnapshot(
            id = "demo",
            version = 1,
            serverOwnsPreprocessing = false,
            rules = listOf(
                LiteralReplaceRule("replace", order = 20, from = "AI", to = "人工智能"),
                InstructionRule("tone", order = 10, instruction = "温柔、克制地朗读"),
                AudioTagRule("tag", order = 30, tag = "[laugh]", position = TagPosition.PREFIX),
            ),
        )

        val result = RuleEngine().preview("AI 新闻", rules)

        assertEquals("[laugh]人工智能 新闻", result.segments.single().text)
        assertEquals(listOf("温柔、克制地朗读"), result.segments.single().instructions)
        assertTrue(result.transformedText.contains("人工智能"))
    }

    @Test
    fun `server owned preprocessing skips local rewrites but keeps explicit tags`() {
        val rules = RuleSetSnapshot(
            id = "server-owned",
            version = 1,
            serverOwnsPreprocessing = true,
            rules = listOf(
                LiteralReplaceRule("replace", order = 1, from = "AI", to = "人工智能"),
                AudioTagRule("tag", order = 2, tag = "[whisper]", position = TagPosition.PREFIX),
            ),
        )

        val result = RuleEngine().preview("AI 新闻", rules)

        assertEquals("[whisper]AI 新闻", result.segments.single().text)
    }

    @Test
    fun `segmentation and profile routing create ordered segments`() {
        val rules = RuleSetSnapshot(
            id = "dialogue",
            version = 1,
            serverOwnsPreprocessing = false,
            rules = listOf(
                SegmentRule("segment", order = 1, delimiterPattern = "\\|", profileTag = "dialogue"),
                ProfileRoutingRule("route", order = 2, profileTag = "dialogue", profileId = "character-a"),
            ),
        )

        val result = RuleEngine().preview("first|second", rules)

        assertEquals(listOf("first", "second"), result.segments.map { it.text })
        assertEquals(listOf("character-a", "character-a"), result.segments.map { it.profileId })
    }

    @Test
    fun `regex replacement expands capture groups while preserving literal suffix`() {
        val result = RuleEngine().preview(
            "张三",
            RuleSetSnapshot(
                id = "regex",
                version = 1,
                serverOwnsPreprocessing = false,
                rules = listOf(
                    RegexReplaceRule("swap", 1, "(张)(三)", "\$2\$1"),
                ),
            ),
        )

        assertEquals("三张", result.transformedText)
    }
}
