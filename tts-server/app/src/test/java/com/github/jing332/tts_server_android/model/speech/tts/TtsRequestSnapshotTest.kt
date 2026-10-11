package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class TtsRequestSnapshotTest {
    @Test
    fun `generic parameter snapshot copies order and values`() {
        val source = mutableListOf(
            BundleEntrySnapshot(0, "foo", BundlePrimitiveSnapshot.StringValue("one")),
            BundleEntrySnapshot(1, "bar", BundlePrimitiveSnapshot.IntValue(2)),
        )
        val snapshot = GenericTtsRequestParamsSnapshot(source)
        source[0] = BundleEntrySnapshot(0, "foo", BundlePrimitiveSnapshot.StringValue("mutated"))

        assertNotSame(source, snapshot.entries)
        assertEquals("one", (snapshot.entries[0].value as BundlePrimitiveSnapshot.StringValue).value)
        assertEquals(null, snapshot.validationError())
    }

    @Test
    fun `non contiguous ordinals are invalid`() {
        val snapshot = GenericTtsRequestParamsSnapshot(
            listOf(
                BundleEntrySnapshot(0, "foo", BundlePrimitiveSnapshot.StringValue("one")),
                BundleEntrySnapshot(2, "bar", BundlePrimitiveSnapshot.IntValue(2)),
            ),
        )

        assertEquals(TtsServerSafeErrorCode.PARAM_CONFLICT, snapshot.validationError())
    }
}
