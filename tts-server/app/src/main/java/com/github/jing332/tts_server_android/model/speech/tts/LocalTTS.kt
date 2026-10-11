package com.github.jing332.tts_server_android.model.speech.tts

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.data.entities.systts.AudioParams
import com.github.jing332.tts_server_android.data.entities.systts.SpeechRuleInfo
import com.github.jing332.tts_server_android.model.speech.ITextToSpeechSynthesizer
import com.github.jing332.tts_server_android.ui.systts.edit.local.LocalTtsEditActivity
import com.github.jing332.tts_server_android.ui.systts.edit.local.LocalTtsParamsEditView
import com.github.jing332.tts_server_android.utils.toHtmlBold
import kotlinx.coroutines.*
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Parcelize
@Serializable
@SerialName("local")
data class LocalTTS(
    var engine: String? = null,
    override var locale: String = "",
    var voiceName: String? = null,

    var extraParams: MutableList<LocalTtsParameter>? = null,

    var isDirectPlayMode: Boolean = true,

    override var pitch: Int = 0,
    override var volume: Int = 0,
    override var rate: Int = 0,

    override var audioPlayer: PlayerParams = PlayerParams(),
    override var audioFormat: BaseAudioFormat = BaseAudioFormat(isNeedDecode = true),
    override var audioParams: AudioParams = AudioParams(),
    @Transient
    override var speechRule: SpeechRuleInfo = SpeechRuleInfo(),
) : Parcelable, ITextToSpeechEngine() {
    companion object {
        private const val TAG = "LocalTTS"
        private const val INIT_TIMEOUT_MILLIS = 8_000L
        private const val DIRECT_ENGINE_START_TIMEOUT_MILLIS = 15_000L
        private const val DIRECT_ENGINE_CHECK_INTERVAL_MILLIS = 500L
        private const val DIRECT_ENGINE_SILENCE_GRACE_MILLIS = 1_500L

        private val saveDir by lazy {
            File(App.context.cacheDir, "local_tts_audio")
        }

        init {
            kotlin.runCatching { saveDir.deleteRecursively() }
        }
    }

    init {
        audioFormat.isNeedDecode = true
    }

    override fun getEditActivity(): Class<out Activity> = LocalTtsEditActivity::class.java

    override fun getType() = App.context.getString(R.string.local)

    override fun getBottomContent() = audioFormat.toString()

    override fun getDescription(): String {
        val rateStr = if (isRateFollowSystem()) {
            App.context.getString(R.string.follow)
        } else {
            "%.2fx".format(Locale.US, LocalTtsParameterPolicy.rateMultiplier(rate))
        }
        val pitchStr =
            if (isPitchFollowSystem()) App.context.getString(R.string.follow)
            else "%.2fx".format(Locale.US, LocalTtsParameterPolicy.pitchMultiplier(pitch))
        return "${voiceName ?: App.context.getString(R.string.default_str)} <br>" + App.context.getString(
            R.string.systts_play_params_description,
            "$rateStr".toHtmlBold(),
            "<b>0</b>",
            "$pitchStr".toHtmlBold()
        )
    }

    override fun getParamsEditView(context: Context) = LocalTtsParamsEditView(context) to false

    private enum class EngineStatus { UNINITIALIZED, INITIALIZING, READY, FAILED }

    @IgnoredOnParcel
    @Transient
    @Volatile
    private var mTtsEngine: TextToSpeech? = null

    @IgnoredOnParcel
    @Transient
    @Volatile
    private var engineStatus: EngineStatus = EngineStatus.UNINITIALIZED

    @IgnoredOnParcel
    @Transient
    @Volatile
    private var readyEnginePackage: String? = null

    @IgnoredOnParcel
    @Transient
    private val initMutex = Mutex()

    @IgnoredOnParcel
    @Transient
    private val requestGeneration = AtomicLong(0L)

    @IgnoredOnParcel
    @Transient
    private val activeRequests = ConcurrentHashMap<String, LocalTtsRequestState>()

    @IgnoredOnParcel
    @Transient
    private var compatibilityFile: File? = null

    @IgnoredOnParcel
    @Transient
    var engineListener: EngineProgressListener? = null

    interface EngineProgressListener {
        fun onStart() {}
        fun onDone() {}
        fun onError(errorCode: Int) {}
        fun onStop() {}
    }

    override fun onLoad() {}

    override fun onStop() {
        Log.i(TAG, "onStop")
        requestGeneration.incrementAndGet()
        activeRequests.values.forEach { it.stop() }
        activeRequests.clear()
        mTtsEngine?.stop()
        // A stop can also be caused by the child engine process dying. Do not
        // keep the wrapper around: Android may report it as READY even though
        // its Binder connection is already dead.
        invalidateEngine("stop")
        engineListener?.onStop()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        requestGeneration.incrementAndGet()
        activeRequests.values.forEach { it.stop() }
        activeRequests.clear()
        compatibilityFile?.let { kotlin.runCatching { it.delete() } }
        compatibilityFile = null
        shutdownEngine()
    }

    private suspend fun ensureEngineReady(): Boolean = initMutex.withLock {
        if (engineStatus == EngineStatus.READY &&
            mTtsEngine != null &&
            readyEnginePackage == engine
        ) return@withLock true

        shutdownEngine()
        engineStatus = EngineStatus.INITIALIZING
        val initResult = CompletableDeferred<Int>()
        val tts = try {
            TextToSpeech(App.context, { code ->
                initResult.complete(code)
            }, engine)
        } catch (e: Throwable) {
            engineStatus = EngineStatus.FAILED
            Log.w(TAG, "TextToSpeech construction failed: ${e.javaClass.simpleName}")
            return@withLock false
        }
        mTtsEngine = tts

        val result = try {
            withTimeout(INIT_TIMEOUT_MILLIS) { initResult.await() }
        } catch (_: TimeoutCancellationException) {
            shutdownEngine()
            engineStatus = EngineStatus.FAILED
            null
        } catch (e: CancellationException) {
            shutdownEngine()
            engineStatus = EngineStatus.FAILED
            throw e
        } catch (_: Throwable) {
            null
        }

        if (result != TextToSpeech.SUCCESS) {
            shutdownEngine()
            engineStatus = EngineStatus.FAILED
            return@withLock false
        }

        installProgressListener(tts)
        readyEnginePackage = engine
        engineStatus = EngineStatus.READY
        true
    }

    private fun shutdownEngine() {
        val tts = mTtsEngine
        mTtsEngine = null
        readyEnginePackage = null
        engineStatus = EngineStatus.UNINITIALIZED
        runCatching { tts?.shutdown() }
    }

    private fun invalidateEngine(reason: String) {
        Log.w(TAG, "invalidating cached TextToSpeech engine: $reason")
        shutdownEngine()
    }

    private fun currentRequest(utteranceId: String?): LocalTtsRequestState? {
        val id = utteranceId ?: return null
        val state = activeRequests[id] ?: return null
        return state.takeIf {
            requestGeneration.get() == it.generation && it.accepts(id, it.generation)
        }
    }

    private fun installProgressListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                currentRequest(utteranceId)?.let {
                    it.markStarted()
                    engineListener?.onStart()
                }
            }

            override fun onBeginSynthesis(
                utteranceId: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                currentRequest(utteranceId)?.let {
                    it.markStarted()
                    it.recordDescriptor(
                        utteranceId,
                        requestGeneration.get(),
                        AudioDescriptor(sampleRateInHz, audioFormat, channelCount)
                    )
                }
            }

            override fun onDone(utteranceId: String?) {
                currentRequest(utteranceId)?.let {
                    if (it.done()) engineListener?.onDone()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                currentRequest(utteranceId)?.let {
                    if (it.failure(
                        category = LocalTtsRequestPolicy.failureCategoryForAndroidError(errorCode),
                        androidErrorCode = errorCode,
                    )) engineListener?.onError(errorCode)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                currentRequest(utteranceId)?.let {
                    if (it.error()) engineListener?.onError(TextToSpeech.ERROR_SYNTHESIS)
                }
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                currentRequest(utteranceId)?.markStarted()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                currentRequest(utteranceId)?.let {
                    if (it.stop()) engineListener?.onStop()
                }
            }
        })
    }

    private fun setEnginePlayParams(
        engine: TextToSpeech,
        rate: Int,
        pitch: Int,
        requestParams: Bundle? = null,
    ): Bundle {
        val requestedLocale = locale.trim()
        if (requestedLocale.isNotEmpty()) {
            val parsed = when (requestedLocale.lowercase(Locale.US)) {
                "zho", "zh", "zh-cn" -> Locale.SIMPLIFIED_CHINESE
                "eng", "en", "en-us" -> Locale.US
                else -> Locale.forLanguageTag(requestedLocale)
            }
            val languageResult = engine.setLanguage(parsed)
            if (languageResult < 0) {
                throw IllegalStateException("locale_not_supported")
            }
        }

        voiceName?.takeIf { it.isNotBlank() }?.let { selectedVoice ->
            val selected = engine.voices?.firstOrNull { it.name == selectedVoice }
                ?: throw IllegalStateException("voice_not_found")
            engine.voice = selected
        }

        val rateMultiplier = if (isRateFollowSystem()) {
            LocalTtsParameterPolicy.rateMultiplierFromSystem(rate)
        } else {
            LocalTtsParameterPolicy.rateMultiplier(this.rate)
        }
        val pitchMultiplier = if (isPitchFollowSystem()) {
            LocalTtsParameterPolicy.pitchMultiplierFromSystem(pitch)
        } else {
            LocalTtsParameterPolicy.pitchMultiplier(this.pitch)
        }
        if (engine.setSpeechRate(rateMultiplier) != TextToSpeech.SUCCESS) {
            throw IllegalStateException("set_rate_failed")
        }
        if (engine.setPitch(pitchMultiplier) != TextToSpeech.SUCCESS) {
            throw IllegalStateException("set_pitch_failed")
        }

        return Bundle().apply {
            extraParams?.forEach { it.putValueFromBundle(this) }
            requestParams?.let(::putAll)
        }
    }

    override suspend fun synthesizeWithOutcome(
        text: String,
        rate: Int,
        pitch: Int,
    ): EngineSynthesisOutcome = synthesizeWithOutcomeAndParams(text, rate, pitch, null)

    override suspend fun synthesizeWithOutcomeAndParams(
        text: String,
        rate: Int,
        pitch: Int,
        requestParams: Bundle?,
    ): EngineSynthesisOutcome {
        if (!ensureEngineReady()) {
            return EngineSynthesisOutcome.Failure(
                EngineSynthesisOutcome.FailureCategory.INITIALIZATION,
                cause = "engine_init_failed"
            )
        }

        val tts = mTtsEngine ?: return EngineSynthesisOutcome.Failure(
            EngineSynthesisOutcome.FailureCategory.INITIALIZATION
        )
        val utteranceId = UUID.randomUUID().toString()
        val generation = requestGeneration.incrementAndGet()
        val state = LocalTtsRequestState(utteranceId, generation)
        activeRequests[utteranceId] = state
        val file = if (isDirectPlayMode) null else {
            saveDir.mkdirs()
            File(saveDir, "$utteranceId.wav")
        }

        return try {
            val immediateResult = try {
                val params = setEnginePlayParams(tts, rate, pitch, requestParams)
                if (isDirectPlayMode) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                } else {
                    tts.synthesizeToFile(text, params, file!!, utteranceId)
                }
            } catch (e: Throwable) {
                val category = when (e.message) {
                    "locale_not_supported", "voice_not_found" ->
                        EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
                    else -> EngineSynthesisOutcome.FailureCategory.IMMEDIATE_API
                }
                state.failure(
                    category,
                    cause = e.message?.take(160)
                )
                invalidateEngine("immediate_exception:${e.message ?: e.javaClass.simpleName}")
                return state.outcome!!
            }

            if (immediateResult != TextToSpeech.SUCCESS) {
                state.failure(
                    EngineSynthesisOutcome.FailureCategory.IMMEDIATE_API,
                    androidErrorCode = immediateResult,
                )
                invalidateEngine("immediate_result:$immediateResult")
                return state.outcome!!
            }

            val completion = try {
                awaitDirectCompletionIfNeeded(tts, state)
            } catch (e: CancellationException) {
                state.stop()
                tts.stop()
                throw e
            }

            when (completion) {
                is EngineSynthesisOutcome.Failure -> {
                    invalidateEngine("listener_failure:${completion.cause ?: completion.category}")
                    completion
                }
                EngineSynthesisOutcome.Stopped -> {
                    invalidateEngine("listener_stop")
                    EngineSynthesisOutcome.Stopped
                }
                is EngineSynthesisOutcome.Success -> {
                    if (isDirectPlayMode) {
                        EngineSynthesisOutcome.Success(
                            descriptor = state.descriptor,
                            direct = true
                        )
                    } else {
                        val bytes = file?.takeIf { it.exists() }?.readBytes()
                        if (bytes == null || bytes.isEmpty() || !hasAudioPayload(bytes)) {
                            EngineSynthesisOutcome.Failure(
                                EngineSynthesisOutcome.FailureCategory.EMPTY_AUDIO,
                                cause = "empty_synthesis_file"
                            )
                        } else {
                            val descriptor = state.descriptor ?: parseWavDescriptor(bytes)
                            EngineSynthesisOutcome.Success(
                                audio = ITextToSpeechSynthesizer.AudioResult(
                                    inputStream = ByteArrayInputStream(bytes),
                                    bytes = bytes,
                                    sourceFormat = descriptor,
                                ),
                                descriptor = descriptor,
                                direct = false,
                            )
                        }
                    }
                }
            }
        } finally {
            activeRequests.remove(utteranceId, state)
            file?.let { kotlin.runCatching { it.delete() } }
        }
    }

    /**
     * Android does not expose a reliable engine-disconnected callback through
     * TextToSpeech. If the child engine dies before sending onDone/onError,
     * state.await() would otherwise wait forever and the reader would never
     * submit another utterance. Poll isSpeaking only for direct playback and
     * collapse a dead/stalled child into a normal synthesis failure.
     */
    private suspend fun awaitDirectCompletionIfNeeded(
        tts: TextToSpeech,
        state: LocalTtsRequestState,
    ): EngineSynthesisOutcome {
        if (!isDirectPlayMode) return state.await()

        return coroutineScope {
            val requestStartedAt = SystemClock.elapsedRealtime()
            val watchdog = launch(Dispatchers.Default) {
                var notSpeakingSince = 0L
                while (isActive && state.outcome == null) {
                    delay(DIRECT_ENGINE_CHECK_INTERVAL_MILLIS)
                    if (state.outcome != null) break

                    val now = SystemClock.elapsedRealtime()
                    val startTimedOut = !state.hasStarted &&
                        now - requestStartedAt >= DIRECT_ENGINE_START_TIMEOUT_MILLIS
                    if (!state.hasStarted && !startTimedOut) continue

                    val speaking = runCatching { tts.isSpeaking }.getOrDefault(false)
                    if (speaking) {
                        notSpeakingSince = 0L
                        continue
                    }

                    if (notSpeakingSince == 0L) notSpeakingSince = now
                    val silenceGrace = if (state.hasStarted) {
                        DIRECT_ENGINE_SILENCE_GRACE_MILLIS
                    } else {
                        0L
                    }
                    if (now - notSpeakingSince < silenceGrace) continue

                    if (state.failure(
                            category = EngineSynthesisOutcome.FailureCategory.LISTENER,
                            androidErrorCode = TextToSpeech.ERROR_SYNTHESIS,
                            cause = if (state.hasStarted) {
                                "engine_disconnected"
                            } else {
                                "engine_start_timeout"
                            },
                        )
                    ) {
                        invalidateEngine("watchdog:${state.outcome}")
                    }
                    break
                }
            }

            try {
                state.await()
            } finally {
                watchdog.cancelAndJoin()
            }
        }
    }

    override suspend fun startPlay(text: String, rate: Int, pitch: Int): Boolean {
        return synthesizeWithOutcome(text, rate, pitch) is EngineSynthesisOutcome.Success
    }

    fun getAudioFile(text: String, rate: Int, pitch: Int = 0): File {
        compatibilityFile?.let { kotlin.runCatching { it.delete() } }
        compatibilityFile = null
        val previousDirectMode = isDirectPlayMode
        isDirectPlayMode = false
        val result = try {
            runBlocking { synthesizeWithOutcome(text, rate, pitch) }
        } finally {
            isDirectPlayMode = previousDirectMode
        }
        val bytes = (result as? EngineSynthesisOutcome.Success)?.audio?.bytes
            ?: throw IllegalStateException("local_tts_audio_unavailable")
        saveDir.mkdirs()
        return File(saveDir, "compat-${UUID.randomUUID()}.wav").also {
            it.writeBytes(bytes)
            compatibilityFile = it
        }
    }


    override suspend fun getAudio(speakText: String, rate: Int, pitch: Int): InputStream? {
        val result = synthesizeWithOutcome(speakText, rate, pitch)
        return (result as? EngineSynthesisOutcome.Success)?.audio?.let {
            it.inputStream ?: it.bytes?.let(::ByteArrayInputStream)
        }
    }

    override fun isDirectPlay() = isDirectPlayMode

    private fun parseWavDescriptor(bytes: ByteArray): AudioDescriptor? {
        if (bytes.size < 44 || bytes.decodeToString(0, 4) != "RIFF" ||
            bytes.decodeToString(8, 12) != "WAVE"
        ) return null

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.decodeToString(offset, offset + 4)
            val length = littleEndianInt(bytes, offset + 4)
            val dataStart = offset + 8
            if (length < 0 || dataStart + length > bytes.size) return null
            if (chunkId == "fmt " && length >= 16) {
                val channels = littleEndianShort(bytes, dataStart + 2)
                val sampleRate = littleEndianInt(bytes, dataStart + 4)
                val bits = littleEndianShort(bytes, dataStart + 14)
                val encoding = when (bits) {
                    8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
                    else -> android.media.AudioFormat.ENCODING_PCM_16BIT
                }
                return AudioDescriptor(sampleRate, encoding, channels)
            }
            offset = dataStart + length + (length and 1)
        }
        return null
    }

    private fun hasAudioPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || bytes.decodeToString(0, 4) != "RIFF" ||
            bytes.decodeToString(8, 12) != "WAVE"
        ) return bytes.isNotEmpty()

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.decodeToString(offset, offset + 4)
            val length = littleEndianInt(bytes, offset + 4)
            val dataStart = offset + 8
            if (length < 0 || dataStart + length > bytes.size) return false
            if (chunkId == "data") return length > 0
            offset = dataStart + length + (length and 1)
        }
        return false
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
