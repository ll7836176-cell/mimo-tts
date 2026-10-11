package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.AudioChunk
import com.jingyue.mimotts.core.spi.ErrorCategory
import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.MiMoOverridePresenceSnapshot
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot
import com.jingyue.mimotts.core.spi.RequestHandle
import com.jingyue.mimotts.core.spi.StructuredError
import com.jingyue.mimotts.core.spi.SynthesisEvent
import com.jingyue.mimotts.core.spi.SynthesisEventSink
import com.jingyue.mimotts.core.spi.SynthesisProvider
import com.jingyue.mimotts.core.spi.SynthesisRequest
import com.jingyue.mimotts.core.spi.VoiceKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class MiMoTransportResponse(
    val statusCode: Int,
    val contentType: String?,
)

interface MiMoTransportCall {
    fun execute(onLine: (String) -> Unit): MiMoTransportResponse

    /**
     * Delivers response headers before the body is consumed. Implementations
     * that only support the legacy callback remain source-compatible; the
     * default path reports headers after body consumption.
     */
    fun execute(
        onResponse: (MiMoTransportResponse) -> Unit,
        onLine: (String) -> Unit,
    ): MiMoTransportResponse = execute(onLine).also(onResponse)

    fun cancel()
}

fun interface MiMoTransport {
    fun newCall(request: MiMoHttpRequest): MiMoTransportCall
}

class OkHttpMiMoTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
) : MiMoTransport {
    override fun newCall(request: MiMoHttpRequest): MiMoTransportCall {
        val body = request.body.toRequestBody("application/json; charset=utf-8".toMediaType())
        val okHttpRequest = Request.Builder()
            .url(request.url)
            .post(body)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val call = client.newCall(okHttpRequest)
        return object : MiMoTransportCall {
            override fun execute(onLine: (String) -> Unit): MiMoTransportResponse {
                return execute({}, onLine)
            }

            override fun execute(
                onResponse: (MiMoTransportResponse) -> Unit,
                onLine: (String) -> Unit,
            ): MiMoTransportResponse {
                call.execute().use { response ->
                    val transportResponse = MiMoTransportResponse(
                        statusCode = response.code,
                        contentType = response.header("Content-Type"),
                    )
                    onResponse(transportResponse)
                    if (response.code !in 200..299) return transportResponse
                    response.body?.source()?.use { source ->
                        while (!source.exhausted()) {
                            onLine(source.readUtf8Line().orEmpty())
                        }
                    }
                    return transportResponse
                }
            }

            override fun cancel() {
                call.cancel()
            }
        }
    }
}

