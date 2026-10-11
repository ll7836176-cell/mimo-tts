package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTtsParameterPolicyTest {
    @Test
    fun fixedValuesUseTheirPersistedLocalTtsScales() {
        assertEquals(1.0f, LocalTtsParameterPolicy.rateMultiplier(50), 0.001f)
        assertEquals(1.0f, LocalTtsParameterPolicy.pitchMultiplier(100), 0.001f)
    }

    @Test
    fun systemValuesUseTheCallerScales() {
        assertEquals(1.0f, LocalTtsParameterPolicy.rateMultiplierFromSystem(20), 0.001f)
        assertEquals(1.5f, LocalTtsParameterPolicy.rateMultiplierFromSystem(30), 0.001f)
        assertEquals(1.0f, LocalTtsParameterPolicy.pitchMultiplierFromSystem(0), 0.001f)
        assertEquals(1.5f, LocalTtsParameterPolicy.pitchMultiplierFromSystem(50), 0.001f)
    }

    @Test
    fun allMultipliersAreClampedToTalkifyCompatibilityRange() {
        assertEquals(0.25f, LocalTtsParameterPolicy.rateMultiplier(1), 0.001f)
        assertEquals(3.0f, LocalTtsParameterPolicy.rateMultiplier(1000), 0.001f)
        assertEquals(0.25f, LocalTtsParameterPolicy.pitchMultiplier(1), 0.001f)
        assertEquals(2.0f, LocalTtsParameterPolicy.pitchMultiplier(1000), 0.001f)
    }
}
