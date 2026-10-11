package com.github.jing332.tts_server_android.model.speech

import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleMiMoProfileParser
import com.github.jing332.tts_server_android.model.speech.tts.ITextToSpeechEngine

data class TtsTextPair(
    val tts: ITextToSpeechEngine,
    val text: String,
    val target: ResolvedTarget? = null,
    val mimoProfile: SpeechRuleMiMoProfileParser.Profile? = null,
    val baseInstructions: List<String> = emptyList(),
)
