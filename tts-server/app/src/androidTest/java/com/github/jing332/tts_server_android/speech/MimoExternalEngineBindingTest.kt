package com.github.jing332.tts_server_android.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleMiMoProfileParser
import com.github.jing332.tts_server_android.model.speech.TtsTextPair
import com.github.jing332.tts_server_android.model.speech.tts.LocalTTS
import com.github.jing332.tts_server_android.service.systts.help.TextToSpeechManager
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MimoExternalEngineBindingTest {
    companion object {
        private const val MIMO_PACKAGE = "com.jingyue.mimotts"
    }

    @Test
    fun styleOnlyMiMoProfileCarriesTheConfiguredLocalVoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = TextToSpeechManager(context)
        val pair = TtsTextPair(
            tts = LocalTTS(
                engine = MIMO_PACKAGE,
                locale = "zh-CN",
                voiceName = "白桦::zh-CN",
            ),
            text = "配置音色传递测试。",
            mimoProfile = SpeechRuleMiMoProfileParser.Profile(
                schemaVersion = 1,
                style = "自然、清晰地朗读。",
            ),
        )

        val params = manager.requestParamsFor(pair, Bundle())

        assertEquals("白桦::zh-CN", params?.getString("mimo.voice"))
        assertEquals("自然、清晰地朗读。", params?.getString("mimo.style"))
    }

    @Test
    fun miMoProfileIsOptionalWhenUsingTheConfiguredLocalVoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = TextToSpeechManager(context)
        val pair = TtsTextPair(
            tts = LocalTTS(
                engine = MIMO_PACKAGE,
                locale = "zh-CN",
                voiceName = "白桦::zh-CN",
            ),
            text = "没有风格也应使用已配置音色。",
        )

        val params = manager.requestParamsFor(pair, Bundle())

        assertEquals("白桦::zh-CN", params?.getString("mimo.voice"))
    }

    @Test
    fun ttsServerProcessCanBindToMiMoAndReadItsVoiceCatalog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $MIMO_PACKAGE/com.jingyue.mimotts.ui.MainActivity",
        ).close()
        Thread.sleep(1_000)

        val initialized = CountDownLatch(1)
        val status = AtomicInteger(TextToSpeech.ERROR)
        val tts = TextToSpeech(context, { result ->
            status.set(result)
            initialized.countDown()
        }, MIMO_PACKAGE)

        try {
            assertTrue("MiMo engine initialization timed out", initialized.await(10, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status.get())
            assertTrue(
                "MiMo voice catalog is unavailable",
                tts.voices.orEmpty().any { it.name == "白桦::zh-CN" },
            )
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun ttsServerProcessCanSynthesizeThroughMiMo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $MIMO_PACKAGE/com.jingyue.mimotts.ui.MainActivity",
        ).close()
        Thread.sleep(1_000)

        val initialized = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        val completed = CountDownLatch(1)
        val result = AtomicInteger(TextToSpeech.ERROR)
        val utteranceId = "mimo-external-${System.nanoTime()}"
        val tts = TextToSpeech(context, { status ->
            initStatus.set(status)
            initialized.countDown()
        }, MIMO_PACKAGE)

        try {
            assertTrue("MiMo engine initialization timed out", initialized.await(10, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus.get())
            assertEquals(TextToSpeech.SUCCESS, tts.setVoice(
                tts.voices.orEmpty().single { it.name == "白桦::zh-CN" },
            ))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        result.set(TextToSpeech.SUCCESS)
                        completed.countDown()
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        result.set(errorCode)
                        completed.countDown()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        result.set(TextToSpeech.ERROR)
                        completed.countDown()
                    }
                }
            })
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.speak("MiMo 外部链路测试。", TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId),
            )
            assertTrue("MiMo synthesis timed out", completed.await(60, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, result.get())
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }
}
