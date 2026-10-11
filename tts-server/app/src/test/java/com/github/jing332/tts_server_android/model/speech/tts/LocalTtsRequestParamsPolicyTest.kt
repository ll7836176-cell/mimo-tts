package com.github.jing332.tts_server_android.model.speech.tts

import com.github.jing332.tts_server_android.model.speech.BundleEntrySnapshot
import com.github.jing332.tts_server_android.model.speech.BundlePrimitiveSnapshot
import com.github.jing332.tts_server_android.model.speech.GenericTtsRequestParamsSnapshot
import com.github.jing332.tts_server_android.service.systts.help.TtsBundleMaterializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTtsRequestParamsPolicyTest {
    @Test
    fun nonMimoEnginesDoNotReceiveMimoReservedFields() {
        assertFalse(TtsBundleMaterializer.isSafeForNonMiMo(TtsBundleMaterializer.KEY_MIMO_VOICE))
        assertFalse(TtsBundleMaterializer.isSafeForNonMiMo(TtsBundleMaterializer.KEY_MIMO_STYLE))
        assertTrue(TtsBundleMaterializer.isSafeForNonMiMo("vendor.option"))
    }

    @Test
    fun nonMimoSanitizerRemovesReservedKeysHiddenInGenericEntries() {
        val sanitized = TtsBundleMaterializer.sanitizeGenericEntriesForNonMiMo(
            GenericTtsRequestParamsSnapshot(
                listOf(
                    BundleEntrySnapshot(0, "mimo.voice", BundlePrimitiveSnapshot.StringValue("冰糖")),
                    BundleEntrySnapshot(1, "vendor.option", BundlePrimitiveSnapshot.IntValue(7)),
                    BundleEntrySnapshot(2, "tts.rule.instruction.v1", BundlePrimitiveSnapshot.StringValue("温柔")),
                ),
            ),
        )

        assertEquals(1, sanitized.entries.size)
        assertEquals("vendor.option", sanitized.entries.single().key)
        assertEquals(BundlePrimitiveSnapshot.IntValue(7), sanitized.entries.single().value)
    }
}
