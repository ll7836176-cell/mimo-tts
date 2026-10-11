package com.github.jing332.tts_server_android.service.systts

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.jing332.tts_server_android.*
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.constant.KeyConst
import com.github.jing332.tts_server_android.constant.LogLevel
import com.github.jing332.tts_server_android.constant.SystemNotificationConst
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.help.audio.AudioDecoderException
import com.github.jing332.tts_server_android.help.config.SysTtsConfig
import com.github.jing332.tts_server_android.model.speech.tts.AudioDescriptor
import com.github.jing332.tts_server_android.model.speech.tts.EngineSynthesisOutcome
import com.github.jing332.tts_server_android.model.speech.tts.ITextToSpeechEngine
import com.github.jing332.tts_server_android.service.systts.help.TextToSpeechManager
import com.github.jing332.tts_server_android.service.systts.help.exception.*
import com.github.jing332.tts_server_android.ui.AppLog
import com.github.jing332.tts_server_android.ui.MainActivity
import com.github.jing332.tts_server_android.ui.MainActivity.Companion.INDEX_SYS_TTS
import com.github.jing332.tts_server_android.ui.MainActivity.Companion.KEY_FRAGMENT_INDEX
import com.github.jing332.tts_server_android.utils.*
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore
import kotlin.system.exitProcess


@Suppress("DEPRECATION")
class SystemTtsService : TextToSpeechService(), TextToSpeechManager.Listener {
    companion object {
        const val TAG = "SysTtsService"
        const val ACTION_ON_LOG = "SYS_TTS_ON_LOG"
        const val ACTION_UPDATE_CONFIG = "on_config_changed"
        const val ACTION_UPDATE_REPLACER = "on_replacer_changed"

        const val ACTION_NOTIFY_CANCEL = "SYS_TTS_NOTIFY_CANCEL"
        const val ACTION_NOTIFY_KILL_PROCESS = "SYS_TTS_NOTIFY_EXIT_0"
        const val NOTIFICATION_CHAN_ID = "system_tts_service"

        const val DEFAULT_VOICE_NAME = "DEFAULT_默认"

        /**
         * 更新配置
         */
        fun notifyUpdateConfig(isOnlyReplacer: Boolean = false) {
            if (isOnlyReplacer)
                AppConst.localBroadcast.sendBroadcast(Intent(ACTION_UPDATE_REPLACER))
            else
                AppConst.localBroadcast.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
        }
    }

    private val mCurrentLanguage: MutableList<String> = mutableListOf("zho", "CHN", "")


    private val mTtsManager: TextToSpeechManager by lazy {
        TextToSpeechManager(this).also { it.listener = this }
    }

    private val mNotificationReceiver: NotificationReceiver by lazy { NotificationReceiver() }
    private val mLocalReceiver: LocalReceiver by lazy { LocalReceiver() }

    private val mScope = CoroutineScope(Job())

    // WIFI 锁
    private val mWifiLock by lazy {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tts-server:wifi_lock")
    }

    // 唤醒锁
    private var mWakeLock: PowerManager.WakeLock? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        IntentFilter(ACTION_NOTIFY_KILL_PROCESS).apply {
            addAction(ACTION_NOTIFY_CANCEL)
            registerReceiver(mNotificationReceiver, this)
        }

        AppConst.localBroadcast.registerReceiver(
            mLocalReceiver,
            IntentFilter(ACTION_UPDATE_CONFIG)
        )

