package com.jingyue.mimotts.provider.mimo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoModelProfileTest {
    @Test
    fun `built in catalog contains every official MiMo preset voice`() {
        val voices = MiMoVoiceCatalog().builtInVoices()

        assertEquals(
            setOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
            voices.map { it.requestVoiceId }.toSet(),
        )
        assertEquals(9, voices.size)
        assertEquals(9, voices.map { it.requestVoiceId }.toSet().size)
    }

    @Test
    fun `design and clone profiles reject built in voice selection`() {
        assertTrue(MiMoModelProfiles.voiceDesign.forbidsBuiltInVoice)
        assertTrue(MiMoModelProfiles.voiceClone.forbidsBuiltInVoice)
        assertFalse(MiMoModelProfiles.builtIn.forbidsBuiltInVoice)
    }

    @Test
    fun `clone profile enforces mp3 or wav sample`() {
        val invalid = MiMoVoiceProfile.clone("clone", sample = VoiceSample("audio/ogg", byteArrayOf(1)))

        assertTrue(invalid.validationErrors().any { it.contains("MP3") })
    }
}
