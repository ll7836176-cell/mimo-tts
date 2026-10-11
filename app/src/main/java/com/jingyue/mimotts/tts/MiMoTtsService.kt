package com.jingyue.mimotts.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisRequest
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.jingyue.mimotts.core.audio.SynthesisSession
import com.jingyue.mimotts.core.audio.SessionOutput
import com.jingyue.mimotts.core.audio.AudioPlaybackPolicy
import com.jingyue.mimotts.core.audio.Pcm16PlaybackProcessor
import com.jingyue.mimotts.core.rules.RuleContext
import com.jingyue.mimotts.core.rules.RuleEngine
import com.jingyue.mimotts.core.rules.RuleSetSnapshot
import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.ErrorCategory
import com.jingyue.mimotts.core.spi.BundlePrimitiveSnapshot
import com.jingyue.mimotts.core.spi.GenericTtsRequestParamsSnapshot
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import com.jingyue.mimotts.core.spi.ProviderConfigSnapshot
import com.jingyue.mimotts.core.spi.ProviderModuleRegistry
import com.jingyue.mimotts.core.spi.RequestSource
import com.jingyue.mimotts.core.spi.SynthesisProvider
import com.jingyue.mimotts.core.spi.SynthesisRequest as CoreSynthesisRequest
import com.jingyue.mimotts.core.spi.StructuredError
import com.jingyue.mimotts.core.spi.VoiceDefinition
import com.jingyue.mimotts.core.spi.VoiceIdCodec
import com.jingyue.mimotts.provider.mimo.MiMoPlugin
import com.jingyue.mimotts.provider.mimo.MiMoModelIds
import com.jingyue.mimotts.provider.mimo.MiMoVoiceCatalog
import com.jingyue.mimotts.provider.mimo.MiMoVoiceResolver
import com.jingyue.mimotts.data.PreferencesStore
import com.jingyue.mimotts.data.RuleFileStore
import com.jingyue.mimotts.data.SecretStore
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class MiMoTtsService : TextToSpeechService() {
    private val generation = AtomicLong(0)
    private val lock = Any()
    private val ruleEngine = RuleEngine()
    private val catalogOwner = MiMoVoiceCatalog()
    private val catalogSnapshot = catalogOwner.snapshot()
    private val voiceResolver = MiMoVoiceResolver()
    private val requestParamsParser = MiMoRequestParamsParser()
    private lateinit var provider: SynthesisProvider
    private lateinit var preferences: PreferencesStore
    private lateinit var ruleStore: RuleFileStore
    private lateinit var secrets: SecretStore
    private var activeHandle: com.jingyue.mimotts.core.spi.RequestHandle? = null
    private var activeSession: SynthesisSession? = null
    private var activeOutput: AndroidSessionOutput? = null

    override fun onCreate() {
        super.onCreate()
        val configuredProvider = ProviderModuleRegistry.create(
            listOf(
                MiMoPlugin(
                    catalogOwner = catalogOwner,
                    catalogSnapshot = catalogSnapshot,
                ),
            ),
        ).get("mimo")?.createProvider()
            ?: error("MiMo provider module is not registered")
        provider = MiMoTargetProcessHooks.providerOr(configuredProvider)
        preferences = PreferencesStore(this)
        ruleStore = RuleFileStore(File(filesDir, "rules"))
        secrets = SecretStore(this)
    }

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CHN", "")

    override fun onIsLanguageAvailable(language: String?, country: String?, variant: String?): Int =
        LocalePolicy.availability(language, country)

    override fun onLoadLanguage(language: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(language, country, variant)

    override fun onGetVoices(): List<Voice> = catalogSnapshot.voices.map { voice ->
        Voice(
            VoiceIdCodec.toTtsServerName(voice),
            voice.locale,
            Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL,
            true,
            emptySet(),
        )
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (findKnownVoice(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        return if (findKnownVoice(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(language: String?, country: String?, variant: String?): String? {
        val requestedLanguage = when (language?.lowercase(Locale.ROOT)) {
            "zho", "chi" -> "zh"
            "eng" -> "en"
            else -> language?.lowercase(Locale.ROOT)
        }
        val configured = if (::preferences.isInitialized) {
            (voiceResolver.resolveRaw(catalogSnapshot, preferences.voiceProfileId)
                as? com.jingyue.mimotts.provider.mimo.MiMoVoiceResolution.Success)?.voice
        } else {
            null
        }
        val configuredForLanguage = configured?.takeIf {
            it.locale.language.equals(requestedLanguage, ignoreCase = true)
        }
        val defaultId = when (requestedLanguage) {
            "zh" -> MiMoVoiceCatalog.DEFAULT_VOICE_ID
            "en" -> "Mia"
            else -> return null
        }
        return (configuredForLanguage ?: catalogSnapshot.voiceById(defaultId))?.let(VoiceIdCodec::toTtsServerName)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val requestGeneration = generation.incrementAndGet()
        synchronized(lock) {
            activeHandle?.cancel()
            activeSession?.cancel()
            activeHandle = null
            activeSession = null
        }

        val params = when (val result = requestParamsParser.parse(request.params)) {
            is MiMoRequestParamsParser.Result.Success -> result
            is MiMoRequestParamsParser.Result.Failure -> {
                finishCallbackError(callback, TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
        }

        if (request.text.isNullOrBlank()) {
            if (callback.start(16_000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                callback.done()
            } else {
                finishCallbackError(callback, TextToSpeech.ERROR_OUTPUT)
            }
            return
        }

        val configuredModel = preferences.modelId.ifBlank { MiMoModelIds.BUILT_IN }
        if (configuredModel != MiMoModelIds.BUILT_IN) {
            finishCallbackError(callback, TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val voiceOverride = if (params.overrides.voice is FieldState.Absent && request.voiceName.isNullOrBlank()) {
            FieldState.Present(preferences.voiceProfileId)
        } else {
            params.overrides.voice
        }
        val voice = (voiceResolver.resolve(
                snapshot = catalogSnapshot,
                override = voiceOverride,
                frameworkVoiceName = request.voiceName,
            ) as? com.jingyue.mimotts.provider.mimo.MiMoVoiceResolution.Success
            )?.voice ?: run {
            finishCallbackError(callback, TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val style = when (val state = params.overrides.style) {
            FieldState.Absent -> null
            is FieldState.Present -> state.value
            is FieldState.Invalid -> {
                finishCallbackError(callback, TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
        }
        val baseInstructions = parseBaseInstructions(params.generic) ?: run {
            finishCallbackError(callback, TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val apiKey = secrets.getApiKey().orEmpty()
        val ruleSnapshot = ruleStore.load() ?: RuleSetSnapshot(
            id = "default",
            version = 1,
            serverOwnsPreprocessing = preferences.serverOwnsPreprocessing,
            rules = emptyList(),
        )
        val preview = ruleEngine.preview(
            originalText = request.text,
            snapshot = ruleSnapshot,
            context = RuleContext(
                providerId = "mimo",
                modelId = voice.modelId,
                locale = voice.locale,
                profileId = voice.profileId,
            ),
        )
        val segments = ruleEngine.toSynthesisSegments(preview).map { segment ->
            segment.copy(
                instructions = baseInstructions + segment.instructions,
                styleInstruction = style,
            )
        }
        val playbackParameters = AudioPlaybackPolicy.fromAndroidPercent(
            request.speechRate,
            request.pitch,
        )
        val coreRequest = CoreSynthesisRequest(
            requestId = UUID.randomUUID().toString(),
            generation = requestGeneration,
            attempt = 1,
            originalText = request.text,
            segments = segments,
            locale = voice.locale,
            voice = voice,
            providerId = "mimo",
            modelId = configuredModel,
            profileId = voice.profileId,
            speechRate = playbackParameters.speechRate,
            pitch = playbackParameters.pitch,
            volume = 1f,
            source = RequestSource.SYSTEM_TTS,
            providerConfig = ProviderConfigSnapshot(
                mapOf(
                    "apiKey" to apiKey,
                    "endpoint" to preferences.endpoint,
                ),
            ),
            ruleSetId = ruleSnapshot.id,
            ruleSetVersion = ruleSnapshot.version,
        )
        val output = AndroidSessionOutput(callback, playbackParameters)
        val session = SynthesisSession(
            requestId = coreRequest.requestId,
            generation = requestGeneration,
            maxBufferSize = callback.maxBufferSize.coerceAtLeast(1),
            output = output,
        )
        synchronized(lock) {
            activeSession = session
            activeOutput = output
        }
        val handle = try {
            provider.synthesize(coreRequest) { event ->
                MiMoTargetProcessHooks.recordSinkEvent()
                if (generation.get() == requestGeneration) session.accept(event)
            }
        } catch (_: Throwable) {
            output.requestFailure()
            output.drainOnSynthesisThread()
            synchronized(lock) {
                if (activeSession === session) activeSession = null
                if (activeOutput === output) activeOutput = null
            }
            return
        }
        synchronized(lock) {
            activeHandle = handle
            if (generation.get() != requestGeneration) {
                activeHandle = null
                handle.cancel()
                output.requestCancellation()
            }
        }
        try {
            // Android requires all SynthesisCallback methods on this thread and
            // requires onSynthesizeText to remain blocked until the request ends.
            output.drainOnSynthesisThread()
            if (output.shouldCancelProducer()) handle.cancel()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            output.requestCancellation()
            handle.cancel()
        } finally {
            synchronized(lock) {
                if (activeHandle === handle) activeHandle = null
                if (activeSession === session) activeSession = null
                if (activeOutput === output) activeOutput = null
            }
        }
    }

    override fun onStop() {
        generation.incrementAndGet()
        var outputToCancel: AndroidSessionOutput? = null
        synchronized(lock) {
            activeSession?.cancel()
            activeHandle?.cancel()
            outputToCancel = activeOutput
            activeSession = null
            activeHandle = null
            activeOutput = null
        }
        outputToCancel?.requestCancellation()
    }

    override fun onDestroy() {
        onStop()
        (provider as? AutoCloseable)?.close()
        super.onDestroy()
    }

    private fun finishCallbackError(callback: SynthesisCallback, errorCode: Int) {
        runCatching { callback.error(errorCode) }
        runCatching { callback.done() }
    }

    private fun findKnownVoice(name: String?): VoiceDefinition? =
        name?.let { requestedName ->
            (voiceResolver.resolve(catalogSnapshot, FieldState.Present(requestedName))
                as? com.jingyue.mimotts.provider.mimo.MiMoVoiceResolution.Success)?.voice
        }

    private fun parseBaseInstructions(snapshot: GenericTtsRequestParamsSnapshot): List<String>? =
        snapshot.entries.filter { it.key == MiMoRequestParamsParser.KEY_BASE_INSTRUCTION }.map { entry ->
            (entry.value as? BundlePrimitiveSnapshot.StringValue)?.value ?: return null
        }

    private class AndroidSessionOutput(
        private val callback: SynthesisCallback,
        private val playbackParameters: com.jingyue.mimotts.core.audio.PlaybackParameters,
    ) : SessionOutput {
        private sealed interface OutputEvent {
            data class Started(val spec: com.jingyue.mimotts.core.spi.AudioSpec) : OutputEvent
            data class Audio(
                val bytes: ByteArray,
                val spec: com.jingyue.mimotts.core.spi.AudioSpec,
            ) : OutputEvent
            data object Completed : OutputEvent
            data class Failed(val error: StructuredError) : OutputEvent
            data object Cancelled : OutputEvent
        }

        private val events = LinkedBlockingQueue<OutputEvent>()
        private val audioBudget = Semaphore(AUDIO_QUEUE_BUDGET_BYTES, true)
        private val terminalQueued = AtomicBoolean(false)
        private val cancellationRequested = AtomicBoolean(false)
        private val callbackFailure = AtomicBoolean(false)
        private val callbackTerminated = AtomicBoolean(false)
        private var playbackProcessor: Pcm16PlaybackProcessor? = null

        override fun onStarted(spec: com.jingyue.mimotts.core.spi.AudioSpec) {
            if (!terminalQueued.get()) events.offer(OutputEvent.Started(spec))
        }

        override fun onAudio(bytes: ByteArray, spec: com.jingyue.mimotts.core.spi.AudioSpec) {
            if (bytes.isEmpty()) return
            var offset = 0
            while (offset < bytes.size && !terminalQueued.get()) {
                val length = minOf(AUDIO_QUEUE_CHUNK_BYTES, bytes.size - offset)
                if (!acquireAudioBudget(length)) return
                val chunk = bytes.copyOfRange(offset, offset + length)
                if (terminalQueued.get() || !events.offer(OutputEvent.Audio(chunk, spec))) {
                    audioBudget.release(length)
                    return
                }
                offset += length
            }
        }

        override fun onCompleted() {
            if (terminalQueued.compareAndSet(false, true)) events.offer(OutputEvent.Completed)
        }

        override fun onFailed(error: StructuredError) {
            if (terminalQueued.compareAndSet(false, true)) events.offer(OutputEvent.Failed(error))
        }

        override fun onCancelled() {
            requestCancellation()
        }

        fun requestFailure() {
            if (terminalQueued.compareAndSet(false, true)) {
                events.offer(
                    OutputEvent.Failed(
                        StructuredError(
                            category = ErrorCategory.UNKNOWN,
                            message = MiMoSafeErrorCode.INTERNAL.wireValue,
                            code = MiMoSafeErrorCode.INTERNAL.wireValue,
                        ),
                    ),
                )
            }
        }

        fun requestCancellation() {
            cancellationRequested.set(true)
            terminalQueued.set(true)
            while (true) {
                val queued = events.poll() ?: break
                releaseAudioBudget(queued)
            }
            events.offer(OutputEvent.Cancelled)
        }

        fun shouldCancelProducer(): Boolean = callbackFailure.get() || cancellationRequested.get()

        fun drainOnSynthesisThread() {
            while (true) {
                when (val event = events.take()) {
                    is OutputEvent.Started -> {
                        if (cancellationRequested.get()) continue
                        if (callback.start(
                                event.spec.sampleRate,
                                AudioFormat.ENCODING_PCM_16BIT,
                                event.spec.channelCount,
                            ) != TextToSpeech.SUCCESS
                        ) {
                            deliverFailure(cancelProducer = true)
                            return
                        }
                        playbackProcessor = if (
                            playbackParameters.speechRate == 1f && playbackParameters.pitch == 1f
                        ) {
                            null
                        } else {
                            Pcm16PlaybackProcessor(
                                speechRate = playbackParameters.speechRate,
                                pitch = playbackParameters.pitch,
                                maxOutputBytes = callback.maxBufferSize.coerceAtLeast(1),
                                output = { bytes ->
                                    callback.audioAvailable(bytes, 0, bytes.size) == TextToSpeech.SUCCESS
                                },
                            )
                        }
                    }
                    is OutputEvent.Audio -> {
                        try {
                            if (cancellationRequested.get()) continue
                            val processor = playbackProcessor
                            if (processor == null) {
                                if (!writeAudio(event.bytes)) {
                                    deliverFailure(cancelProducer = true)
                                    return
                                }
                            } else if (!processor.accept(
                                    event.bytes,
                                    event.spec.sampleRate,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    event.spec.channelCount,
                                )
                            ) {
                                deliverFailure(cancelProducer = true)
                                return
                            }
                        } finally {
                            audioBudget.release(event.bytes.size)
                        }
                    }
                    OutputEvent.Completed -> {
                        if (cancellationRequested.get()) continue
                        if (playbackProcessor?.finish() == false) {
                            deliverFailure(cancelProducer = true)
                            return
                        }
                        deliverDone()
                        return
                    }
                    is OutputEvent.Failed -> {
                        if (cancellationRequested.get()) continue
                        playbackProcessor?.cancel()
                        deliverFailure(event.error)
                        return
                    }
                    OutputEvent.Cancelled -> {
                        playbackProcessor?.cancel()
                        deliverFailure(cancelProducer = true)
                        return
                    }
                }
            }
        }

        private fun writeAudio(transformed: ByteArray): Boolean {
            var offset = 0
            while (offset < transformed.size) {
                val length = minOf(callback.maxBufferSize.coerceAtLeast(1), transformed.size - offset)
                if (callback.audioAvailable(transformed, offset, length) != TextToSpeech.SUCCESS) {
                    return false
                }
                offset += length
            }
            return true
        }

        private fun deliverFailure(
            error: StructuredError? = null,
            cancelProducer: Boolean = false,
        ) {
            if (cancelProducer) callbackFailure.set(true)
            if (!callbackTerminated.compareAndSet(false, true)) return
            runCatching {
                callback.error(
                    if (error?.category == ErrorCategory.INVALID_REQUEST) {
                        TextToSpeech.ERROR_INVALID_REQUEST
                    } else {
                        TextToSpeech.ERROR_SYNTHESIS
                    },
                )
            }
            runCatching { callback.done() }
        }

        private fun deliverDone() {
            if (callbackTerminated.compareAndSet(false, true)) {
                runCatching { callback.done() }
            }
        }

        private fun acquireAudioBudget(length: Int): Boolean {
            while (!terminalQueued.get() && !cancellationRequested.get()) {
                if (audioBudget.tryAcquire(length, 50, TimeUnit.MILLISECONDS)) return true
            }
            return false
        }

        private fun releaseAudioBudget(event: OutputEvent) {
            if (event is OutputEvent.Audio) audioBudget.release(event.bytes.size)
        }

        companion object {
            private const val AUDIO_QUEUE_BUDGET_BYTES = 256 * 1024
            private const val AUDIO_QUEUE_CHUNK_BYTES = 16 * 1024
        }
    }
}
