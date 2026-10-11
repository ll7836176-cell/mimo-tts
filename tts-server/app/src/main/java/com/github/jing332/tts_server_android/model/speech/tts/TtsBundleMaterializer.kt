package com.github.jing332.tts_server_android.model.speech.tts

import android.os.Bundle

class TtsBundleMaterializer {
    sealed interface SnapshotResult {
        data class Success(val entries: Map<String, Any>) : SnapshotResult
        data class Failure(val code: TtsServerSafeErrorCode) : SnapshotResult
    }

    sealed interface Result {
        data class Success(val bundle: Bundle) : Result
        data class Failure(val code: TtsServerSafeErrorCode) : Result
    }

    fun materializeSnapshot(
        generic: GenericTtsRequestParamsSnapshot,
        providerId: String,
        voice: String? = null,
        style: String? = null,
        baseInstructions: List<String> = emptyList(),
    ): SnapshotResult {
        generic.validationError()?.let { return SnapshotResult.Failure(it) }
        val isMiMo = providerId.equals("mimo", ignoreCase = true)
        val wireInstructions = if (isMiMo) baseInstructions else emptyList()
        if (isMiMo && generic.entries.size + wireInstructions.size > MAX_GENERIC_ENTRIES) {
            return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        if (isMiMo && voice != null && voice.isBlank()) {
            return SnapshotResult.Failure(TtsServerSafeErrorCode.VOICE_INVALID)
        }
        if (style != null && style.utf8ByteLength() > SpeechRuleMiMoProfileParser.MAX_STYLE_UTF8_BYTES) {
            return SnapshotResult.Failure(TtsServerSafeErrorCode.STYLE_TOO_LONG)
        }
        if (generic.entries.any { it.key.startsWith("mimo.") }) {
            return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_CONFLICT)
        }
        if (isMiMo && generic.entries.any { it.key == KEY_BASE_INSTRUCTION }) {
            return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_CONFLICT)
        }

        val genericEntries = generic.entries + wireInstructions.mapIndexed { index, instruction ->
            BundleEntrySnapshot(
                ordinal = generic.entries.size + index,
                key = KEY_BASE_INSTRUCTION,
                value = BundlePrimitiveSnapshot.StringValue(instruction),
            )
        }
        val entries = linkedMapOf<String, Any>()
        entries[KEY_GENERIC_COUNT] = genericEntries.size
        genericEntries.forEach { entry -> putIndexed(entries, entry) }
        if (isMiMo) {
            voice?.takeIf { it.isNotEmpty() }?.let { entries[KEY_MIMO_VOICE] = it }
            style?.takeIf { it.isNotEmpty() }?.let { entries[KEY_MIMO_STYLE] = it }
        }
        return SnapshotResult.Success(entries.toMap())
    }

    fun materialize(
        generic: GenericTtsRequestParamsSnapshot,
        providerId: String,
        voice: String? = null,
        style: String? = null,
        baseInstructions: List<String> = emptyList(),
    ): Result {
        val snapshot = materializeSnapshot(generic, providerId, voice, style, baseInstructions)
        if (snapshot is SnapshotResult.Failure) return Result.Failure(snapshot.code)
        val bundle = Bundle()
        (snapshot as SnapshotResult.Success).entries.forEach { (key, value) -> put(bundle, key, value) }
        return Result.Success(bundle)
    }

    fun fromParameters(
        parameters: List<LocalTtsParameter>,
        providerId: String,
        voice: String? = null,
        style: String? = null,
        baseInstructions: List<String> = emptyList(),
    ): Result {
        val entries = parameters.mapIndexed { ordinal, parameter ->
            parameter.toSnapshot(ordinal)
        }
        val failure = entries.firstOrNull { it is LocalTtsParameter.SnapshotResult.Failure }
        if (failure is LocalTtsParameter.SnapshotResult.Failure) return Result.Failure(failure.code)
        return materialize(
            generic = GenericTtsRequestParamsSnapshot(
                entries.map { (it as LocalTtsParameter.SnapshotResult.Success).entry },
            ),
            providerId = providerId,
            voice = voice,
            style = style,
            baseInstructions = baseInstructions,
        )
    }

    private fun putIndexed(entries: MutableMap<String, Any>, entry: BundleEntrySnapshot) {
        val prefix = "tts.params.v1.${entry.ordinal}"
        when (val value = entry.value) {
            is BundlePrimitiveSnapshot.BooleanValue -> entries["$prefix.bool"] = value.value
            is BundlePrimitiveSnapshot.IntValue -> entries["$prefix.int"] = value.value
            is BundlePrimitiveSnapshot.FloatValue -> entries["$prefix.float"] = value.value
            is BundlePrimitiveSnapshot.StringValue -> entries["$prefix.string"] = value.value
        }
        entries["$prefix.key"] = entry.key
        entries["$prefix.type"] = typeName(entry.value)
    }

    private fun put(bundle: Bundle, key: String, value: Any) {
        when (value) {
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Float -> bundle.putFloat(key, value)
            is String -> bundle.putString(key, value)
        }
    }

    private fun typeName(value: BundlePrimitiveSnapshot): String = when (value) {
        is BundlePrimitiveSnapshot.BooleanValue -> "boolean"
        is BundlePrimitiveSnapshot.IntValue -> "int"
        is BundlePrimitiveSnapshot.FloatValue -> "float"
        is BundlePrimitiveSnapshot.StringValue -> "string"
    }

    companion object {
        const val KEY_GENERIC_COUNT = "tts.params.v1.count"
        const val KEY_BASE_INSTRUCTION = "tts.rule.instruction.v1"
        const val KEY_MIMO_VOICE = "mimo.voice"
        const val KEY_MIMO_STYLE = "mimo.style"
        private const val MAX_GENERIC_ENTRIES = 64
    }
}
