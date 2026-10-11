package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.VoiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoPluginContractTest {
    @Test
    fun `plugin advertises only the fixed built in catalog`() {
        val plugin = MiMoPlugin()

        assertEquals(1, plugin.models.size)
        assertEquals(MiMoModelIds.BUILT_IN, plugin.models.single().id)
        assertEquals(9, plugin.voices.size)
        assertTrue(plugin.voices.all { it.kind == VoiceKind.BUILT_IN })
    }
}