class MiMoProvider(
    private val transport: MiMoTransport = OkHttpMiMoTransport(),
    private val profiles: Map<String, MiMoVoiceProfile> = defaultProfiles(),
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mimo-provider").apply { isDaemon = true }
    },
    private val composer: MiMoRequestComposer = MiMoRequestComposer(),
    private val parser: MiMoResponseParser = MiMoResponseParser(),
    private val decoder: MiMoAudioDecoder = MiMoAudioDecoder(),
    private val catalog: MiMoVoiceCatalogSnapshot = MiMoVoiceCatalog().snapshot(),
    private val voiceResolver: MiMoVoiceResolver = MiMoVoiceResolver(),
    private val requestFactory: MiMoSynthesisRequestFactory =
        MiMoSynthesisRequestFactory(resolver = voiceResolver),
) : SynthesisProvider, AutoCloseable {
    private class ActiveRequest {
        val cancelled = AtomicBoolean(false)
        val activeCall = AtomicReference<MiMoTransportCall?>(null)
        val future = AtomicReference<Future<*>?>(null)
    }

    private val activeRequests = ConcurrentHashMap.newKeySet<ActiveRequest>()

    override fun synthesize(request: SynthesisRequest, sink: SynthesisEventSink): RequestHandle {
        val state = ActiveRequest()
        activeRequests += state
        val future = executor.submit {
            try {
                runSynthesis(request, sink, state)
            } finally {
                activeRequests.remove(state)
            }
        }
        state.future.set(future)
        return RequestHandle {
            state.cancelled.set(true)
            state.activeCall.getAndSet(null)?.cancel()
            future.cancel(true)
        }
    }

    override fun close() {
        activeRequests.toList().forEach { state ->
            state.cancelled.set(true)
            state.activeCall.getAndSet(null)?.cancel()
            state.future.get()?.cancel(true)
        }
        executor.shutdownNow()
    }

    private fun runSynthesis(
        request: SynthesisRequest,
        sink: SynthesisEventSink,
        state: ActiveRequest,
    ) {
        val cancelled = state.cancelled
        val activeCall = state.activeCall
        var sequence = 0L
        var started = false
        var audioSeen = false
        try {
            catalog.validationError()?.let {
                fail(request, sink, ErrorCategory.INVALID_REQUEST, it)
                return
            }
            val apiKey = request.providerConfig["apiKey"].orEmpty()
            if (apiKey.isBlank()) {
                fail(request, sink, ErrorCategory.NOT_CONFIGURED, MiMoSafeErrorCode.AUTH_FAILURE)
                return
            }
            if (request.segments.isEmpty()) {
                fail(request, sink, ErrorCategory.INVALID_REQUEST, MiMoSafeErrorCode.REQUEST_INVALID)
                return
            }
            if (request.voice != null && catalog.voices.singleOrNull { it == request.voice } == null) {
                fail(request, sink, ErrorCategory.INVALID_REQUEST, MiMoSafeErrorCode.VOICE_INVALID)
                return
            }
            val endpoint = request.providerConfig["endpoint"]
                ?.takeIf { it.isNotBlank() }
                ?: MiMoRequestComposer.DEFAULT_ENDPOINT
            for (segment in request.segments) {
                if (cancelled.get()) return
                val profile = resolveProfile(request, segment.profileId)
                    ?: run {
                        fail(request, sink, ErrorCategory.INVALID_REQUEST, MiMoSafeErrorCode.VOICE_INVALID)
                        return
                    }
                val modelId = request.modelId.ifBlank { profile.modelId }
                val model = MiMoModelProfiles.forModel(modelId)
                    ?: run {
                        fail(request, sink, ErrorCategory.INVALID_REQUEST, MiMoSafeErrorCode.REQUEST_INVALID)
                        return
                    }
                val chunks = MiMoTextChunker.split(
                    segment.text,
                    segment.maxChunkLength ?: model.descriptor.maxTextLength,
                )
                for ((chunkIndex, chunkText) in chunks.withIndex()) {
                    if (cancelled.get()) return
                    val prepared = requestFactory.create(
                        catalog = catalog,
                        overrides = MiMoOverridePresenceSnapshot(
                            voice = FieldState.Present(profile.voiceId.orEmpty()),
                            style = segment.styleInstruction?.let { FieldState.Present<String>(it) }
                                ?: FieldState.Absent,
                        ),
                        frameworkVoiceName = request.voice?.requestVoiceId,
                        endpoint = endpoint,
                        apiKey = apiKey,
                        text = chunkText,
                        baseInstructions = segment.instructions,
                        // A prefix/inline tag is already part of the first chunk's text.
                        // Do not inject it again into later chunks.
                        audioTags = if (chunkIndex == 0) segment.audioTags else emptyList(),
                        modelId = modelId,
                        speechRate = request.speechRate,
                        pitch = request.pitch,
                        volume = request.volume,
                    )
                    val input = when (prepared) {
                        is MiMoSynthesisFactoryResult.Success -> prepared.prepared.input
                        is MiMoSynthesisFactoryResult.Failure -> {
                            fail(
                                request,
                                sink,
                                ErrorCategory.INVALID_REQUEST,
                                prepared.errorCode,
                            )
                            return
                        }
                    }
                    val composed = composer.compose(input).getOrElse {
                        fail(request, sink, errorCategory(it), composeErrorCode(it))
                        return
                    }
                    val call = runCatching { transport.newCall(composed) }.getOrElse {
                        fail(request, sink, errorCategory(it), composeErrorCode(it))
                        return
                    }
                    if (cancelled.get()) {
                        call.cancel()
                        return
                    }
                    activeCall.set(call)
                    if (cancelled.get() && activeCall.compareAndSet(call, null)) {
                        call.cancel()
                        return
                    }

                    val batch = StringBuilder()
                    var streamFailed = false
                    var failureEmitted = false
                    var chunkAudioSeen = false
                    var sseParser: MiMoResponseParser.SseParser? = null
                    fun emitStreamFailure(category: ErrorCategory, code: MiMoSafeErrorCode) {
                        if (failureEmitted) return
                        failureEmitted = true
                        fail(request, sink, category, code)
                    }
                    fun processEvent(event: MiMoResponseEvent) {
                        if (cancelled.get() || streamFailed) return
                        when (event) {
                            is MiMoResponseEvent.Audio -> {
                                val decoded = runCatching {
                                    decoder.decode(event.bytes, event.format, event.sampleRate, event.channelCount)
                                }.getOrElse {
                                    streamFailed = true
                                    emitStreamFailure(ErrorCategory.DECODING, MiMoSafeErrorCode.AUDIO_DECODING)
                                    return
                                }
                                if (!started) {
                                    sink.onEvent(SynthesisEvent.Started(request.requestId, request.generation, decoded.spec))
                                    started = true
                                }
                                sink.onEvent(
                                    SynthesisEvent.Audio(
                                        AudioChunk(
                                            requestId = request.requestId,
                                            generation = request.generation,
                                            sequence = sequence++,
                                            bytes = decoded.pcm16,
                                            spec = decoded.spec,
                                        ),
                                    ),
                                )
                                chunkAudioSeen = true
                                audioSeen = true
                            }
                            is MiMoResponseEvent.Error -> {
                                streamFailed = true
                                emitStreamFailure(errorCategoryForCode(event.code), safeCode(event.code))
                            }
                            MiMoResponseEvent.Done -> Unit
                        }
                    }

                    val response = try {
                        call.execute(
                            onResponse = { headers ->
                                if (isSse(headers.contentType)) {
                                    sseParser = parser.newSseParser(::processEvent)
                                }
                            },
                            onLine = { line ->
                                if (sseParser != null) {
                                    sseParser?.accept(line)
                                } else if (batch.length <= MAX_BATCH_CHARS) {
                                    if (batch.isNotEmpty()) batch.append('\n')
                                    batch.append(line)
                                } else {
                                    streamFailed = true
                                }
                            },
                        )
                    } finally {
                        activeCall.compareAndSet(call, null)
                    }
                    if (cancelled.get()) return
                    if (response.statusCode !in 200..299) {
                        fail(request, sink, statusCategory(response.statusCode), statusCode(response.statusCode))
                        return
                    }
                    if (streamFailed) {
                        emitStreamFailure(ErrorCategory.INVALID_REQUEST, MiMoSafeErrorCode.RESPONSE_INVALID)
                        return
                    }
                    if (sseParser != null) {
                        sseParser?.finish()
                    } else if (batch.isNotBlank()) {
                        val body = batch.toString()
                        if (body.trimStart().startsWith("data:")) {
                            parser.parseSse(body.lines()).forEach(::processEvent)
                        } else {
                            parser.parseBatch(body).forEach(::processEvent)
                        }
                    }
                    if (streamFailed) return
                    if (!chunkAudioSeen) {
                        fail(request, sink, ErrorCategory.EMPTY_AUDIO, MiMoSafeErrorCode.AUDIO_EMPTY)
                        return
                    }
                }
            }
            if (!cancelled.get()) {
                if (audioSeen) sink.onEvent(SynthesisEvent.Completed(request.requestId, request.generation))
                else fail(request, sink, ErrorCategory.EMPTY_AUDIO, MiMoSafeErrorCode.AUDIO_EMPTY)
            }
        } catch (cancelledException: InterruptedException) {
            Thread.currentThread().interrupt()
            if (cancelled.compareAndSet(false, true)) {
                sink.onEvent(SynthesisEvent.Cancelled(request.requestId, request.generation))
            }
        } catch (error: Throwable) {
            if (!cancelled.get()) {
                fail(request, sink, errorCategory(error), composeErrorCode(error))
            }
        } finally {
            activeCall.set(null)
        }
    }

    private fun resolveProfile(request: SynthesisRequest, profileId: String): MiMoVoiceProfile? {
        val catalogVoice = catalog.voices.singleOrNull { it.profileId == profileId }
        if (catalogVoice != null) {
            val resolved = voiceResolver.resolve(catalog, FieldState.Present(catalogVoice.requestVoiceId))
            if (resolved is MiMoVoiceResolution.Success) {
                return MiMoVoiceProfile.builtIn(resolved.voice.profileId, resolved.voice.requestVoiceId)
            }
        }
        profiles[profileId]?.let { profile ->
            if (profile.kind == VoiceKind.BUILT_IN) {
                val candidate = profile.voiceId.orEmpty()
                val resolved = voiceResolver.resolve(catalog, FieldState.Present(candidate))
                if (resolved is MiMoVoiceResolution.Success) return profile
            } else {
                return profile
            }
        }
        val voice = request.voice
        if (voice != null && voice.kind == VoiceKind.BUILT_IN) {
            val resolved = voiceResolver.resolve(catalog, FieldState.Present(voice.requestVoiceId))
            if (resolved is MiMoVoiceResolution.Success && resolved.voice.profileId == profileId) {
                return MiMoVoiceProfile.builtIn(resolved.voice.profileId, resolved.voice.requestVoiceId)
            }
        }
        return null
    }

    private fun fail(
        request: SynthesisRequest,
        sink: SynthesisEventSink,
        category: ErrorCategory,
        code: MiMoSafeErrorCode,
    ) {
        sink.onEvent(
            SynthesisEvent.Failed(
                requestId = request.requestId,
                generation = request.generation,
                error = StructuredError(
                    category = category,
                    message = code.wireValue,
                    retryable = category == ErrorCategory.NETWORK ||
                        category == ErrorCategory.TIMEOUT || category == ErrorCategory.QUOTA,
                    code = code.wireValue,
                ),
            ),
        )
    }

    private fun errorCategory(error: Throwable): ErrorCategory = when (error) {
        is java.net.SocketTimeoutException -> ErrorCategory.TIMEOUT
        is java.io.IOException -> ErrorCategory.NETWORK
        else -> ErrorCategory.INVALID_REQUEST
    }

    private fun statusCategory(status: Int): ErrorCategory = when (status) {
        401, 403 -> ErrorCategory.AUTHENTICATION
        402, 429 -> ErrorCategory.QUOTA
        in 500..599 -> ErrorCategory.NETWORK
        else -> ErrorCategory.INVALID_REQUEST
    }

    private fun statusCode(status: Int): MiMoSafeErrorCode = when (status) {
        401, 403 -> MiMoSafeErrorCode.AUTH_FAILURE
        402, 429 -> MiMoSafeErrorCode.RATE_LIMIT
        in 500..599 -> MiMoSafeErrorCode.NETWORK_FAILURE
        else -> MiMoSafeErrorCode.REQUEST_INVALID
    }

    private fun errorCategoryForCode(code: String): ErrorCategory = when (code) {
        MiMoSafeErrorCode.AUDIO_DECODING.wireValue -> ErrorCategory.DECODING
        MiMoSafeErrorCode.AUDIO_EMPTY.wireValue -> ErrorCategory.EMPTY_AUDIO
        MiMoSafeErrorCode.NETWORK_TIMEOUT.wireValue -> ErrorCategory.TIMEOUT
        MiMoSafeErrorCode.AUTH_FAILURE.wireValue -> ErrorCategory.AUTHENTICATION
        else -> ErrorCategory.INVALID_REQUEST
    }

    private fun safeCode(code: String): MiMoSafeErrorCode =
        MiMoSafeErrorCode.entries.firstOrNull { it.wireValue == code }
            ?: MiMoSafeErrorCode.RESPONSE_INVALID

    private fun composeErrorCode(error: Throwable): MiMoSafeErrorCode = when (error) {
        is java.net.SocketTimeoutException -> MiMoSafeErrorCode.NETWORK_TIMEOUT
        is java.io.IOException -> MiMoSafeErrorCode.NETWORK_FAILURE
        else -> MiMoSafeErrorCode.REQUEST_INVALID
    }

    private fun isSse(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) == true

    companion object {
        private const val MAX_BATCH_CHARS = 16 * 1024 * 1024

        fun defaultProfiles(): Map<String, MiMoVoiceProfile> = listOf(
            "mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean",
        ).associate { voiceId ->
            val profileId = "builtin-$voiceId"
            profileId to MiMoVoiceProfile.builtIn(profileId, voiceId)
        }
    }
}
