package com.jingyue.mimotts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jingyue.mimotts.debug.MiMoTargetProcessTestSeam
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiMoTtsServiceProviderSeamTest {
    private val controlUri = Uri.parse("content://com.jingyue.mimotts.debug.testcontrol")

    @Test
    fun fake_success_is_injected_before_service_creation_and_counts_stay_in_target_process() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(Intent(context, com.jingyue.mimotts.tts.MiMoTtsService::class.java))
        val resolver = context.contentResolver
        val sessionToken = installFake(resolver, chunks = 2)
        val initialized = CountDownLatch(1)
        val initStatus = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val tts = TextToSpeech(
            context,
            { status ->
                initStatus.set(status)
                initialized.countDown()
            },
            context.packageName,
        )

        try {
            assertTrue(initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus.get())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }

                override fun onError(utteranceId: String?) {
                    error.set("synthesis_failed")
                    done.countDown()
                }
            })
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.speak("seam test", TextToSpeech.QUEUE_FLUSH, Bundle(), "seam-test"),
            )
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertNull(error.get())

            val stats = resolver.call(
                controlUri,
                MiMoTargetProcessTestSeam.METHOD_STATS,
                null,
                Bundle().apply {
                    putInt(MiMoTargetProcessTestSeam.KEY_PROTOCOL_VERSION, 1)
                    putString(MiMoTargetProcessTestSeam.KEY_SESSION_TOKEN, sessionToken)
                },
            )
            assertEquals(MiMoTargetProcessTestSeam.STATUS_OK, stats?.getString("status"))
            assertEquals(1, stats?.getInt("providerRequests"))
            assertEquals(2, stats?.getInt("factoryCreates"))
            assertEquals(2, stats?.getInt("transportRequests"))
            assertEquals(2, stats?.getInt("chunkEvents"))
            assertTrue((stats?.getInt("sinkEvents") ?: 0) >= 4)
        } finally {
            tts.shutdown()
            resolver.call(
                controlUri,
                MiMoTargetProcessTestSeam.METHOD_RESET,
                null,
                Bundle().apply {
                    putInt(MiMoTargetProcessTestSeam.KEY_PROTOCOL_VERSION, 1)
                    putString(MiMoTargetProcessTestSeam.KEY_SESSION_TOKEN, sessionToken)
                },
            )
        }
    }

    private fun installFake(
        resolver: android.content.ContentResolver,
        chunks: Int,
    ): String {
        val bootstrap = resolver.call(
            controlUri,
            MiMoTargetProcessTestSeam.METHOD_ISSUE_BOOTSTRAP,
            null,
            Bundle().apply {
                putInt(MiMoTargetProcessTestSeam.KEY_PROTOCOL_VERSION, 1)
            },
        )
        assertFalse(bootstrap?.getString(MiMoTargetProcessTestSeam.KEY_BOOTSTRAP_TOKEN).isNullOrBlank())
        val token = bootstrap?.getString(MiMoTargetProcessTestSeam.KEY_BOOTSTRAP_TOKEN).orEmpty()
        val install = resolver.call(
            controlUri,
            MiMoTargetProcessTestSeam.METHOD_INSTALL,
            null,
            Bundle().apply {
                putInt(MiMoTargetProcessTestSeam.KEY_PROTOCOL_VERSION, 1)
                putString(MiMoTargetProcessTestSeam.KEY_BOOTSTRAP_TOKEN, token)
                putString(MiMoTargetProcessTestSeam.KEY_MODE, MiMoTargetProcessTestSeam.MODE_FAKE_SUCCESS)
                putBundle(MiMoTargetProcessTestSeam.KEY_PROVIDER_RESULT, Bundle().apply {
                    putString(MiMoTargetProcessTestSeam.KEY_OUTCOME, MiMoTargetProcessTestSeam.OUTCOME_SUCCESS)
                })
                putBundle(MiMoTargetProcessTestSeam.KEY_CHUNK_PLAN, Bundle().apply {
                    putInt(MiMoTargetProcessTestSeam.KEY_CHUNKS, chunks)
                })
            },
        )
        assertEquals(MiMoTargetProcessTestSeam.STATUS_OK, install?.getString("status"))
        return install?.getString(MiMoTargetProcessTestSeam.KEY_SESSION_TOKEN).orEmpty()
    }
}
