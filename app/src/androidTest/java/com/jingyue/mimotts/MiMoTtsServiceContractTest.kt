package com.jingyue.mimotts

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiMoTtsServiceContractTest {
    @Test
    fun tts_engine_is_listed_for_tts_server_engine_picker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tts = TextToSpeech(context, null)

        try {
            assertTrue(
                "MiMo engine is missing from TextToSpeech.engines: " +
                    tts.engines.map { it.name },
                tts.engines.any { it.name == context.packageName },
            )
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun advertised_voices_are_complete_and_selectable_by_tts_server() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedNames = setOf(
            "mimo_default::zh-CN",
            "冰糖::zh-CN",
            "茉莉::zh-CN",
            "苏打::zh-CN",
            "白桦::zh-CN",
            "Mia::en-US",
            "Chloe::en-US",
            "Milo::en-US",
            "Dean::en-US",
        )
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(Int.MIN_VALUE)
        val tts = TextToSpeech(
            context,
            { status ->
                initStatus.set(status)
                initLatch.countDown()
            },
            context.packageName,
        )

        try {
            assertTrue("TTS engine init timed out", initLatch.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus.get())
            val voices = tts.voices.orEmpty()
            assertEquals(expectedNames, voices.map { it.name }.toSet())

            expectedNames.forEach { name ->
                val voice = voices.single { it.name == name }
                assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voice))
            }
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun tts_service_is_bindable_by_tts_server_client() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL,
        )

        val mimoService = services.firstOrNull { it.serviceInfo.packageName == context.packageName }
        assertTrue(mimoService != null)
        assertTrue(mimoService?.serviceInfo?.permission.isNullOrBlank())
        assertTrue(context.packageName == "com.jingyue.mimotts")
    }

    @Test
    fun manifest_supports_android_ten_devices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(context.applicationInfo.minSdkVersion <= Build.VERSION_CODES.Q)
    }

    @Test
    fun online_tts_smoke_when_explicitly_requested() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e online true to run the live MiMo smoke test", arguments.getString("online") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "mimo-online-smoke.wav")
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(Int.MIN_VALUE)
        val doneLatch = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val tts = TextToSpeech(
            context,
            { status ->
                initStatus.set(status)
                initLatch.countDown()
            },
            context.packageName,
        )

        try {
            assertTrue("TTS engine init timed out", initLatch.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus.get())
            tts.language = Locale.CHINA
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    doneLatch.countDown()
                }

                override fun onError(utteranceId: String?) {
                    error.set("TTS synthesis failed")
                    doneLatch.countDown()
                }
            })

            assertEquals(
                TextToSpeech.SUCCESS,
                tts.synthesizeToFile(
                    "MiMo TTS 真机在线合成测试。",
                    Bundle(),
                    output,
                    "mimo-online-smoke",
                ),
            )
            assertTrue("TTS synthesis timed out", doneLatch.await(60, TimeUnit.SECONDS))
            assertTrue("TTS synthesis failed: ${error.get()}", error.get() == null)
            assertTrue("No audio file was produced", output.exists())
            assertTrue("Audio file is empty", output.length() > 44)
        } finally {
            tts.shutdown()
            output.delete()
        }
    }
}
