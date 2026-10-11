package com.jingyue.mimotts

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiMoTargetProcessTestSeamTest {
    private val uri = Uri.parse("content://com.jingyue.mimotts.debug.testcontrol")

    @Test
    fun control_tokens_are_one_use_and_responses_are_safe_metadata_only() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val bootstrap = resolver.call(
            uri,
            "issueBootstrap",
            null,
            Bundle().apply { putInt("protocolVersion", 1) },
        )
        assertNotNull(bootstrap)
        assertEquals("ok", bootstrap?.getString("status"))
        val bootstrapToken = bootstrap?.getString("bootstrapToken")
        assertFalse(bootstrapToken.isNullOrBlank())

        val install = resolver.call(
            uri,
            "install",
            null,
            Bundle().apply {
                putInt("protocolVersion", 1)
                putString("bootstrapToken", bootstrapToken)
                putString("mode", "FAKE_SUCCESS")
                putBundle("providerResult", Bundle().apply {
                    putString("outcome", "success")
                })
                putBundle("chunkPlan", Bundle().apply {
                    putInt("chunks", 1)
                })
            },
        )
        assertEquals("ok", install?.getString("status"))
        val sessionToken = install?.getString("sessionToken")
        assertFalse(sessionToken.isNullOrBlank())

        val reused = resolver.call(
            uri,
            "install",
            null,
            Bundle().apply {
                putInt("protocolVersion", 1)
                putString("bootstrapToken", bootstrapToken)
                putString("mode", "FAKE_SUCCESS")
                putBundle("providerResult", Bundle().apply {
                    putString("outcome", "success")
                })
            },
        )
        assertEquals("control.invalid_token", reused?.getString("canonicalCodeId"))

        val diagnostics = resolver.call(
            uri,
            "diagnostics",
            null,
            Bundle().apply {
                putInt("protocolVersion", 1)
                putString("sessionToken", sessionToken)
            },
        )
        assertEquals("ok", diagnostics?.getString("status"))
        assertTrue(diagnostics?.containsKey("fingerprint") == true)
        assertFalse(diagnostics?.containsKey("providerResult") == true)
    }
}
