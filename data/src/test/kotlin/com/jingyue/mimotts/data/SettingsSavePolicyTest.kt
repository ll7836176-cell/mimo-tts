package com.jingyue.mimotts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSavePolicyTest {
    @Test
    fun `blank api key keeps the existing key`() {
        assertEquals(
            "existing-secret",
            SettingsSavePolicy.resolveApiKey(input = "  ", existing = "existing-secret"),
        )
    }

    @Test
    fun `new api key replaces the existing key`() {
        assertEquals(
            "new-secret",
            SettingsSavePolicy.resolveApiKey(input = "  new-secret  ", existing = "existing-secret"),
        )
    }

    @Test
    fun `blank api key stays absent when no key was saved`() {
        assertEquals(null, SettingsSavePolicy.resolveApiKey(input = "", existing = null))
    }
}
