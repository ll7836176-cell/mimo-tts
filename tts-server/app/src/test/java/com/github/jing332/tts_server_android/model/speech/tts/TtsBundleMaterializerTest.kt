package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsBundleMaterializerTest {
    @Test
    fun `mimo materialization uses exact reserved keys and preserves typed generic entries`() {
        val result = TtsBundleMaterializer().materializeSnapshot(
            generic = GenericTtsRequestParamsSnapshot(
                listOf(
                    BundleEntrySnapshot(0, "rate", BundlePrimitiveSnapshot.FloatValue(1.1f)),
                ),
            ),
            providerId = "mimo",
            voice = "冰糖",
            style = "温柔",
        )

        assertTrue(result is TtsBundleMaterializer.SnapshotResult.Success)
        val entries = (result as TtsBundleMaterializer.SnapshotResult.Success).entries
        assertEquals("冰糖", entries[TtsBundleMaterializer.KEY_MIMO_VOICE])
        assertEquals("温柔", entries[TtsBundleMaterializer.KEY_MIMO_STYLE])
        assertEquals(1, entries[TtsBundleMaterializer.KEY_GENERIC_COUNT])
        assertEquals(1.1f, entries["tts.params.v1.0.float"])
        assertFalse(entries.containsKey("mimo.voice_id"))
    }

    @Test
    fun `reserved generic keys are rejected instead of being silently overwritten`() {
        val result = TtsBundleMaterializer().materializeSnapshot(
            generic = GenericTtsRequestParamsSnapshot(
                listOf(
                    BundleEntrySnapshot(0, "mimo.voice", BundlePrimitiveSnapshot.StringValue("冰糖")),
                ),
            ),
            providerId = "mimo",
            voice = "茉莉",
        )

        assertEquals(
            TtsServerSafeErrorCode.PARAM_CONFLICT,
            (result as TtsBundleMaterializer.SnapshotResult.Failure).code,
        )
    }

    @Test
    fun `base instructions travel as ordered tts-owned generic strings`() {
        val result = TtsBundleMaterializer().materializeSnapshot(
            generic = GenericTtsRequestParamsSnapshot(
                listOf(
                    BundleEntrySnapshot(0, "rate", BundlePrimitiveSnapshot.FloatValue(1.1f)),
                ),
            ),
            providerId = "mimo",
            baseInstructions = listOf("保持自然停顿", "语气克制"),
        )

        val entries = (result as TtsBundleMaterializer.SnapshotResult.Success).entries
        assertEquals(3, entries[TtsBundleMaterializer.KEY_GENERIC_COUNT])
        assertEquals("tts.rule.instruction.v1", entries["tts.params.v1.1.key"])
        assertEquals("保持自然停顿", entries["tts.params.v1.1.string"])
        assertEquals("语气克制", entries["tts.params.v1.2.string"])
    }
}
