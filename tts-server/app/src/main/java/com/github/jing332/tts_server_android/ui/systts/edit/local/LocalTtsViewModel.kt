package com.github.jing332.tts_server_android.ui.systts.edit.local

import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drake.net.utils.withIO
import com.drake.net.utils.withMain
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.BR
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.data.entities.systts.SystemTts
import com.github.jing332.tts_server_android.help.audio.AudioDecoder
import com.github.jing332.tts_server_android.model.speech.tts.LocalTTS
import com.github.jing332.tts_server_android.ui.systts.edit.SpinnerData
import com.github.jing332.tts_server_android.ui.view.widget.spinner.SpinnerItem
import com.github.jing332.tts_server_android.utils.runOnUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*

class LocalTtsViewModel : ViewModel() {
    companion object {
        // 默认的SpinnerItem 用于风格和角色
        private val DEFAULT_SPINNER_ITEM: SpinnerItem by lazy {
            SpinnerItem(App.context.getString(R.string.default_str), null)
        }

        private val STR_DEFAULT by lazy { App.context.getString(R.string.default_str) }

        private val ISO3_LANGUAGES: Map<String, String> by lazy {
            buildMap {
                Locale.getAvailableLocales().forEach { locale ->
                    if (locale.language.isNotBlank()) {
                        runCatching { put(locale.isO3Language.lowercase(Locale.US), locale.language.lowercase(Locale.US)) }
                    }
                }
                put("chi", "zh")
                put("zho", "zh")
                put("eng", "en")
            }
        }

        private val ISO3_COUNTRIES: Map<String, String> by lazy {
            buildMap {
                Locale.getAvailableLocales().forEach { locale ->
                    if (locale.country.isNotBlank()) {
                        runCatching { put(locale.isO3Country.uppercase(Locale.US), locale.country.uppercase(Locale.US)) }
                    }
                }
            }
        }
    }

    val voiceEnabledLiveData: MutableLiveData<Boolean> by lazy { MutableLiveData() }
    val engineAvailableLiveData: MutableLiveData<Boolean> by lazy { MutableLiveData() }
    val ui: UiData = UiData()

    private val engineHelper by lazy { TtsEngineHelper(App.context, viewModelScope) }

    private lateinit var mData: SystemTts
    private lateinit var mTts: LocalTTS

    private lateinit var mAllVoices: List<Voice>

    /** Only the latest user-selected engine may publish a catalog to the UI. */
    @Volatile
    private var engineLoadGeneration: Long = 0L
    private var engineLoadJob: Job? = null
    private val engineLoadMutex = Mutex()
    /** Engine that produced the currently published locale/voice catalog. */
    private var catalogEngine: String? = null
    private var hasPublishedCatalog = false


    fun checkAndSetDisplayName(displayName: String) {
        val isAuto = Regex(".*(.*\\..*\\..*)").replace(displayName, "").isBlank()
        mData.displayName = if (isAuto) {
            ui.engines.selectedItem?.displayText ?: ""
        } else {
            displayName
        }
    }

