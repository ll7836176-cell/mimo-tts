package com.github.jing332.tts_server_android.help

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.*

class LocalTtsEngineHelper(val context: Context) {
    private var tts: TextToSpeech? = null

    private var engineName: String = ""

    /**
     * return 是否 初始化成功
     */
    suspend fun setEngine(name: String): Boolean {
        if (name.isBlank()) return false
        if (engineName != name || tts == null) {
            engineName = name
            shutdown()

            val initResult = CompletableDeferred<Int>()
            try {
                tts = TextToSpeech(context, { initResult.complete(it) }, name)
            } catch (_: Throwable) {
                shutdown()
                return false
            }
            val status = try {
                withTimeout(8_000L) { initResult.await() }
            } catch (_: CancellationException) {
                shutdown()
                return false
            }
            if (status != TextToSpeech.SUCCESS) {
                shutdown()
                return false
            }
        }
        return true
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }


    val voices: List<Voice>
        get() = try {
            tts!!.voices?.toList()!!
        } catch (_: Throwable) {
            emptyList()
        }

    val locales: List<Locale>
        get() = try {
            tts!!.availableLanguages.toList().sortedBy { it.toString() }
        } catch (_: Throwable) {
            emptyList()
        }

}
