package com.github.jing332.tts_server_android.service.systts.config

import com.github.jing332.tts_server_android.constant.SpeechTarget
import com.github.jing332.tts_server_android.data.entities.systts.SpeechRuleInfo
import com.github.jing332.tts_server_android.model.speech.CaptureState
import com.github.jing332.tts_server_android.model.speech.RouteDeclaration
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationSnapshot
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationSource
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationState
import com.github.jing332.tts_server_android.model.speech.ResolvedTarget
import com.github.jing332.tts_server_android.model.speech.RouteKind
import com.github.jing332.tts_server_android.model.speech.RouteResolution
import com.github.jing332.tts_server_android.model.speech.RuleSegmentIdState
import com.github.jing332.tts_server_android.model.speech.TargetKey
import com.github.jing332.tts_server_android.model.speech.TargetRole

typealias TtsServerSafeErrorCode = com.github.jing332.tts_server_android.model.speech.tts.TtsServerSafeErrorCode

class RouteDeclarationResolver {
    fun captureSpeechRuleInfos(generation: Long, infos: List<SpeechRuleInfo>): RouteDeclarationSnapshot {
        val declarations = mutableListOf<RouteDeclaration>()
        for (info in infos) {
            if (info.isStandby || info.target == SpeechTarget.BGM) continue
            val tag = info.tag.trim()
            val role = when {
                info.target == SpeechTarget.ALL && tag.isEmpty() -> TargetRole.DEFAULT
                info.target == SpeechTarget.ASIDE && tag.isEmpty() -> TargetRole.NARRATION
                (info.target == SpeechTarget.CUSTOM_TAG || info.target == SpeechTarget.DIALOGUE) && tag.isNotEmpty() -> TargetRole.TAG
                else -> return invalidSnapshot(generation.coerceAtLeast(0))
            }
            declarations += RouteDeclaration(
                role = role,
                targetKey = TargetKey(tag, info.configId),
                legacyTarget = info.target,
                source = RouteDeclarationSource.SPEECH_RULE_INFO_LEGACY,
            )
        }
        return capture(generation, declarations)
    }

    fun capture(generation: Long, declarations: List<RouteDeclaration>): RouteDeclarationSnapshot {
        if (generation < 0) return invalidSnapshot(0)
        val primary = declarations.filterNot { it.isStandby }.map { it.copy() }
        if (primary.any {
                it.targetKey.configId <= 0 ||
                    (it.role != TargetRole.TAG && it.targetKey.tag.isNotEmpty()) ||
                    (it.role == TargetRole.TAG && it.targetKey.tag.isBlank())
            }) return invalidSnapshot(generation)
        val defaults = primary.filter { it.role == TargetRole.DEFAULT }
            .distinctBy { it.targetKey }
        val narrations = primary.filter { it.role == TargetRole.NARRATION }
            .distinctBy { it.targetKey }
        if (defaults.size > 1 || narrations.size > 1) return invalidSnapshot(generation)
        return RouteDeclarationSnapshot(
            generation = generation,
            declarations = primary,
            defaultTarget = defaults.singleOrNull()?.let { RouteDeclarationState.Present(it.targetKey) }
                ?: RouteDeclarationState.Absent,
            narrationTarget = narrations.singleOrNull()?.let { RouteDeclarationState.Present(it.targetKey) }
                ?: RouteDeclarationState.Absent,
            enabledTargetsByTag = primary.filter { it.role == TargetRole.TAG }
                .groupBy { it.targetKey.tag }
                .mapValues { (_, values) -> values.map { it.targetKey }.distinct() },
            captureState = CaptureState.Ready,
        )
    }

    fun resolve(snapshot: RouteDeclarationSnapshot, tag: String, sourceId: RuleSegmentIdState): RouteResolution {
        val capture = snapshot.captureState
        if (capture is CaptureState.Invalid) return RouteResolution.Failure(capture.code)
        if (sourceId is RuleSegmentIdState.Invalid) return RouteResolution.Failure(sourceId.code)
        val normalizedTag = tag.trim()
        val candidates = snapshot.enabledTargetsByTag[normalizedTag].orEmpty()
        if (normalizedTag.isEmpty() && sourceId is RuleSegmentIdState.Present) {
            return when (val default = snapshot.defaultTarget) {
                is RouteDeclarationState.Present -> if (default.target.configId == sourceId.value) {
                    RouteResolution.Success(ResolvedTarget(default.target, sourceId, RouteKind.DEFAULT))
                } else RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_NOT_FOUND)
                is RouteDeclarationState.Invalid -> RouteResolution.Failure(default.code)
                RouteDeclarationState.Absent -> RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_NOT_FOUND)
            }
        }
        if (sourceId is RuleSegmentIdState.Present) {
            val exact = candidates.filter { it.configId == sourceId.value }
            if (exact.size > 1) return RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_AMBIGUOUS)
            if (exact.size == 1) return RouteResolution.Success(
                ResolvedTarget(exact.single(), sourceId, RouteKind.EXPLICIT_TAG),
            )
            return RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_NOT_FOUND)
        }
        if (normalizedTag.isEmpty()) return defaultResolution(snapshot)
        if (candidates.size > 1) return RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_AMBIGUOUS)
        if (candidates.size == 1) return RouteResolution.Success(
            ResolvedTarget(candidates.single(), RuleSegmentIdState.Absent, RouteKind.IDLESS_UNIQUE_TAG),
        )
        if (normalizedTag == "narration") {
            return when (val narration = snapshot.narrationTarget) {
                is RouteDeclarationState.Present -> RouteResolution.Success(
                    ResolvedTarget(narration.target, RuleSegmentIdState.Absent, RouteKind.NARRATION_ALIAS),
                )
                is RouteDeclarationState.Invalid -> RouteResolution.Failure(narration.code)
                RouteDeclarationState.Absent -> RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_NOT_FOUND)
            }
        }
        return defaultResolution(snapshot)
    }

    private fun defaultResolution(snapshot: RouteDeclarationSnapshot): RouteResolution =
        when (val default = snapshot.defaultTarget) {
            is RouteDeclarationState.Present -> RouteResolution.Success(
                ResolvedTarget(default.target, RuleSegmentIdState.Absent, RouteKind.DEFAULT),
            )
            is RouteDeclarationState.Invalid -> RouteResolution.Failure(default.code)
            RouteDeclarationState.Absent -> RouteResolution.Failure(TtsServerSafeErrorCode.TARGET_NOT_FOUND)
        }

    private fun invalidSnapshot(generation: Long) = RouteDeclarationSnapshot(
        generation = generation,
        declarations = emptyList(),
        defaultTarget = RouteDeclarationState.Invalid(TtsServerSafeErrorCode.ROUTE_DECLARATION_INVALID),
        narrationTarget = RouteDeclarationState.Invalid(TtsServerSafeErrorCode.ROUTE_DECLARATION_INVALID),
        enabledTargetsByTag = emptyMap(),
        captureState = CaptureState.Invalid(TtsServerSafeErrorCode.ROUTE_DECLARATION_INVALID),
    )
}
