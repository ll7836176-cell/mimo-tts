package com.github.jing332.tts_server_android.model.speech.tts

import java.nio.charset.StandardCharsets

sealed interface RuleSegmentIdState {
    object Absent : RuleSegmentIdState
    data class Present(val value: Long) : RuleSegmentIdState
    data class Invalid(val code: TtsServerSafeErrorCode = TtsServerSafeErrorCode.TARGET_ID_INVALID) : RuleSegmentIdState
}

data class TargetKey(
    val tag: String,
    val configId: Long,
)

enum class TargetRole {
    DEFAULT,
    NARRATION,
    TAG,
}

enum class RouteDeclarationSource {
    SPEECH_RULE_INFO_LEGACY,
    SPEECH_TARGET_LEGACY,
    EXPLICIT_ROLE_CONFIG,
}

data class RouteDeclaration(
    val role: TargetRole,
    val targetKey: TargetKey,
    val legacyTarget: Int? = null,
    val isStandby: Boolean = false,
    val source: RouteDeclarationSource = RouteDeclarationSource.EXPLICIT_ROLE_CONFIG,
)

sealed interface RouteDeclarationState {
    object Absent : RouteDeclarationState
    data class Present(val target: TargetKey) : RouteDeclarationState
    data class Invalid(val code: TtsServerSafeErrorCode) : RouteDeclarationState
}

sealed interface CaptureState {
    object Ready : CaptureState
    data class Invalid(val code: TtsServerSafeErrorCode) : CaptureState
}

class RouteDeclarationSnapshot(
    val generation: Long,
    declarations: List<RouteDeclaration>,
    val defaultTarget: RouteDeclarationState,
    val narrationTarget: RouteDeclarationState,
    enabledTargetsByTag: Map<String, List<TargetKey>>,
    val captureState: CaptureState,
) {
    val declarations: List<RouteDeclaration> = declarations.map { it.copy() }.toList()
    val enabledTargetsByTag: Map<String, List<TargetKey>> = enabledTargetsByTag
        .mapValues { (_, targets) -> targets.map { it.copy() }.toList() }
        .toMap()

    init {
        require(generation >= 0) { "generation must not be negative" }
        // Lists and maps are copied at construction so callers cannot mutate a live route.
    }

    override fun equals(other: Any?): Boolean = other is RouteDeclarationSnapshot &&
        generation == other.generation &&
        declarations == other.declarations &&
        defaultTarget == other.defaultTarget &&
        narrationTarget == other.narrationTarget &&
        enabledTargetsByTag == other.enabledTargetsByTag &&
        captureState == other.captureState

    override fun hashCode(): Int = listOf(
        generation,
        declarations,
        defaultTarget,
        narrationTarget,
        enabledTargetsByTag,
        captureState,
    ).hashCode()

    override fun toString(): String = "RouteDeclarationSnapshot(generation=$generation, declarations=$declarations, " +
        "defaultTarget=$defaultTarget, narrationTarget=$narrationTarget, " +
        "enabledTargetsByTag=$enabledTargetsByTag, captureState=$captureState)"
}

enum class RouteKind {
    EXPLICIT_TAG,
    IDLESS_UNIQUE_TAG,
    DEFAULT,
    NARRATION_ALIAS,
}

data class ResolvedTarget(
    val key: TargetKey,
    val sourceIdState: RuleSegmentIdState,
    val routeKind: RouteKind,
)

sealed interface RouteResolution {
    data class Success(val target: ResolvedTarget) : RouteResolution
    data class Failure(val code: TtsServerSafeErrorCode) : RouteResolution
}

sealed interface BundlePrimitiveSnapshot {
    data class BooleanValue(val value: Boolean) : BundlePrimitiveSnapshot
    data class IntValue(val value: Int) : BundlePrimitiveSnapshot
    data class FloatValue(val value: Float) : BundlePrimitiveSnapshot
    data class StringValue(val value: String) : BundlePrimitiveSnapshot
}

data class BundleEntrySnapshot(
    val ordinal: Int,
    val key: String,
    val value: BundlePrimitiveSnapshot,
)

class GenericTtsRequestParamsSnapshot(entries: List<BundleEntrySnapshot>) {
    val entries: List<BundleEntrySnapshot> = entries.map { entry ->
        entry.copy(
            value = when (val value = entry.value) {
                is BundlePrimitiveSnapshot.BooleanValue -> value.copy()
                is BundlePrimitiveSnapshot.IntValue -> value.copy()
                is BundlePrimitiveSnapshot.FloatValue -> value.copy()
                is BundlePrimitiveSnapshot.StringValue -> value.copy()
            },
        )
    }.toList()

    fun validationError(): TtsServerSafeErrorCode? {
        if (entries.withIndex().any { (index, entry) -> entry.ordinal != index }) {
            return TtsServerSafeErrorCode.PARAM_CONFLICT
        }
        if (entries.any { it.ordinal < 0 || it.key.isBlank() }) {
            return TtsServerSafeErrorCode.PARAM_CONFLICT
        }
        return null
    }
}

data class TtsSegmentSnapshot(
    val text: String,
    val tag: String,
    val sourceId: RuleSegmentIdState = RuleSegmentIdState.Absent,
    val target: ResolvedTarget? = null,
)

data class TtsSegmentPlanSnapshot(
    val generation: Long,
    val segments: List<TtsSegmentSnapshot>,
    val fallback: ResolvedTarget? = null,
) {
    init {
        require(generation >= 0) { "generation must not be negative" }
        require(segments.all { it.text.isNotEmpty() }) { "segments must not contain empty text" }
    }
}

data class TtsRequestSnapshot(
    val generation: Long,
    val segments: List<TtsSegmentSnapshot>,
    val genericParams: GenericTtsRequestParamsSnapshot,
) {
    init {
        require(generation >= 0) { "generation must not be negative" }
        require(segments.all { it.text.isNotEmpty() }) { "segments must not contain empty text" }
    }
}

fun String.utf8ByteLength(): Int = toByteArray(StandardCharsets.UTF_8).size