    fun init(sysTts: SystemTts, onStart: () -> Unit, onDone: (ok: Boolean) -> Unit) {
        mData = sysTts
        mTts = sysTts.tts as LocalTTS

        ui.engines.addOnPropertyChangedCallback { _, propertyId ->
            if (propertyId == BR.position) {
                val selectedEngine = (ui.engines.selectedItem?.value as? String)
                    ?.takeIf { it.isNotBlank() }
                val engineChanged = hasPublishedCatalog && catalogEngine != selectedEngine
                if (selectedEngine != null) {
                    mTts.engine = selectedEngine
                    mTts.onLoad()
                }

                // The spinner callback runs on the UI thread. Publish the
                // loading state synchronously so Save/Test cannot observe the
                // previous engine's ready flag during a switch.
                engineAvailableLiveData.value = false
                engineLoadJob?.cancel()
                val generation = ++engineLoadGeneration
                engineLoadJob = viewModelScope.launch(Dispatchers.IO) {
                    withMain {
                        if (generation == engineLoadGeneration) onStart.invoke()
                    }

                    val ok = engineLoadMutex.withLock {
                        if (generation != engineLoadGeneration) {
                            false
                        } else {
                            withTimeoutOrNull(8000) {
                                engineHelper.setEngine(selectedEngine.orEmpty())
                            } ?: false
                        }
                    }
                    if (generation != engineLoadGeneration) return@launch

                    if (!ok) { // 超时或引擎初始化失败
                        withMain {
                            if (generation == engineLoadGeneration) {
                                engineAvailableLiveData.value = false
                                onDone(false)
                            }
                        }
                        return@launch
                    }

                    // Read the catalog off the main thread, but publish all
                    // selections together on main so spinner callbacks cannot
                    // observe a half-rebased locale/voice pair.
                    val voices = engineHelper.voices
                    // Some Android engines report an empty availableLanguages
                    // set even though their Voice catalog is populated. Use
                    // both sources so an engine switch can always rebase to
                    // a real locale/voice pair.
                    val locales = (engineHelper.locales + voices.map { it.locale })
                        .distinctBy { it.toLanguageTag() }
                    withMain {
                        if (generation != engineLoadGeneration) return@withMain
                        mAllVoices = voices
                        if (engineChanged) {
                            val rebased = rebaseLocalTtsSelection(
                                previousLocaleTag = mTts.locale,
                                previousVoiceName = mTts.voiceName,
                                availableLocales = locales,
                                availableVoices = voices.map { voice ->
                                    LocalTtsVoiceOption(voice.name, voice.locale)
                                },
                            )
                            mTts.locale = rebased.localeTag
                            mTts.voiceName = rebased.voiceName
                        }
                        updateLocale(locales)
                        catalogEngine = selectedEngine
                        hasPublishedCatalog = true
                        engineAvailableLiveData.value = true
                        onDone.invoke(true)
                    }
                }
            }
        }

        ui.locales.addOnPropertyChangedCallback { _, propertyId ->
            if (propertyId == BR.position) {
                ui.voices.apply {
                    ui.locales.selectedItem?.let {
                        when (val value = it.value) {
                            is Locale -> mTts.locale = value.toLanguageTag()
                            is MissingLocale -> mTts.locale = value.raw
                            null -> {
                                mTts.voiceName = null
                                mTts.locale = ""
                            }
                        }
                    }
                    updateVoice(mAllVoices)

                    voiceEnabledLiveData.postValue(mTts.locale != "")
                }
            }
        }

        ui.voices.addOnPropertyChangedCallback { _, propertyId ->
            if (propertyId == BR.position) {
                ui.voices.selectedItem?.let {
                    mTts.voiceName = when (val value = it.value) {
                        is Voice -> value.name
                        is MissingVoice -> value.raw
                        else -> null
                    }
                }
            }
        }

        val engines = getEngines()
        val engineItems = engines.map { SpinnerItem("${it.label} (${it.name})", it.name) }.toMutableList()
        val pos = engineItems.indexOfFirst { it.value == mTts.engine }
        if (pos < 0 && !mTts.engine.isNullOrBlank()) {
            engineItems.add(0, SpinnerItem("${App.context.getString(R.string.systts_engine_missing)} (${mTts.engine})", mTts.engine))
            engineAvailableLiveData.value = false
        } else {
            engineAvailableLiveData.value = pos >= 0
        }
        ui.engines.items = engineItems
        ui.engines.position = if (pos >= 0) pos else 0
    }

    // 更新地区
    private fun updateLocale(locales: List<Locale>) {
        ui.locales.apply {
            reset()
            val localeItems = locales.map { SpinnerItem(it.displayName, it) }
                .toMutableList()
                .apply { add(0, DEFAULT_SPINNER_ITEM) }
            items = localeItems

            val savedPos = localeItems.indexOfFirst {
                it.value is Locale && localeMatches(mTts.locale, it.value as Locale)
            }
            if (mTts.locale.isNotBlank() && savedPos < 0) {
                localeItems.add(1, SpinnerItem("${App.context.getString(R.string.systts_locale_missing)} (${mTts.locale})", MissingLocale(mTts.locale)))
                position = 1
            } else if (savedPos >= 0) {
                position = savedPos
            } else {
                val default = Locale.getDefault()
                position = items.indexOfFirst {
                    it.value is Locale && localeMatches(default.toLanguageTag(), it.value as Locale)
                }.takeIf { it >= 0 } ?: 0
            }
        }
    }

