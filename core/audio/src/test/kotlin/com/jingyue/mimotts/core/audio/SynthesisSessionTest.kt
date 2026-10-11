package com.jingyue.mimotts.core.audio

import com.jingyue.mimotts.core.spi.AudioChunk
import com.jingyue.mimotts.core.spi.AudioEncoding
import com.jingyue.mimotts.core.spi.AudioSpec
import com.jingyue.mimotts.core.spi.AudioContainer
import com.jingyue.mimotts.core.spi.StructuredError
import com.jingyue.mimotts.core.spi.SynthesisEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SynthesisSessionTest {
    @Test
    fun `cancel is not blocked by a slow output callback`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val output = object : SessionOutput {
            override fun onStarted(spec: AudioSpec) = Unit
            override fun onAudio(bytes: ByteArray, spec: AudioSpec) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            override fun onCompleted() = Unit
            override fun onFailed(error: StructuredError) = Unit
            override fun onCancelled() = Unit
        }
        val session = SynthesisSession("r1", generation = 1, maxBufferSize = 8, output = output)
        val spec = AudioSpec(24_000, AudioEncoding.PCM_16, 1, AudioContainer.RAW_PCM)

        try {
            session.accept(SynthesisEvent.Started("r1", 1, spec))
            val audio = executor.submit {
                session.accept(SynthesisEvent.Audio(AudioChunk("r1", 1, 0, byteArrayOf(1, 2), spec)))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val cancel = executor.submit { session.cancel() }
            try {
                cancel.get(250, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw AssertionError("cancel waited for output callback")
            } finally {
                release.countDown()
            }
            audio.get(1, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `audio is split by callback limit and completion is terminal exactly once`() {
        val output = RecordingSessionOutput()
        val session = SynthesisSession("r1", generation = 3, maxBufferSize = 4, output = output)
        val spec = AudioSpec(24_000, AudioEncoding.PCM_16, 1, AudioContainer.RAW_PCM)

        session.accept(SynthesisEvent.Started("r1", 3, spec))
        session.accept(SynthesisEvent.Audio(AudioChunk("r1", 3, 0, byteArrayOf(1, 2, 3, 4, 5, 6), spec)))
        session.accept(SynthesisEvent.Completed("r1", 3))
        session.accept(SynthesisEvent.Completed("r1", 3))

        assertEquals(listOf("start", "audio:4", "audio:2", "done"), output.events)
    }

    @Test
    fun `old generation cannot complete a stopped session`() {
        val output = RecordingSessionOutput()
        val session = SynthesisSession("r1", generation = 4, maxBufferSize = 8, output = output)
        val spec = AudioSpec(16_000, AudioEncoding.PCM_16, 1, AudioContainer.RAW_PCM)

        session.cancel()
        session.accept(SynthesisEvent.Started("r1", 4, spec))
        session.accept(SynthesisEvent.Completed("r1", 4))

        assertTrue(output.events.isEmpty())
    }

    @Test
    fun `session generated failures expose only stable safe codes`() {
        val output = RecordingSessionOutput()
        val session = SynthesisSession("r1", generation = 4, maxBufferSize = 8, output = output)

        session.accept(SynthesisEvent.Completed("r1", 4))

        assertEquals(listOf("audio.empty"), output.failureCodes)
    }

    private class RecordingSessionOutput : SessionOutput {
        val events = mutableListOf<String>()
        val failureCodes = mutableListOf<String>()

        override fun onStarted(spec: AudioSpec) { events += "start" }
        override fun onAudio(bytes: ByteArray, spec: AudioSpec) { events += "audio:${bytes.size}" }
        override fun onCompleted() { events += "done" }
        override fun onFailed(error: StructuredError) {
            events += "error"
            failureCodes += error.code
        }
        override fun onCancelled() { events += "cancel" }
    }
}
