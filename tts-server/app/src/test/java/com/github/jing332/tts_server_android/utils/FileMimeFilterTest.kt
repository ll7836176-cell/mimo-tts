package com.github.jing332.tts_server_android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMimeFilterTest {
    @Test
    fun `matches major type wildcards and exact mime types`() {
        assertTrue(FileMimeFilter.matches(listOf("text/*"), "text/plain"))
        assertTrue(FileMimeFilter.matches(listOf("audio/*"), "audio/mpeg"))
        assertTrue(FileMimeFilter.matches(listOf("application/json"), "application/json"))
        assertTrue(FileMimeFilter.matches(listOf("*/*"), "application/octet-stream"))
    }

    @Test
    fun `does not match unrelated or unknown mime types`() {
        assertFalse(FileMimeFilter.matches(listOf("text/*"), "audio/mpeg"))
        assertFalse(FileMimeFilter.matches(listOf("application/zip"), "application/json"))
        assertFalse(FileMimeFilter.matches(listOf("text/*"), null))
    }
}
