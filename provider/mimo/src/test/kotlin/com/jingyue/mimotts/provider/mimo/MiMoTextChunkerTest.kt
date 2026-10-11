package com.jingyue.mimotts.provider.mimo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoTextChunkerTest {
    @Test
    fun `long text is split at punctuation without exceeding model limit`() {
        val chunks = MiMoTextChunker.split("第一句。第二句！第三句？第四句。", maxLength = 7)

        assertTrue(chunks.all { it.length <= 7 })
        assertEquals("第一句。第二句！第三句？第四句。", chunks.joinToString(""))
    }

    @Test
    fun `chunking does not split surrogate pairs or audio tags`() {
        val chunks = MiMoTextChunker.split("😀[laugh]甲乙丙丁", maxLength = 8)

        assertEquals("😀[laugh]甲乙丙丁", chunks.joinToString(""))
        assertFalse(chunks.any { it.contains("\uD83D") && !it.contains("😀") })
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 8 })
    }

    @Test
    fun `overlong audio tag is rejected instead of being silently cut`() {
        assertThrows(IllegalArgumentException::class.java) {
            MiMoTextChunker.split("[laugh]你好", maxLength = 5)
        }
    }
}