        if (SysTtsConfig.isWakeLockEnabled)
            mWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "tts-server:wake_lock"
            )

        mWakeLock?.acquire(60 * 20 * 100)
        mWifiLock.acquire()

        mTtsManager.load()
    }

    override fun onDestroy() {
        super.onDestroy()

        synthesisGeneration.incrementAndGet()
        mTtsManager.destroy()
        unregisterReceiver(mNotificationReceiver)
        AppConst.localBroadcast.unregisterReceiver(mLocalReceiver)

        mWakeLock?.release()
        mWifiLock.release()

        stopForeground(/* removeNotification = */ true)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (Locale.SIMPLIFIED_CHINESE.isO3Language == lang || Locale.US.isO3Language == lang) {
            if (Locale.SIMPLIFIED_CHINESE.isO3Country == country || Locale.US.isO3Country == country) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return mCurrentLanguage.toTypedArray()
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        mCurrentLanguage.clear()
        mCurrentLanguage.addAll(
            mutableListOf(
                lang.toString(),
                country.toString(),
                variant.toString()
            )
        )

        return result
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String {
        return DEFAULT_VOICE_NAME
    }


    override fun onGetVoices(): MutableList<Voice> {
        val list =
            mutableListOf(Voice(DEFAULT_VOICE_NAME, Locale.getDefault(), 0, 0, true, emptySet()))

        appDb.systemTtsDao.getSysTtsWithGroups().forEach {
            it.list.forEach { tts ->
                list.add(
                    Voice(
                        /* name = */ "${tts.displayName}_${tts.id}",
                        /* locale = */ Locale.forLanguageTag(tts.tts.locale),
                        /* quality = */ 0,
                        /* latency = */ 0,
                        /* requiresNetworkConnection = */true,
                        /* features = */mutableSetOf<String>().apply {
                            add(tts.order.toString())
                            add(tts.id.toString())
                        }
                    )
                )

            }
        }

        return list
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        val isDefault = voiceName == DEFAULT_VOICE_NAME
        if (isDefault) return TextToSpeech.SUCCESS

        val index =
            appDb.systemTtsDao.allTts.indexOfFirst { "${it.displayName}_${it.id}" == voiceName }

        return if (index == -1) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        synthesisGeneration.incrementAndGet()
        mTtsManager.stop()
        synthesizerJob?.cancel()
        updateNotification(getString(R.string.systts_state_idle), "")
    }

    private lateinit var mCurrentTextLabel: String
    private var synthesizerJob: Job? = null
    private var mNotificationJob: Job? = null
    private val synthesisGeneration = AtomicLong(0L)
    /** Keep concurrent framework/probe requests from sharing TextToSpeechManager state. */
    private val synthesisPermit = Semaphore(1, true)

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        try {
            synthesisPermit.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            finishCallbackError(callback, TextToSpeech.ERROR_SERVICE)
            return
        }

        try {
            onSynthesizeTextExclusive(request, callback)
        } finally {
            synthesisPermit.release()
        }
    }

    private fun onSynthesizeTextExclusive(request: SynthesisRequest, callback: SynthesisCallback) {
        val requestGeneration = synthesisGeneration.incrementAndGet()
        mNotificationJob?.cancel()
        reNewWakeLock()
        startForegroundService()
        val text = request?.charSequenceText.toString().trim()
        mCurrentTextLabel = requestLabel(text)
        updateNotification(getString(R.string.systts_state_synthesizing), mCurrentTextLabel)

        if (StringUtils.isSilent(text)) {
            if (requestGeneration != synthesisGeneration.get()) return
            if (callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                callback.done()
            } else {
                finishCallbackError(callback, TextToSpeech.ERROR_OUTPUT)
            }
            return
        }

        // 调用者指定ID
        var ttsId = -1L
        if (request.voiceName.isNotEmpty() && request.voiceName.contains("_")) {
            val voiceSplitList = request.voiceName.split("_")
            voiceSplitList.getOrNull(voiceSplitList.size - 1)?.let { idStr ->
                ttsId = idStr.toLongOrNull() ?: -1L
            }
        }

        val outcome = AtomicReference<EngineSynthesisOutcome>(EngineSynthesisOutcome.Stopped)
        val callbackStarted = AtomicBoolean(false)
        val callbackTerminated = AtomicBoolean(false)
        val callbackAudioWritten = AtomicBoolean(false)
        val callbackFrameBytes = AtomicReference(2)

        runBlocking {
            synthesizerJob = launch {
                outcome.set(mTtsManager.textToAudio(
                    ttsId = ttsId,
                    text = text,
                    sysRate = (request!!.speechRate * 100) / 500, // < 100
                    sysPitch = request.pitch - 100, // 默认0,
                    requestParams = request.params,
                    onStart = { format ->
                        if (requestGeneration != synthesisGeneration.get()) {
                            false
                        } else if (!callbackStarted.compareAndSet(false, true)) {
                            true
                        } else {
                            callbackFrameBytes.set(frameBytes(format))
                            callback.start(
                                format.sampleRate,
                                format.encoding,
                                format.channelCount
                            ) == TextToSpeech.SUCCESS
                        }
                    },
                ) {
                    val accepted = writeToCallBack(
                        callback,
                        it,
                        callbackFrameBytes.get(),
                        requestGeneration,
                    )
                    if (accepted && it.isNotEmpty()) callbackAudioWritten.set(true)
                    accepted
                })
            }.job
            synthesizerJob!!.join()
        }

        if (requestGeneration != synthesisGeneration.get()) return

        val finalOutcome = outcome.get()
        when (finalOutcome) {
            is EngineSynthesisOutcome.Success -> {
                if ((finalOutcome.direct || callbackAudioWritten.get()) &&
                    callbackStarted.get() && callbackTerminated.compareAndSet(false, true)
                ) {
                    callback.done()
                } else if (callbackTerminated.compareAndSet(false, true)) {
                    finishCallbackError(callback, TextToSpeech.ERROR_OUTPUT)
                }
            }

            is EngineSynthesisOutcome.Failure -> if (callbackTerminated.compareAndSet(false, true)) {
                finishCallbackError(callback, mapError(finalOutcome))
            }

            // A child engine can stop because its Binder died while the
            // framework request is still current. Returning without a
            // callback leaves TextToSpeech clients waiting forever; report a
            // synthesis error so readers can advance or recover.
            EngineSynthesisOutcome.Stopped -> if (callbackTerminated.compareAndSet(false, true)) {
                finishCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
            }
        }
        Log.i(TAG, "synthesis finished outcome=${finalOutcome::class.simpleName}")

        mNotificationJob = mScope.launch {
            delay(5000)
            stopForeground(true)
            mNotificationDisplayed = false
        }
    }

    private fun writeToCallBack(
        callback: SynthesisCallback,
        pcmData: ByteArray,
        frameBytes: Int,
        requestGeneration: Long,
    ): Boolean {
        if (pcmData.isEmpty()) return true
        if (requestGeneration != synthesisGeneration.get()) return false
        if (!mTtsManager.isSynthesizing) return false
        val maxBufferSize = callback.maxBufferSize
        if (maxBufferSize <= 0 || frameBytes <= 0 || maxBufferSize < frameBytes ||
            pcmData.size % frameBytes != 0
        ) return false

        val alignedMax = maxBufferSize - (maxBufferSize % frameBytes)
        if (alignedMax < frameBytes) return false

        var offset = 0
        while (offset < pcmData.size) {
            if (!mTtsManager.isSynthesizing) return false
            val remaining = pcmData.size - offset
            val bytesToWrite = minOf(alignedMax, remaining - (remaining % frameBytes))
            if (bytesToWrite <= 0) return false
            if (callback.audioAvailable(pcmData, offset, bytesToWrite) != TextToSpeech.SUCCESS) {
                return false
            }
            offset += bytesToWrite
        }
        return true
    }

    private fun finishCallbackError(callback: SynthesisCallback, errorCode: Int) {
        runCatching { callback.error(errorCode) }
        runCatching { callback.done() }
    }

    private fun frameBytes(format: AudioDescriptor): Int {
        val sampleBytes = when (format.encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        return sampleBytes * format.channelCount.coerceAtLeast(1)
    }

    private fun mapError(failure: EngineSynthesisOutcome.Failure): Int {
        val androidCode = failure.androidErrorCode
        return if (androidCode != null && androidCode < 0) androidCode else when (failure.category) {
            EngineSynthesisOutcome.FailureCategory.CALLBACK -> TextToSpeech.ERROR_OUTPUT
            EngineSynthesisOutcome.FailureCategory.TIMEOUT -> TextToSpeech.ERROR_NETWORK_TIMEOUT
            EngineSynthesisOutcome.FailureCategory.INVALID_REQUEST -> TextToSpeech.ERROR_INVALID_REQUEST
            else -> TextToSpeech.ERROR_SYNTHESIS
        }
    }

    private fun reNewWakeLock() {
        if (mWakeLock != null && mWakeLock?.isHeld == false) {
            Log.i(TAG, "刷新WakeLock 20分钟")
            mWakeLock?.acquire(60 * 20 * 1000)
        }
        GcManager.doGC()
    }

    private var mNotificationBuilder: Notification.Builder? = null
    private lateinit var mNotificationManager: NotificationManager

    // 通知是否显示中
    private var mNotificationDisplayed = false

    /* 启动前台服务通知 */
    private fun startForegroundService() {
        if (SysTtsConfig.isForegroundServiceEnabled && !mNotificationDisplayed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    NOTIFICATION_CHAN_ID,
                    getString(R.string.systts_service),
                    NotificationManager.IMPORTANCE_NONE
                )
                chan.lightColor = Color.CYAN
                chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                mNotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mNotificationManager.createNotificationChannel(chan)
            }
            startForeground(SystemNotificationConst.ID_SYSTEM_TTS, getNotification())
            mNotificationDisplayed = true
        }
    }

    /* 更新通知 */
    private fun updateNotification(title: String, content: String? = null) {
        if (SysTtsConfig.isForegroundServiceEnabled)
            runOnUI {
                mNotificationBuilder?.let { builder ->
                    content?.let {
                        val bigTextStyle =
                            Notification.BigTextStyle().bigText(it).setSummaryText("TTS")
                        builder.style = bigTextStyle
                        builder.setContentText(it)
                    }

                    builder.setContentTitle(title)
                    startForeground(
                        SystemNotificationConst.ID_SYSTEM_TTS,
                        builder.build()
                    )
                }
            }
    }

    /* 获取通知 */
    @Suppress("DEPRECATION")
    private fun getNotification(): Notification {
        val notification: Notification
        /*Android 12(S)+ 必须指定PendingIntent.FLAG_*/
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        /*点击通知跳转*/
        val pendingIntent =
            PendingIntent.getActivity(
                this, 1, Intent(
                    this,
                    MainActivity::class.java
                ).apply { putExtra(KEY_FRAGMENT_INDEX, INDEX_SYS_TTS) }, pendingIntentFlags
            )

        val killProcessPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(
                ACTION_NOTIFY_KILL_PROCESS
            ), pendingIntentFlags
        )
        val cancelPendingIntent =
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_NOTIFY_CANCEL), pendingIntentFlags)

        mNotificationBuilder = Notification.Builder(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationBuilder?.setChannelId(NOTIFICATION_CHAN_ID)
        }
        notification = mNotificationBuilder!!
            .setSmallIcon(R.mipmap.ic_app_notification)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_light_primary))
            .addAction(0, getString(R.string.kill_process), killProcessPendingIntent)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent)
            .build()

        return notification
    }

    @Suppress("DEPRECATION")
    inner class NotificationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_NOTIFY_KILL_PROCESS -> { // 通知按钮{结束进程}
                    stopForeground(true)
                    exitProcess(0)
                }

                ACTION_NOTIFY_CANCEL -> { // 通知按钮{取消}
                    if (mTtsManager.isSynthesizing)
                        onStop() /* 取消当前播放 */
                    else /* 无播放，关闭通知 */ {
                        stopForeground(true)
                        mNotificationDisplayed = false
                    }
                }
            }
        }
    }

    inner class LocalReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_CONFIG -> mTtsManager.load()
                ACTION_UPDATE_REPLACER -> mTtsManager.loadReplacer()
            }
        }
    }

    override fun onRequestStarted(text: String, tts: ITextToSpeechEngine) {
        if (!AppConst.isSysTtsLogEnabled) return
        logD(
            "<br>" + getString(
                R.string.systts_log_request_audio,
                "${requestLabel(text).toHtmlBold()}<br> ${tts.javaClass.simpleName.toHtmlSmall().toHtmlItalic()}"
            )
        )
    }

    override fun onError(e: TtsManagerException) {
        if (!AppConst.isSysTtsLogEnabled) return
        when (e) {
            is RequestException -> {
                when (e.errorCode) {
                    RequestException.ERROR_CODE_AUDIO_NULL -> {
                        logE(getString(R.string.systts_log_audio_empty, requestLabel(e.text)))
                    }

                    RequestException.ERROR_CODE_TIMEOUT -> {
                        logE(getString(R.string.failed_timed_out, SysTtsConfig.requestTimeout))
                    }

                    else -> {
                        logE(
                            getString(
                                R.string.systts_log_failed,
                                "(${e.times}) ${e.javaClass.simpleName}, code=${e.errorCode}"
                            )
                        )
                    }
                }

                updateNotification(
                    getString(R.string.systts_log_failed, ""),
                    "${e.javaClass.simpleName}, code=${e.errorCode}"
                )
            }

            is TextReplacerException -> {
                logE(
                    getString(
                        R.string.systts_log_replace_failed,
                        "${e.replaceRule}, ${e.localizedMessage}"
                    )
                )
            }

            is SpeechRuleException -> {
                logE(getString(R.string.systts_log_text_handle_failed, e.localizedMessage))
            }

            is ConfigLoadException -> {
                logE("配置加载失败: ${e.localizedMessage}")
            }

            is PlayException -> {
                if (e.cause is AudioDecoderException) {
                    logE("解码失败: ${e.cause?.localizedMessage}")
                } else
                    logE("播放失败: ${e.localizedMessage}")
            }

            else -> {
                logE("错误: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onStartRetry(times: Int) {
        logW(getString(R.string.systts_log_start_retry, times))
    }

    override fun onRequestSuccess(
        text: String,
        tts: ITextToSpeechEngine,
        size: Int,
        costTime: Long,
        retryTimes: Int
    ) {
        if (!AppConst.isSysTtsLogEnabled) return

        val sizeStr = if (size == -1) getString(R.string.unknown) else "${(size / 1024)}kb"
        logI(
            getString(
                R.string.systts_log_success,
                sizeStr.toHtmlBold(),
                "${costTime}ms".toHtmlBold()
            )
        )
        // 重试成功
        if (retryTimes > 0) updateNotification(
            getString(R.string.systts_state_synthesizing),
            mCurrentTextLabel
        )
    }

    override fun onPlayFinished(text: String, tts: ITextToSpeechEngine) {
        if (!AppConst.isSysTtsLogEnabled) return
        logI(getString(R.string.systts_log_finished_playing, requestLabel(text).toHtmlBold()))
    }

    private fun requestLabel(text: String?): String {
        val value = text.orEmpty()
        return "len=${value.length},hash=${value.hashCode().toUInt().toString(16)}"
    }

    private fun logD(msg: String) = sendLog(LogLevel.DEBUG, msg)
    private fun logI(msg: String) = sendLog(LogLevel.INFO, msg)
    private fun logW(msg: String) = sendLog(LogLevel.WARN, msg)
    private fun logE(msg: String) = sendLog(LogLevel.ERROR, msg)

    private fun sendLog(@LogLevel level: Int, msg: String) {
        Log.d(TAG, "$level, $msg")
        val intent =
            Intent(ACTION_ON_LOG).putExtra(KeyConst.KEY_DATA, AppLog(level, msg))
        AppConst.localBroadcast.sendBroadcast(intent)
    }

}
