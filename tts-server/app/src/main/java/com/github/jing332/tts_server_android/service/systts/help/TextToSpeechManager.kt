package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.constant.AppPattern
import com.github.jing332.tts_server_android.constant.SpeechTarget
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.help.audio.AudioDecoder
import com.github.jing332.tts_server_android.help.audio.AudioPlayer
import com.github.jing332.tts_server_android.help.audio.Sonic
import com.github.jing332.tts_server_android.help.audio.exo.ExoAudioDecoder
import com.github.jing332.tts_server_android.help.config.SysTtsConfig
import com.github.jing332.tts_server_android.model.SysTtsLib
import com.github.jing332.tts_server_android.model.speech.ITextToSpeechSynthesizer
import com.github.jing332.tts_server_android.model.speech.TtsTextPair
import com.github.jing332.tts_server_android.model.speech.tts.*
import com.github.jing332.tts_server_android.service.systts.help.exception.*
import com.github.jing332.tts_server_android.service.systts.config.RuntimeConfigWriter
import com.github.jing332.tts_server_android.utils.StringUtils
import com.github.jing332.tts_server_android.utils.longToast
import com.github.jing332.tts_server_android.utils.toast
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class TextToSpeechManager(val context: Context) : ITextToSpeechSynthesizer<ITextToSpeechEngine>() {
    companion object {
        const val TAG = "TextToSpeechManager"
        private const val MIMO_ENGINE_PACKAGE = "com.jingyue.mimotts"

        private val defaultTtsConfig by lazy { MsTTS() }
    }

    var listener: Listener? = null

    @Volatile
    var isSynthesizing: Boolean = false
        private set

    private var audioFormat: BaseAudioFormat = BaseAudioFormat()

    init {
        SysTtsLib.setTimeout(SysTtsConfig.requestTimeout)
    }

    private data class RuntimeConfigSnapshot(
        val generation: Long,
        val configs: Map<Int, List<ITextToSpeechEngine>>,
        val speechRuleHelper: SpeechRuleHelper?,
    )

    private val mConfigMap: MutableMap<Int, List<ITextToSpeechEngine>> = mutableMapOf()
    private val mRuntimeGeneration = AtomicLong(0L)
    private val mConfigSnapshot = AtomicReference(
        RuntimeConfigSnapshot(
            generation = 0L,
            configs = emptyMap(),
            speechRuleHelper = null,
        ),
    )
    private val mLoadedTtsMap = mutableSetOf<ITextToSpeechEngine>()
    private val mRandom = Random(System.currentTimeMillis())

    override fun requestParamsFor(pair: TtsTextPair, baseParams: Bundle?): Bundle? {
        val profile = pair.mimoProfile
        val localTts = pair.tts as? LocalTTS
        if (profile == null && localTts?.engine?.equals(MIMO_ENGINE_PACKAGE, ignoreCase = true) != true) {
            return baseParams?.let(TtsBundleMaterializer::sanitizeForNonMiMo)
        }
        if (localTts == null) {
            throw TtsRequestValidationException(TtsServerSafeErrorCode.REQUEST_INVALID)
        }
        val configuredVoice = localTts.voiceName?.takeIf { it.isNotBlank() }
        val materialized = TtsBundleMaterializer().fromParameters(
            parameters = localTts.extraParams.orEmpty(),
            providerId = "mimo",
            voice = profile?.voice ?: configuredVoice,
            style = profile?.style,
            baseInstructions = pair.baseInstructions,
        )
        if (materialized is TtsBundleMaterializer.Result.Failure) {
            throw TtsRequestValidationException(materialized.code)
        }
        val own = (materialized as TtsBundleMaterializer.Result.Success).bundle
        val merged = Bundle().apply { baseParams?.let { params -> this.putAll(params) } }
        listOf(
            TtsBundleMaterializer.KEY_MIMO_VOICE,
            TtsBundleMaterializer.KEY_MIMO_STYLE,
        ).forEach { key ->
            if (own.containsKey(key) && merged.containsKey(key) && own.get(key) != merged.get(key)) {
                throw TtsRequestValidationException(TtsServerSafeErrorCode.PARAM_CONFLICT)
            }
        }
        merged.putAll(own)
        return merged
    }

    override suspend fun handleText(text: String): List<TtsTextPair> {
        kotlin.runCatching {
            val runtimeSnapshot = mConfigSnapshot.get()
            val configSnapshot = runtimeSnapshot.configs
            val speechRuleHelper = runtimeSnapshot.speechRuleHelper
            return if (SysTtsConfig.isMultiVoiceEnabled) {
                val tagTtsMap = mutableMapOf<String, MutableList<ITextToSpeechEngine>>()
                configSnapshot[SpeechTarget.CUSTOM_TAG]?.forEach { tts ->
                    if (tagTtsMap[tts.speechRule.tag] == null)
                        tagTtsMap[tts.speechRule.tag] = mutableListOf()

                    tagTtsMap[tts.speechRule.tag]?.add(tts)
                }

                speechRuleHelper?.handleText(
                    text = text,
                    config = tagTtsMap,
                    defaultConfig = defaultTtsConfig,
                    generation = runtimeSnapshot.generation,
                ) ?: listOf(TtsTextPair(defaultTtsConfig, text))
            } else {
                val list = configSnapshot[SpeechTarget.ALL] ?: listOf(defaultTtsConfig)
                listOf(TtsTextPair(list[mRandom.nextInt(list.size)], text))
            }.run {
                (if (SysTtsConfig.isSplitEnabled) {
                    val list = mutableListOf<TtsTextPair>()
                    forEach { ttsText ->
                        splitText(
                            list,
                            ttsText,
                            SysTtsConfig.isMultiVoiceEnabled,
                            speechRuleHelper,
                        )
                    }
                    list
                } else this).run {
                    if (SysTtsConfig.isSkipSilentText) filterNot {
                        AppPattern.notReadAloudRegex.matches(it.text)
                    } else this
                }

            }
        }.onFailure { error ->
            if (error is TtsRequestValidationException) throw error
            listener?.onError(
                SpeechRuleException(text = text, tts = null, message = error.message, cause = error)
            )
        }

        return emptyList()
    }

    private fun splitText(
        list: MutableList<TtsTextPair>,
        pair: TtsTextPair,
        isMultiVoice: Boolean,
        speechRuleHelper: SpeechRuleHelper?,
    ) {
        if (isMultiVoice) {
            val texts = try {
                speechRuleHelper?.splitText(pair.text) ?: return splitText(
                    list,
                    pair,
                    false,
                    speechRuleHelper,
                )
            } catch (e: NoSuchMethodException) {
                return splitText(list, pair, false, speechRuleHelper)
            }
            if (texts.isEmpty())
                listener?.onError(
                    SpeechRuleException(text = pair.text, tts = pair.tts, message = "splittedTexts is empty.")
                )
            else
                texts.forEach { list.add(pair.copy(text = it)) }
        } else {
            Log.d(TAG, "使用内置分割规则...")
            StringUtils.splitSentences(pair.text).forEach {
                list.add(pair.copy(text = it))
            }
        }
    }

    override suspend fun getAudio(
        tts: ITextToSpeechEngine,
        text: String,
        sysRate: Int,
        sysPitch: Int
    ): AudioResult? = getAudioWithParams(tts, text, sysRate, sysPitch, null)

    override suspend fun getAudioWithParams(
        tts: ITextToSpeechEngine,
        text: String,
        sysRate: Int,
        sysPitch: Int,
        requestParams: android.os.Bundle?,
    ): AudioResult? {
        Log.i(TAG, "request audio engine=${tts.javaClass.simpleName}, textLength=${text.length}")
        if (!coroutineContext.isActive) return null

        var audioResult: AudioResult? = null
        var retryTimes = 0
        var validationFailure: TtsRequestValidationException? = null
        var lastFailure: Throwable? = null
        retry(times = 50,
            onCatch = { times, e ->
                if (e is CancellationException) throw e
                retryTimes = times
                lastFailure = e
                if (e is TtsRequestValidationException) {
                    validationFailure = e
                    return@retry false
                }
                Log.w(
                    TAG,
                    "request failed: times=$times, engine=${tts.javaClass.simpleName}, " +
                            "type=${e.javaClass.simpleName}"
                )
                listener?.onError(
                    if (e is RequestException) e else RequestException(
                        text = text, tts = tts, cause = e, times = times
                    )
                )

                if (times > SysTtsConfig.maxRetryCount) {
                    return@retry false // 超过最大重试次数，跳过
                }

                if (e is RequestException && e.terminal) {
                    return@retry false
                }

                // 备用TTS
                if (SysTtsConfig.standbyTriggeredRetryIndex == times)
                    tts.speechRule.standbyTts?.let { sbyTts ->
                        Log.i(TAG, "使用备用TTS：$sbyTts")
                        audioResult = if (sbyTts.isDirectPlay()) {
                            AudioResult(
                                directAcknowledged = true,
                                standbyDirectAcknowledged = true,
                            )
                        } else
                            getAudioWithParams(sbyTts, text, sysRate, sysPitch, requestParams)
                        return@retry false // 取消重试
                    }

                // 第 maxEmptyAudioRetryCount 音频为空失败后退出 return false
                val canContinue = if (SysTtsConfig.maxEmptyAudioRetryCount == 0) false else
                    !(e is RequestException && e.errorCode == RequestException.ERROR_CODE_AUDIO_NULL
                            && times >= SysTtsConfig.maxEmptyAudioRetryCount)

                if (canContinue) listener?.onStartRetry(times)
                return@retry canContinue
            },
            block = {
                if (!coroutineContext.isActive) return@retry
                audioResult = null
                listener?.onRequestStarted(text, tts)

                val costTime = measureTimeMillis {
                    if (!mLoadedTtsMap.contains(tts)) {
                        Log.i(TAG, "load engine=${tts.javaClass.simpleName}")
                        tts.onLoad()
                        mLoadedTtsMap.add(tts)
                    }

                    // Legacy direct-play engines still acknowledge submission here.
                    // LocalTTS uses its request-scoped richer API and waits for onDone.
                    if (tts.isDirectPlay() && tts !is LocalTTS) {
                        audioResult = AudioResult()
                        return@retry
                    }

                    if (tts is LocalTTS) {
                        val outcome = try {
                            if (!LocalTtsRequestPolicy.shouldApplyRequestTimeout(tts.isDirectPlay())) {
                                // Direct LocalTTS completion is the lifetime of
                                // the child Android TTS utterance.  The
                                // request timeout is for obtaining network/file
                                // audio, not for waiting while that audio is
                                // spoken.  Applying the default 5-second value
                                // here truncates slow speech by calling
                                // onStop(), so the number of spoken characters
                                // changes with the playback rate.
                                if (requestParams == null) {
                                    tts.synthesizeWithSystemParams(text, sysRate, sysPitch)
                                } else {
                                    tts.synthesizeWithSystemParamsAndParams(
                                        text,
                                        sysRate,
                                        sysPitch,
                                        requestParams,
                                    )
                                }
                            } else {
                                withTimeout(SysTtsConfig.requestTimeout.toLong()) {
                                    if (requestParams == null) {
                                        tts.synthesizeWithSystemParams(text, sysRate, sysPitch)
                                    } else {
                                        tts.synthesizeWithSystemParamsAndParams(
                                            text,
                                            sysRate,
                                            sysPitch,
                                            requestParams,
                                        )
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            tts.onStop()
                            throw RequestException(
                                tts = tts,
                                text = text,
                                errorCode = RequestException.ERROR_CODE_TIMEOUT,
                                cause = e,
                            )
                        }

                        when (outcome) {
                            is EngineSynthesisOutcome.Success -> {
                                if (outcome.direct) {
                                    audioResult = AudioResult(
                                        data = outcome,
                                        sourceFormat = outcome.descriptor,
                                        directAcknowledged = true,
                                    )
                                } else {
                                    val result = outcome.audio
                                        ?: throw RequestException(
                                            errorCode = RequestException.ERROR_CODE_AUDIO_NULL,
                                            tts = tts, text = text
                                        )
                                    val bytes = result.bytes ?: result.inputStream?.readBytes()
                                    result.inputStream?.close()
                                    if (bytes == null || bytes.isEmpty()) {
                                        throw RequestException(
                                            errorCode = RequestException.ERROR_CODE_AUDIO_NULL,
                                            tts = tts, text = text
                                        )
                                    }
                                    result.bytes = bytes
                                    result.inputStream = ByteArrayInputStream(bytes)
                                    audioResult = result
                                }
                            }

                            is EngineSynthesisOutcome.Failure -> throw RequestException(
                                errorCode = if (outcome.category == EngineSynthesisOutcome.FailureCategory.TIMEOUT) {
                                    RequestException.ERROR_CODE_TIMEOUT
                                } else {
                                    RequestException.ERROR_CODE_REQUEST
                                },
                                message = outcome.cause ?: if (
                                    outcome.category == EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
                                ) {
                                    TtsServerSafeErrorCode.REQUEST_INVALID.wireValue
                                } else outcome.category.name,
                                terminal = outcome.category == EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST,
                                tts = if (
                                    outcome.category == EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
                                ) null else tts,
                                text = if (
                                    outcome.category == EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
                                ) null else text,
                                androidErrorCode = outcome.androidErrorCode
                                    ?: if (outcome.category == EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST) {
                                        TextToSpeech.ERROR_INVALID_REQUEST
                                    } else null,
                            )

                            EngineSynthesisOutcome.Stopped -> throw CancellationException("tts_stopped")
                        }
                    } else {
                        val inputStream = try {
                            withTimeout(SysTtsConfig.requestTimeout.toLong()) {
                                tts.getAudioWithSystemParams(text, sysRate, sysPitch)
                            }
                        } catch (e: TimeoutCancellationException) {
                            tts.onStop()
                            throw RequestException(
                                errorCode = RequestException.ERROR_CODE_TIMEOUT,
                                tts = tts,
                                text = text,
                                cause = e,
                            )
                        } ?: throw RequestException(
                            errorCode = RequestException.ERROR_CODE_AUDIO_NULL,
                            tts = tts, text = text
                        )
                        audioResult = AudioResult(inputStream = inputStream)
                        if (!SysTtsConfig.isStreamPlayModeEnabled) {
                            audioResult?.bytes = inputStream.readBytes()
                            inputStream.close()
                            if (audioResult?.bytes?.isEmpty() == true)
                                throw RequestException(
                                    errorCode = RequestException.ERROR_CODE_AUDIO_NULL,
                                    tts = tts, text = text
                                )
                        }
                    }
                }
                audioResult?.elapsedMillis = costTime
                audioResult?.retryCount = retryTimes
            })

        validationFailure?.let { throw it }
        if (audioResult == null) {
            val failure = lastFailure
            throw when (failure) {
                is RequestException -> failure
                null -> RequestException(
                    errorCode = RequestException.ERROR_CODE_AUDIO_NULL,
                    tts = tts,
                    text = text,
                )
                else -> RequestException(
                    tts = tts,
                    text = text,
                    cause = failure,
                    times = retryTimes,
                )
            }
        }
        return audioResult
    }

    private val mTextReplacer = TextReplacer()
    private val mAudioDecoder = AudioDecoder()
    private val mExoDecoder by lazy { ExoAudioDecoder(context) }
    private var mAudioPlayer: AudioPlayer? = null
    private var mBgmPlayer: BgmPlayer? = null

    /**
     * Direct engines play through their own Android TTS instance. Their
     * synthesis call waits for the inner engine to finish, so the outer
     * SystemTtsService must receive start() before that wait begins; otherwise
     * readers observe onStart at the end of a paragraph and may restart it.
     */
    private fun directEngineFormat(tts: ITextToSpeechEngine): AudioDescriptor =
        AudioDescriptor(
            sampleRate = tts.audioFormat.sampleRate,
            encoding = tts.audioFormat.bitRate,
            channelCount = 1,
        )

    private fun getEnabledList(target: Int, isStandby: Boolean): List<ITextToSpeechEngine> {
        return appDb.systemTtsDao.getEnabledListForSort(target, isStandby).map {
            appDb.systemTtsDao.getGroup(it.groupId)?.let { group ->
                val groupAudioParams = group.audioParams.copyIfFollow(
                    SysTtsConfig.audioParamsSpeed,
                    SysTtsConfig.audioParamsVolume, SysTtsConfig.audioParamsPitch
                )
                it.tts.audioParams = it.tts.audioParams.copyIfFollow(
                    followSpeed = groupAudioParams.speed,
                    followVolume = groupAudioParams.volume,
                    followPitch = groupAudioParams.pitch,
                )
            }
            it.tts.speechRule = it.speechRule
            it.tts.speechRule.configId = it.id
            return@map it.tts
        }
    }

    private fun initConfig(target: Int): Boolean {
        Log.i(TAG, "initConfig: $target")
        var isOk = true
        mConfigMap[target] = getEnabledList(target, isStandby = false)
        if (mConfigMap[target]?.isEmpty() == true) {
            isOk = false
            Log.w(TAG, "缺少朗读目标$target, 使用内置MsTTS！")
            mConfigMap[target] = listOf(MsTTS())
        }

        val sbyList = getEnabledList(target, isStandby = true)

        mConfigMap[target]?.forEach { tts ->
            sbyList.find { it.speechRule.isTagSame(tts.speechRule) }
                ?.let { sbyTts ->
                    Log.i(TAG, "找到备用TTS：$sbyTts")
                    tts.speechRule.standbyTts = sbyTts
                }
        }

        return isOk
    }

    private fun initBgm() {
        val list = mutableSetOf<Pair<Float, String>>()
        appDb.systemTtsDao.getEnabledListForSort(SpeechTarget.BGM).forEach {
            val tts = (it.tts as BgmTTS)
            val volume = if (tts.volume == 0) SysTtsConfig.bgmVolume else it.tts.volume / 1000f
            list.addAll(tts.musicList.map { path ->
                Pair(volume, path)
            })
        }
        if (list.isEmpty()) {
            mBgmPlayer?.release()
            mBgmPlayer = null
        } else {
            mBgmPlayer = mBgmPlayer ?: BgmPlayer(context)
            mBgmPlayer?.setPlayList(SysTtsConfig.isBgmShuffleEnabled, list)
        }
    }

    private fun initAudioFormat(@SpeechTarget target: Int) {
        mConfigMap[target]?.maxBy { it.audioFormat.sampleRate }?.audioFormat?.also {
            audioFormat = it
        }
    }

    fun loadReplacer() {
        if (SysTtsConfig.isReplaceEnabled)
            mTextReplacer.load()
    }

    override fun load() {
        try {
            mConfigMap.clear()
            loadReplacer()
            initBgm()
            var nextSpeechRuleHelper: SpeechRuleHelper? = null
            if (SysTtsConfig.isMultiVoiceEnabled) {
                val ok = initConfig(SpeechTarget.CUSTOM_TAG)
                initAudioFormat(SpeechTarget.CUSTOM_TAG)
                if (ok) {
                    mConfigMap[SpeechTarget.CUSTOM_TAG]?.getOrNull(0)?.also {
                        appDb.speechRule.getByRuleId(it.speechRule.tagRuleId)?.let { rule ->
                            nextSpeechRuleHelper = SpeechRuleHelper().also { helper ->
                                helper.init(context, rule)
                            }
                        }
                            ?: throw ConfigLoadException()
                    }
                } else
                    context.toast(R.string.systts_no_custom_tag_config_warn)

            } else {
                if (!initConfig(SpeechTarget.ALL))
                    context.toast(R.string.systts_no_speech_target_all)
                initAudioFormat(SpeechTarget.ALL)
            }
            val appliedGeneration = RuntimeConfigWriter.appliedGeneration()
            val nextGeneration = mRuntimeGeneration.updateAndGet { current ->
                maxOf(current + 1L, appliedGeneration)
            }
            mConfigSnapshot.set(
                RuntimeConfigSnapshot(
                    generation = nextGeneration,
                    configs = mConfigMap.mapValues { (_, values) -> values.toList() }.toMap(),
                    speechRuleHelper = nextSpeechRuleHelper,
                ),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            listener?.onError(ConfigLoadException(cause = e))
        }
    }

    override fun stop() {
        isSynthesizing = false
        for (engine in mLoadedTtsMap) engine.onStop()
        mBgmPlayer?.pause()
    }

    override fun destroy() {
        for (engine in mLoadedTtsMap) engine.onDestroy()
        mBgmPlayer?.release()
        mAudioPlayer?.release()
    }

    suspend fun textToAudio(
        ttsId: Long = -1L,
        text: String,
        sysRate: Int,
        sysPitch: Int,
        requestParams: android.os.Bundle? = null,
        onStart: (format: AudioDescriptor) -> Boolean,
        onPcmAudio: (pcmAudio: ByteArray) -> Boolean,
    ): EngineSynthesisOutcome {
        isSynthesizing = true
        val sessionFormat = AtomicReference<AudioDescriptor?>(null)
        val requiresExternalPcm = AtomicBoolean(false)
        val emittedExternalPcm = AtomicBoolean(false)

        fun ensureStarted(candidate: AudioDescriptor): Boolean {
            val normalized = candidate.copy(
                sampleRate = candidate.sampleRate.takeIf { it > 0 } ?: audioFormat.sampleRate,
                encoding = candidate.encoding.takeIf { it > 0 } ?: audioFormat.bitRate,
                channelCount = candidate.channelCount.coerceAtLeast(1),
            )
            if (!sessionFormat.compareAndSet(null, normalized)) return true
            val accepted = onStart(normalized)
            if (!accepted) sessionFormat.compareAndSet(normalized, null)
            return accepted
        }

        suspend fun receive(data: ITextToSpeechSynthesizer.AudioData<ITextToSpeechEngine>) {
            data.receiver(
                sysRate,
                sysPitch,
                ::ensureStarted,
                sessionFormat,
                markExternalAudio = { requiresExternalPcm.set(true) },
            ) { pcmAudio ->
                if (pcmAudio.isNotEmpty()) emittedExternalPcm.set(true)
                onPcmAudio(pcmAudio)
            }
        }

        return try {
            val replaced = if (SysTtsConfig.isReplaceEnabled)
                mTextReplacer.replace(text) { listener?.onError(it) } else text

            if (ttsId == -1L) {
                mBgmPlayer?.play()
                synthesizeTextWithParams(
                    text = replaced,
                    sysRate = sysRate,
                    sysPitch = sysPitch,
                    requestParams = requestParams,
                    onAudioAvailable = { data -> receive(data) },
                    beforeSynthesis = { pair ->
                        if (pair.tts.isDirectPlay() &&
                            !ensureStarted(directEngineFormat(pair.tts))
                        ) {
                            throw TtsManagerException("callback_start_failed")
                        }
                    },
                )
            } else {
                val specifiedTts = appDb.systemTtsDao.getTts(ttsId)
                    ?: throw TtsManagerException("specified_tts_missing")

                val group = appDb.systemTtsDao.getGroup(specifiedTts.groupId)
                if (group != null) {
                    val groupAudioParams = group.audioParams.copyIfFollow(
                        SysTtsConfig.audioParamsSpeed,
                        SysTtsConfig.audioParamsVolume,
                        SysTtsConfig.audioParamsPitch
                    )
                    specifiedTts.tts.audioParams = specifiedTts.tts.audioParams.copyIfFollow(
                        followSpeed = groupAudioParams.speed,
                        followVolume = groupAudioParams.volume,
                        followPitch = groupAudioParams.pitch,
                    )
                }
                synthesizeTextWithParams(
                    tts = specifiedTts.tts,
                    text = text,
                    sysRate = sysRate,
                    sysPitch = sysPitch,
                    requestParams = requestParams,
                    onAudioAvailable = { data -> receive(data) },
                    beforeSynthesis = { pair ->
                        if (pair.tts.isDirectPlay() &&
                            !ensureStarted(directEngineFormat(pair.tts))
                        ) {
                            throw TtsManagerException("callback_start_failed")
                        }
                    },
                )
            }

            EngineSynthesisOutcome.Success(
                descriptor = sessionFormat.get(),
                direct = !requiresExternalPcm.get() && !emittedExternalPcm.get(),
            )
        } catch (e: CancellationException) {
            EngineSynthesisOutcome.Stopped
        } catch (e: TtsRequestValidationException) {
            TtsRequestFailurePolicy.toTerminalOutcome(e)
        } catch (e: RequestException) {
                EngineSynthesisOutcome.Failure(
                    category = if (e.terminal) {
                        EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST
                    } else if (e.errorCode == RequestException.ERROR_CODE_TIMEOUT) {
                        EngineSynthesisOutcome.FailureCategory.TIMEOUT
                    } else EngineSynthesisOutcome.FailureCategory.LISTENER,
                    androidErrorCode = e.androidErrorCode,
                    cause = if (e.terminal) e.message else e.message?.take(160),
                )
        } catch (e: TtsManagerException) {
            EngineSynthesisOutcome.Failure(
                category = if (e.message?.contains("callback") == true) {
                    EngineSynthesisOutcome.FailureCategory.CALLBACK
                } else EngineSynthesisOutcome.FailureCategory.UNKNOWN,
                cause = e.message?.take(160),
            )
        } catch (e: Throwable) {
            EngineSynthesisOutcome.Failure(
                category = EngineSynthesisOutcome.FailureCategory.UNKNOWN,
                cause = e.message?.take(160),
            )
        } finally {
            isSynthesizing = false
        }
    }

    private suspend fun AudioData<ITextToSpeechEngine>.receiver(
        sysRate: Int,
        sysPitch: Int,
        ensureStarted: (AudioDescriptor) -> Boolean,
        sessionFormat: AtomicReference<AudioDescriptor?>,
        markExternalAudio: () -> Unit,
        onPcmAudio: (pcmAudio: ByteArray) -> Boolean,
    ) {
        val data = this
        try {
            val txtTts = data.txtTts
            val audioParams = txtTts.tts.audioParams

            val fallback = AudioDescriptor(
                txtTts.tts.audioFormat.sampleRate,
                txtTts.tts.audioFormat.bitRate,
                1,
            )
            val canUseStandbyDirect = data.audio == null &&
                    txtTts.tts.speechRule.standbyTts?.isDirectPlay() == true
            if (!txtTts.tts.isDirectPlay() && !canUseStandbyDirect &&
                !SysTtsConfig.isInAppPlayAudio
            ) {
                markExternalAudio()
            }
            if (data.audio == null && !txtTts.tts.isDirectPlay() && !canUseStandbyDirect) {
                throw TtsManagerException("audio_unavailable")
            }
            val source = data.audio?.sourceFormat ?: fallback
            if (!ensureStarted(source)) {
                throw TtsManagerException("callback_start_failed")
            }
            val target = sessionFormat.get() ?: source
            val targetSampleRate = target.sampleRate
            val srcSampleRate = source.sampleRate
            if (source.channelCount != target.channelCount) {
                throw TtsManagerException("audio_channel_mismatch")
            }

            val sonic =
                if (audioParams.isDefaultValue && srcSampleRate == targetSampleRate &&
                    source.channelCount == target.channelCount
                ) null
                else Sonic(srcSampleRate, source.channelCount.coerceAtLeast(1))
            txtTts.playAudio(
                sysRate, sysPitch, data.audio, srcSampleRate,
            )
            { pcmAudio ->
                val output: ByteArray = if (sonic == null) {
                    pcmAudio
                } else {
                    sonic.volume = audioParams.volume
                    sonic.speed = audioParams.speed
                    sonic.pitch = audioParams.pitch
                    sonic.rate = srcSampleRate.toFloat() / targetSampleRate.toFloat()

                    sonic.writeBytesToStream(pcmAudio, pcmAudio.size)
                    val sonicOutput: ByteArray = sonic.readBytesFromStream(sonic.samplesAvailable())
                    sonicOutput
                }
                if (output.isNotEmpty()) {
                    val accepted = onPcmAudio.invoke(output)
                    if (accepted != true) throw TtsManagerException("callback_audio_failed")
                }
                true
            }
            sonic?.flushStream()
            sonic?.let {
                val output: ByteArray = it.readBytesFromStream(it.samplesAvailable())
                if (output.isNotEmpty()) {
                    val accepted = onPcmAudio.invoke(output)
                    if (accepted != true) throw TtsManagerException("callback_audio_failed")
                }
            }
            data.done.invoke()
            listener?.onPlayFinished(txtTts.text, txtTts.tts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listener?.onError(TtsManagerException(cause = e, message = e.message))
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun TtsTextPair.playAudio(
        sysRate: Int,
        sysPitch: Int,
        audioResult: AudioResult?,
        sourceSampleRate: Int,
        onPcmAudio: (pcmAudio: ByteArray) -> Boolean,
    ) {
        var costTime = 0L
        var retryTimes = 0
        costTime = audioResult?.elapsedMillis ?: 0L
        retryTimes = audioResult?.retryCount ?: 0

        // audioResult == null 说明请求失败
        if (audioResult?.standbyDirectAcknowledged == true) { // 直接播放备用TTS
            if (tts.speechRule.standbyTts?.startPlayWithSystemParams(text, sysRate, sysPitch) != true) {
                throw TtsManagerException("standby_callback_failed")
            }
            return
        } else if (audioResult == null && tts.speechRule.standbyTts?.isDirectPlay() == true) { // 兼容旧的空结果路径
            if (tts.speechRule.standbyTts?.startPlayWithSystemParams(text, sysRate, sysPitch) != true) {
                throw TtsManagerException("standby_callback_failed")
            }
            return
        } else if (tts.isDirectPlay()) { // 直接播放
            if (audioResult == null) throw TtsManagerException("direct_play_failed")
            if (audioResult?.directAcknowledged != true &&
                !tts.startPlayWithSystemParams(text, sysRate, sysPitch)
            ) {
                throw TtsManagerException("direct_play_failed")
            }
            return
        } else if (audioResult?.inputStream == null) { // 无音频
            Log.w(TAG, "audio == null, $this")
            throw TtsManagerException("audio_unavailable")
        }

        if (SysTtsConfig.isStreamPlayModeEnabled) {
            listener?.onRequestSuccess(text, tts, -1, costTime, retryTimes)
            if (tts.audioFormat.isNeedDecode) {
                if (SysTtsConfig.isInAppPlayAudio) {
                    audioResult.builtinPlayAudio(tts.audioPlayer)
                    audioResult.inputStream?.close()
                } else {
                    try {
                        audioResult.decodeAudio(sourceSampleRate) {
                            if (!onPcmAudio(it)) throw TtsManagerException("callback_audio_failed")
                        }
                    } catch (e: Exception) {
                        throw PlayException(
                            tts = tts,
                            cause = e,
                            message = "流播放下的音频解码失败"
                        )
                    }
                }
            } else {
                throw TtsManagerException("暂不支持流播放下的raw音频")
            }
        } else { // 全部加载到内存
            val audio = audioResult.bytes
            if (audio == null || audio.isEmpty()) {
                throw TtsManagerException("audio_unavailable")
            }

            listener?.onRequestSuccess(text, tts, audio.size, costTime, retryTimes)

            if (tts.audioFormat.isNeedDecode) {
                if (SysTtsConfig.isInAppPlayAudio)
                    audioResult.builtinPlayAudio(tts.audioPlayer)
                else
                    try {
                        audioResult.decodeAudio(sourceSampleRate) {
                            if (!onPcmAudio(it)) throw TtsManagerException("callback_audio_failed")
                        }
                    } catch (e: Exception) {
                        throw PlayException(tts = tts, cause = e, message = "音频解码失败")
                    }
            } else
                if (!onPcmAudio(audio)) throw TtsManagerException("callback_audio_failed")
        }
    }

    // 内置播放器
    private suspend fun AudioResult.builtinPlayAudio(audioParams: PlayerParams) {
        val params = audioParams.copy().setParamsIfFollow(
            SysTtsConfig.inAppPlaySpeed,
            SysTtsConfig.inAppPlayVolume,
            SysTtsConfig.inAppPlayPitch
        )

        mAudioPlayer = mAudioPlayer ?: AudioPlayer(context)
        if (SysTtsConfig.isStreamPlayModeEnabled)
            mAudioPlayer?.play(inputStream!!, params.rate, params.volume, params.pitch)
        else
            mAudioPlayer?.play(bytes!!, params.rate, params.volume, params.pitch)
    }

    // 解码音频
    private suspend fun AudioResult.decodeAudio(
        sampleRate: Int,
        isStream: Boolean = SysTtsConfig.isStreamPlayModeEnabled,
        useExoDecoder: Boolean = SysTtsConfig.isExoDecoderEnabled,
        onPcmAudio: (pcmAudio: ByteArray) -> Unit,
    ) {
        if (useExoDecoder) {
            mExoDecoder.callback = ExoAudioDecoder.Callback { byteBuffer ->
                val buffer = ByteArray(byteBuffer.remaining())
                byteBuffer.get(buffer)
                onPcmAudio.invoke(buffer)
            }
            if (isStream) mExoDecoder.doDecode(inputStream!!)
            else mExoDecoder.doDecode((bytes!!))
        } else {
            if (isStream) mAudioDecoder.doDecode(
                inputStream!!,
                sampleRate
            ) { pcmData ->
                onPcmAudio.invoke(pcmData)
            }
            else
                mAudioDecoder.doDecode(bytes!!, sampleRate) { pcmData ->
                    onPcmAudio.invoke(pcmData)
                }
        }

    }

    interface Listener {
        fun onError(e: TtsManagerException)
        fun onStartRetry(times: Int)
        fun onRequestStarted(text: String, tts: ITextToSpeechEngine)
        fun onPlayFinished(text: String, tts: ITextToSpeechEngine)
        fun onRequestSuccess(
            text: String, tts: ITextToSpeechEngine, size: Int, costTime: Long, retryTimes: Int
        )
    }
}
