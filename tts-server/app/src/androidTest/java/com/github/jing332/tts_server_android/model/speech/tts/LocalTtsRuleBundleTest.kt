package com.github.jing332.tts_server_android.model.speech.tts

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.jing332.tts_server_android.service.systts.help.TtsBundleMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTtsRuleBundleTest {
    @Test
    fun nonMimoSanitizerRemovesReservedKeysHiddenInGenericEntries() {
        val source = Bundle().apply {
            putInt("tts.params.v1.count", 3)
            putString("tts.params.v1.0.key", "mimo.voice")
            putString("tts.params.v1.0.type", "string")
            putString("tts.params.v1.0.string", "冰糖")
            putString("tts.params.v1.1.key", "vendor.option")
            putString("tts.params.v1.1.type", "int")
            putInt("tts.params.v1.1.int", 7)
            putString("tts.params.v1.2.key", "tts.rule.instruction.v1")
            putString("tts.params.v1.2.type", "string")
            putString("tts.params.v1.2.string", "温柔")
        }

        val sanitized = TtsBundleMaterializer.sanitizeForNonMiMo(source)

        assertEquals(1, sanitized.getInt("tts.params.v1.count"))
        assertEquals("vendor.option", sanitized.getString("tts.params.v1.0.key"))
        assertEquals(7, sanitized.getInt("tts.params.v1.0.int"))
        assertFalse(sanitized.containsKey("tts.params.v1.1.key"))
    }
}
