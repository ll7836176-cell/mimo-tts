package com.github.jing332.tts_server_android.ui.systts.edit.local

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.*

class TtsEngineHelper(val context: Context, val scope: CoroutineScope) {
    private var tts: TextToSpeech? = null


    /**
     * @return 是否成功
     */
    suspend fun setEngine(name: String): Boolean = coroutineScope {
        shutdown()
        if (name.isBlank()) return@coroutineScope false

        val initResult = CompletableDeferred<Int>()
        try {
            tts = TextToSpeech(context, { initResult.complete(it) }, name)
        } catch (_: Throwable) {
            shutdown()
            return@coroutineScope false
        }

        val status = try {
            withTimeout(8_000L) { initResult.await() }
        } catch (_: CancellationException) {
            shutdown()
            return@coroutineScope false
        }

        if (status != TextToSpeech.SUCCESS) {
            shutdown()
            return@coroutineScope false
        }

        return@coroutineScope true
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    val voices: List<Voice>
        get() = try {
            tts?.voices?.toList()?.sortedBy { it.name } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

    val locales: List<Locale>
        get() = try {
            tts?.availableLanguages?.toList()?.sortedBy { it.toLanguageTag() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

}
