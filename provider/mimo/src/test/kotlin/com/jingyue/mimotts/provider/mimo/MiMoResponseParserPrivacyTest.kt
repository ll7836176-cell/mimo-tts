package com.jingyue.mimotts.provider.mimo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoResponseParserPrivacyTest {
    @Test
    fun `malformed response exposes only a safe error`() {
        val sentinel = "PRIVATE_RESPONSE_SENTINEL"
        val events = MiMoResponseParser().parseBatch("{\"error\":{\"message\":\"$sentinel\"}}")

        val error = events.single() as MiMoResponseEvent.Error
        assertTrue(error.code.isNotBlank())
        assertFalse(error.message.contains(sentinel))
    }
}
