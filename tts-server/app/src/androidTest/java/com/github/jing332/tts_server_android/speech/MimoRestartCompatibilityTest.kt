package com.github.jing332.tts_server_android.speech

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device regression coverage for the real chain:
 * reader -> tts-server -> MiMoTTS.
 *
 * Opening the MiMoTTS settings activity must not leave an already-created
 * tts-server client unable to complete the next utterance.
 */
@RunWith(AndroidJUnit4::class)
class MimoRestartCompatibilityTest {
    companion object {
        private const val SERVER_ENGINE = "com.github.jing332.tts_server_android.debug"
        private const val MIMO_PACKAGE = "com.jingyue.mimotts"
        private const val INIT_TIMEOUT_SECONDS = 10L
        private const val SPEECH_TIMEOUT_SECONDS = 45L
    }

    @Test
    fun openingMimoSettingsDoesNotSilenceTheNextTtsServerUtterance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Start MiMo once so the first utterance proves the configured route is healthy.
        openMimoSettings(context, instrumentation)
        val tts = createTts(context)

        try {
            assertEquals(TextToSpeech.SUCCESS, speakAndAwait(tts, "MiMo 重启前测试"))

            // Reproduce the Huawei/Android lifecycle seen in the field: the child
            // engine process dies while tts-server keeps its TextToSpeech wrapper.
            instrumentation.uiAutomation.executeShellCommand("am force-stop $MIMO_PACKAGE").close()
            Thread.sleep(500)
            openMimoSettings(context, instrumentation)

            assertEquals(TextToSpeech.SUCCESS, speakAndAwait(tts, "MiMo 重启后测试"))
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    @Test
    fun killingMimoDuringActiveUtteranceDoesNotLeaveTtsClientStuck() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        openMimoSettings(context, instrumentation)
        val tts = createTts(context)

        try {
            assertEquals(TextToSpeech.SUCCESS, speakAndAwait(tts, "MiMo 中途退出测试预热"))

            val active = startSpeech(
                tts,
                "这是一段用于验证 MiMo 引擎进程在朗读过程中退出后请求能够结束并恢复的较长测试内容。".repeat(8),
            )
            assertEquals(TextToSpeech.SUCCESS, active.immediate)
            // Kill the child while the request is in flight, before the
            // provider normally returns its first audio callback.
            Thread.sleep(250)

            instrumentation.uiAutomation.executeShellCommand("am force-stop $MIMO_PACKAGE").close()

            assertTrue(
                "tts-server left the in-flight utterance waiting after MiMo died",
                active.completed.await(20, TimeUnit.SECONDS),
            )
            assertTrue(
                "the interrupted utterance unexpectedly completed successfully",
                active.result.get() != TextToSpeech.SUCCESS,
            )

            openMimoSettings(context, instrumentation)
            assertEquals(TextToSpeech.SUCCESS, speakAndAwait(tts, "MiMo 中途重启后恢复测试"))
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    private fun openMimoSettings(context: Context, instrumentation: Instrumentation) {
        check(context.packageManager.getLaunchIntentForPackage(MIMO_PACKAGE) != null) {
            "MiMoTTS is not installed on the test device"
        }
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $MIMO_PACKAGE/com.jingyue.mimotts.ui.MainActivity"
        ).close()
        // A cold start on the Huawei test phone takes longer than the activity
        // launch call; wait until the TTS service has had time to register.
        Thread.sleep(4_000)
    }

    private fun createTts(context: Context): TextToSpeech {
        val initialized = CountDownLatch(1)
        val status = AtomicInteger(TextToSpeech.ERROR)
        val tts = TextToSpeech(context, { result ->
            status.set(result)
            initialized.countDown()
        }, SERVER_ENGINE)
        assertTrue(
            "tts-server did not initialize within ${INIT_TIMEOUT_SECONDS}s",
            initialized.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertEquals(TextToSpeech.SUCCESS, status.get())
        return tts
    }

    private fun speakAndAwait(tts: TextToSpeech, text: String): Int {
        val completed = CountDownLatch(1)
        val result = AtomicInteger(TextToSpeech.ERROR)
        val utteranceId = "mimo-restart-${System.nanoTime()}"
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
        val immediate = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (immediate != TextToSpeech.SUCCESS) return immediate
        if (!completed.await(SPEECH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return TextToSpeech.ERROR_SYNTHESIS
        }
        return result.get()
    }

    private fun startSpeech(tts: TextToSpeech, text: String): SpeechProgress {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicInteger(TextToSpeech.ERROR)
        val utteranceId = "mimo-active-${System.nanoTime()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) started.countDown()
            }

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
        val immediate = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (immediate != TextToSpeech.SUCCESS) {
            result.set(immediate)
            completed.countDown()
        }
        return SpeechProgress(started, completed, result, immediate)
    }

    private data class SpeechProgress(
        val started: CountDownLatch,
        val completed: CountDownLatch,
        val result: AtomicInteger,
        val immediate: Int,
    )
}
