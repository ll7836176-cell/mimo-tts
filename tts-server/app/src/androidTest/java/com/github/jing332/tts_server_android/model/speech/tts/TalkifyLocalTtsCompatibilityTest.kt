package com.github.jing332.tts_server_android.model.speech.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke coverage for the Android engine boundary. */
@RunWith(AndroidJUnit4::class)
class TalkifyLocalTtsCompatibilityTest {
    companion object {
        private const val TALKIFY_PACKAGE = "com.github.lonepheasantwarrior.talkify"
    }

    @Test
    fun talkifyIsDiscoverableAndAdvertisesStableVoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        val tts = TextToSpeech(context, { status ->
            initStatus = status
            ready.countDown()
        }, TALKIFY_PACKAGE)

        assertTrue("Talkify did not initialize within 8 seconds", ready.await(8, TimeUnit.SECONDS))
        assertEquals(TextToSpeech.SUCCESS, initStatus)

        val voices = tts.voices.orEmpty().toList()
        assumeTrue("Talkify has no configured voices on this device", voices.isNotEmpty())
        val names = voices.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertFalse(names.any { it.contains(":::") })

        tts.shutdown()
    }
}
