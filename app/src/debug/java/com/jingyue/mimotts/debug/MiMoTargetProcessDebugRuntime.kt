package com.jingyue.mimotts.debug

import com.jingyue.mimotts.core.spi.AudioChunk
import com.jingyue.mimotts.core.spi.AudioContainer
import com.jingyue.mimotts.core.spi.AudioEncoding
import com.jingyue.mimotts.core.spi.AudioSpec
import com.jingyue.mimotts.core.spi.ErrorCategory
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import com.jingyue.mimotts.core.spi.RequestHandle
import com.jingyue.mimotts.core.spi.StructuredError
import com.jingyue.mimotts.core.spi.SynthesisEvent
import com.jingyue.mimotts.core.spi.SynthesisEventSink
import com.jingyue.mimotts.core.spi.SynthesisProvider
import com.jingyue.mimotts.core.spi.SynthesisRequest
import com.jingyue.mimotts.tts.MiMoTargetProcessHooks
import java.util.concurrent.atomic.AtomicBoolean

/** All fake transport/provider state is debug-only and target-process local. */
object MiMoTargetProcessDebugRuntime {
    data class CounterSnapshot(
        val providerRequests: Int,
        val factoryCreates: Int,
        val transportRequests: Int,
        val chunkEvents: Int,
        val sinkEvents: Int,
    )

    private data class FakeConfig(
        val mode: String,
        val chunks: Int,
        val failureCode: MiMoSafeErrorCode,
    )

    private var fakeConfig: FakeConfig? = null
    private var providerRequests = 0
    private var factoryCreates = 0
    private var transportRequests = 0
    private var chunkEvents = 0
    private var sinkEvents = 0

    @Synchronized
    fun installFake(mode: String, chunks: Int, failureCode: String?): Boolean {
        if (mode !in setOf(
                MiMoTargetProcessTestSeam.MODE_FAKE_SUCCESS,
                MiMoTargetProcessTestSeam.MODE_FAKE_FAILURE,
                MiMoTargetProcessTestSeam.MODE_FAKE_CANCEL,
            )
        ) return false
        if (chunks !in 1..16) return false
        val safeFailureCode = MiMoSafeErrorCode.entries.firstOrNull {
            it.wireValue == failureCode
        } ?: MiMoSafeErrorCode.PROVIDER_UNAVAILABLE
        fakeConfig = FakeConfig(mode, chunks, safeFailureCode)
        resetCountersLocked()
        MiMoTargetProcessHooks.install(FakeProvider(fakeConfig!!), ::recordSinkEvent)
        return true
    }

    @Synchronized
    fun installRecordOnly(): Boolean {
        fakeConfig = null
        resetCountersLocked()
        MiMoTargetProcessHooks.installDecorator(
            decorator = { provider -> RecordingProvider(provider) },
            sinkEventRecorder = ::recordSinkEvent,
        )
        return true
    }

    @Synchronized
    fun clear() {
        fakeConfig = null
        resetCountersLocked()
        MiMoTargetProcessHooks.clear()
    }

    @Synchronized
    fun snapshotCounters(): CounterSnapshot = CounterSnapshot(
        providerRequests = providerRequests,
        factoryCreates = factoryCreates,
        transportRequests = transportRequests,
        chunkEvents = chunkEvents,
        sinkEvents = sinkEvents,
    )

    @Synchronized
    private fun recordFactoryCreate() {
        factoryCreates++
    }

    @Synchronized
    private fun recordTransportRequest() {
        transportRequests++
    }

    @Synchronized
    private fun recordChunkEvent() {
        chunkEvents++
    }

    @Synchronized
    private fun recordSinkEvent() {
        sinkEvents++
    }

    private fun resetCountersLocked() {
        providerRequests = 0
        factoryCreates = 0
        transportRequests = 0
        chunkEvents = 0
        sinkEvents = 0
    }

    private class FakeProvider(private val config: FakeConfig) : SynthesisProvider {
        override fun synthesize(request: SynthesisRequest, sink: SynthesisEventSink): RequestHandle {
            val cancelled = AtomicBoolean(false)
            synchronized(MiMoTargetProcessDebugRuntime) {
                providerRequests++
            }
            if (config.mode == MiMoTargetProcessTestSeam.MODE_FAKE_CANCEL) {
                sink.onEvent(SynthesisEvent.Cancelled(request.requestId, request.generation))
                return RequestHandle { cancelled.set(true) }
            }
            if (config.mode == MiMoTargetProcessTestSeam.MODE_FAKE_FAILURE) {
                synchronized(MiMoTargetProcessDebugRuntime) {
                    factoryCreates++
                    transportRequests++
                }
                sink.onEvent(
                    SynthesisEvent.Failed(
                        requestId = request.requestId,
                        generation = request.generation,
                        error = StructuredError(
                            category = ErrorCategory.NETWORK,
                            message = config.failureCode.wireValue,
                            retryable = false,
                            code = config.failureCode.wireValue,
                        ),
                    ),
                )
                return RequestHandle { cancelled.set(true) }
            }

            val spec = AudioSpec(
                sampleRate = 24_000,
                encoding = AudioEncoding.PCM_16,
                channelCount = 1,
                container = AudioContainer.RAW_PCM,
            )
            sink.onEvent(SynthesisEvent.Started(request.requestId, request.generation, spec))
            repeat(config.chunks) { sequence ->
                if (!cancelled.get()) {
                    synchronized(MiMoTargetProcessDebugRuntime) {
                        factoryCreates++
                        transportRequests++
                        chunkEvents++
                    }
                    sink.onEvent(
                        SynthesisEvent.Audio(
                            AudioChunk(
                                requestId = request.requestId,
                                generation = request.generation,
                                sequence = sequence.toLong(),
                                bytes = byteArrayOf(0, 0),
                                spec = spec,
                            ),
                        ),
                    )
                }
            }
            if (!cancelled.get()) {
                sink.onEvent(SynthesisEvent.Completed(request.requestId, request.generation))
            }
            return RequestHandle { cancelled.set(true) }
        }
    }

    private class RecordingProvider(
        private val delegate: SynthesisProvider,
    ) : SynthesisProvider {
        override fun synthesize(request: SynthesisRequest, sink: SynthesisEventSink): RequestHandle {
            synchronized(MiMoTargetProcessDebugRuntime) {
                providerRequests++
                factoryCreates++
                transportRequests++
            }
            return delegate.synthesize(request) { event -> sink.onEvent(event) }
        }
    }
}
