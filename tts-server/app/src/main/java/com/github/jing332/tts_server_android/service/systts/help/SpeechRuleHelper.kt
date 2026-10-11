package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import com.github.jing332.tts_server_android.data.entities.SpeechRule
import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleEngine
import com.github.jing332.tts_server_android.model.speech.CaptureState
import com.github.jing332.tts_server_android.model.speech.TtsTextPair
import com.github.jing332.tts_server_android.model.speech.RouteResolution
import com.github.jing332.tts_server_android.model.speech.RuleSegmentIdState
import com.github.jing332.tts_server_android.model.speech.TtsServerSafeErrorCode
import com.github.jing332.tts_server_android.model.speech.tts.ITextToSpeechEngine
import com.github.jing332.tts_server_android.service.systts.config.RouteDeclarationResolver
import com.github.jing332.tts_server_android.service.systts.help.exception.TtsRequestValidationException

class SpeechRuleHelper {
    private val routeResolver = RouteDeclarationResolver()
    lateinit var engine: SpeechRuleEngine

    fun init(context: Context, rule: SpeechRule) {
        engine = SpeechRuleEngine(context, rule)
        engine.eval()
    }

    fun splitText(text: String): List<String> {
        return engine.splitText(text).map { it.toString() }
    }

    fun handleText(
        text: String,
        config: Map<String, List<ITextToSpeechEngine>>,
        defaultConfig: ITextToSpeechEngine,
        generation: Long = 0L,
    ): List<TtsTextPair> {
        if (!this::engine.isInitialized) return listOf(TtsTextPair(defaultConfig, text))
        val resultList = mutableListOf<TtsTextPair>()

        val candidates = config.values.flatten()
        val routeSnapshot = routeResolver.captureSpeechRuleInfos(
            generation = generation,
            infos = candidates.map { it.speechRule },
        )
        if (routeSnapshot.captureState !is CaptureState.Ready) {
            throw TtsRequestValidationException(
                TtsServerSafeErrorCode.ROUTE_DECLARATION_INVALID,
            )
        }

        val list = candidates.map { it.speechRule }
        engine.handleText(text, list).forEach { txtWithTag ->
            if (txtWithTag.text.isNotBlank()) {
                val profileError = txtWithTag.profileError ?: txtWithTag.instructionsError
                if (profileError != null) throw TtsRequestValidationException(profileError)
                val resolution = routeResolver.resolve(
                    snapshot = routeSnapshot,
                    tag = txtWithTag.tag,
                    sourceId = txtWithTag.idState,
                )
                val target = (resolution as? RouteResolution.Success)?.target
                    ?: throw TtsRequestValidationException(
                        (resolution as? RouteResolution.Failure)?.code
                            ?: TtsServerSafeErrorCode.TARGET_NOT_FOUND,
                    )
                val matching = candidates.filter { candidate ->
                    candidate.speechRule.configId == target.key.configId &&
                        candidate.speechRule.tag.trim() == target.key.tag
                }
                val tts = matching.singleOrNull()
                    ?: throw TtsRequestValidationException(
                        if (matching.isEmpty()) {
                            TtsServerSafeErrorCode.TARGET_NOT_FOUND
                        } else {
                            TtsServerSafeErrorCode.TARGET_AMBIGUOUS
                        },
                    )
                resultList.add(
                    TtsTextPair(
                        text = txtWithTag.text,
                        tts = tts,
                        target = target,
                        mimoProfile = txtWithTag.mimoProfile,
                        baseInstructions = txtWithTag.baseInstructions,
                    ),
                )
            }
        }

        return resultList
    }

}
