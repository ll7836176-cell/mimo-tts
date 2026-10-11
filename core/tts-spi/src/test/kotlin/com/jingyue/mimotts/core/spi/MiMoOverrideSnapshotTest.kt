package com.jingyue.mimotts.core.spi

import org.junit.Assert.assertEquals
import org.junit.Test

class MiMoOverrideSnapshotTest {
    @Test
    fun `voice and style preserve absent versus present empty`() {
        val snapshot = MiMoOverridePresenceSnapshot(
            voice = FieldState.Present("冰糖"),
            style = FieldState.Present(""),
        )

        assertEquals("冰糖", (snapshot.voice as FieldState.Present).value)
        assertEquals("", (snapshot.style as FieldState.Present).value)
    }
}
