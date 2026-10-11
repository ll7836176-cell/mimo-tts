package com.github.jing332.tts_server_android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkPortPolicyTest {
    @Test
    fun `accepts valid port boundaries`() {
        assertEquals(1, NetworkPortPolicy.parse("1"))
        assertEquals(65_535, NetworkPortPolicy.parse("65535"))
        assertEquals(4566, NetworkPortPolicy.parse(" 4566 "))
    }

    @Test
    fun `rejects empty malformed overflow and out of range ports`() {
        listOf("", "port", "2147483648", "0", "65536", "-1").forEach {
            assertNull(NetworkPortPolicy.parse(it))
        }
    }
}