    // 更新语音
    private fun updateVoice(voices: List<Voice>) {
        ui.voices.apply {
            reset()
            val matchingVoices = voices.filter { localeMatches(mTts.locale, it.locale) }
                .sortedBy { it.name }.map {
                    val featureStr = readableFeatures(it.features)
                    SpinnerItem("${it.name} $featureStr", it)
                }.toMutableList()

            if (!mTts.voiceName.isNullOrBlank() && matchingVoices.none {
                    (it.value as? Voice)?.name == mTts.voiceName
                }) {
                matchingVoices.add(0, SpinnerItem(
                    "${App.context.getString(R.string.systts_voice_missing)} (${mTts.voiceName})",
                    MissingVoice(mTts.voiceName!!)
                ))
            }
            items = matchingVoices

            position = items.indexOfFirst { (it.value as? Voice)?.name == mTts.voiceName }
                .takeIf { it >= 0 } ?: 0
        }
    }

    private fun localeMatches(saved: String, candidate: Locale): Boolean {
        if (saved.isBlank()) return false
        val parts = saved.trim().replace('_', '-').split('-')
        val language = ISO3_LANGUAGES[parts.first().lowercase(Locale.US)]
            ?: parts.first().lowercase(Locale.US)
        val country = parts.drop(1).firstOrNull { part ->
            val value = part.uppercase(Locale.US)
            (value.length == 2 && Locale.getISOCountries().contains(value)) ||
                    (value.length == 3 && ISO3_COUNTRIES.containsKey(value))
        }?.let { part ->
            val value = part.uppercase(Locale.US)
            ISO3_COUNTRIES[value] ?: value
        }
        val savedLocale = Locale.forLanguageTag(
            buildString {
                append(language)
                if (!country.isNullOrBlank()) append('-').append(country)
            }
        )
        if (savedLocale.language.isBlank()) return false
        if (savedLocale.language != candidate.language) return false
        return savedLocale.country.isBlank() || candidate.country.isBlank() ||
                savedLocale.country.equals(candidate.country, ignoreCase = true)
    }

    private fun readableFeatures(features: Set<String>?): String {
        return features.orEmpty()
            .filter { !it.startsWith("jingyue.talkify.") && it.length <= 40 }
            .filter { it.all { ch -> ch.isLetterOrDigit() || ch.isWhitespace() || ch in "._-" } }
            .take(3)
            .joinToString(", ")
            .let { if (it.isBlank()) "" else " [$it]" }
    }

    @Suppress("DEPRECATION")
    private fun getEngines(): List<TextToSpeech.EngineInfo> {
        val tts = TextToSpeech(App.context, null)
        val engines = tts.engines
        tts.shutdown()
        return engines.sortedBy { it.name }
    }

    @Synchronized
    fun doTest(
        text: String,
        onEngineInitSuccess: () -> Unit,

        onGetAudioSuccess: suspend (audio: ByteArray, sampleRate: Int) -> Unit
    ) {
        viewModelScope.launch {
            mTts.onLoad()

            mTts.engineListener = null
            if (mTts.isDirectPlayMode) {
                mTts.engineListener = object : LocalTTS.EngineProgressListener {
                    override fun onStart() {
                        runOnUI { onEngineInitSuccess.invoke() }
                    }

                    override fun onDone() {
                    }
                }
                withIO { mTts.startPlayWithSystemParams(text, sysRate = 20, sysPitch = 0) }
            } else {
                withIO {
                    mTts.getAudioWithSystemParams(text, sysRate = 20, sysPitch = 0)?.let {
                        val bytes = it.readBytes()
                        val sampleRate = AudioDecoder.getSampleRateAndMime(bytes).first
                        withMain { onGetAudioSuccess(bytes, sampleRate) }
                        return@withIO
                    }
                }
            }
        }
    }

    fun stopTestPlay() {
        mTts.onStop()
    }

    fun canSave(): Boolean {
        if (engineAvailableLiveData.value != true) return false
        if (ui.locales.selectedItem?.value is MissingLocale) return false
        if (ui.voices.selectedItem?.value is MissingVoice) return false
        return true
    }

    override fun onCleared() {
        engineLoadGeneration += 1
        engineLoadJob?.cancel()
        super.onCleared()
        mTts.onDestroy()
        engineHelper.shutdown()
    }


    data class UiData(
        val engines: SpinnerData = SpinnerData(),
        val locales: SpinnerData = SpinnerData(),
        val voices: SpinnerData = SpinnerData()
    )

    private data class MissingLocale(val raw: String)
    private data class MissingVoice(val raw: String)

}
