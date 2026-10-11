package com.github.jing332.tts_server_android.service.systts.help

import android.os.Bundle
import com.github.jing332.tts_server_android.model.speech.BundleEntrySnapshot
import com.github.jing332.tts_server_android.model.speech.BundlePrimitiveSnapshot
import com.github.jing332.tts_server_android.model.speech.tts.LocalTtsParameter
import com.github.jing332.tts_server_android.model.speech.utf8ByteLength

class TtsBundleMaterializer(
) {
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
        if (style != null && style.utf8ByteLength() > 1024) {
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
        val entries = linkedMapOf<String, Any>(KEY_GENERIC_COUNT to genericEntries.size)
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
            val value = when (parameter.type) {
                "Boolean" -> when (parameter.value.lowercase()) {
                    "true" -> BundlePrimitiveSnapshot.BooleanValue(true)
                    "false" -> BundlePrimitiveSnapshot.BooleanValue(false)
                    else -> return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
                }
                "Int" -> parameter.value.toIntOrNull()?.let(BundlePrimitiveSnapshot::IntValue)
                    ?: return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
                "Float" -> parameter.value.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.let(BundlePrimitiveSnapshot::FloatValue)
                    ?: return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
                "String" -> BundlePrimitiveSnapshot.StringValue(parameter.value)
                else -> return Result.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            }
            BundleEntrySnapshot(ordinal, parameter.key, value)
        }
        return materialize(
            GenericTtsRequestParamsSnapshot(entries),
            providerId,
            voice,
            style,
            baseInstructions,
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
        entries["$prefix.type"] = when (entry.value) {
            is BundlePrimitiveSnapshot.BooleanValue -> "boolean"
            is BundlePrimitiveSnapshot.IntValue -> "int"
            is BundlePrimitiveSnapshot.FloatValue -> "float"
            is BundlePrimitiveSnapshot.StringValue -> "string"
        }
    }

    private fun put(bundle: Bundle, key: String, value: Any) {
        when (value) {
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Float -> bundle.putFloat(key, value)
            is String -> bundle.putString(key, value)
        }
    }

    companion object {
        const val KEY_GENERIC_COUNT = "tts.params.v1.count"
        const val KEY_BASE_INSTRUCTION = "tts.rule.instruction.v1"
        const val KEY_MIMO_VOICE = "mimo.voice"
        const val KEY_MIMO_STYLE = "mimo.style"
        private const val MAX_GENERIC_ENTRIES = 64
        private const val MIMO_PREFIX = "mimo."

        /** Remove MiMo-owned public fields before a request enters another engine. */
        @JvmStatic
        fun sanitizeForNonMiMo(source: Bundle?): Bundle {
            val sanitized = Bundle().apply {
                source?.let {
                    putAll(it)
                    it.keySet().filterNot(::isSafeForNonMiMo).forEach(::remove)
                }
            }
            val count = source?.get(KEY_GENERIC_COUNT) as? Int
            if (count == null || count !in 0..MAX_GENERIC_ENTRIES) return sanitized

            val kept = mutableListOf<Int>()
            for (ordinal in 0 until count) {
                val key = source?.get("$GENERIC_ENTRY_PREFIX$ordinal.key") as? String
                    ?: return sanitized
                if (isSafeForNonMiMo(key)) kept += ordinal
            }
            if (kept.size == count) return sanitized

            sanitized.keySet()
                .filter { it == KEY_GENERIC_COUNT || it.startsWith(GENERIC_ENTRY_PREFIX) }
                .forEach(sanitized::remove)
            sanitized.putInt(KEY_GENERIC_COUNT, kept.size)
            kept.forEachIndexed { newOrdinal, oldOrdinal ->
                copyGenericEntry(source, sanitized, oldOrdinal, newOrdinal)
            }
            return sanitized
        }

        @JvmStatic
        fun isSafeForNonMiMo(key: String): Boolean =
            !key.startsWith(MIMO_PREFIX) && key != KEY_BASE_INSTRUCTION

        @JvmStatic
        fun sanitizeGenericEntriesForNonMiMo(
            source: GenericTtsRequestParamsSnapshot,
        ): GenericTtsRequestParamsSnapshot = GenericTtsRequestParamsSnapshot(
            source.entries
                .filter { isSafeForNonMiMo(it.key) }
                .mapIndexed { ordinal, entry -> entry.copy(ordinal = ordinal) },
        )

        private const val GENERIC_ENTRY_PREFIX = "tts.params.v1."

        private fun copyGenericEntry(
            source: Bundle?,
            target: Bundle,
            oldOrdinal: Int,
            newOrdinal: Int,
        ) {
            val oldPrefix = "$GENERIC_ENTRY_PREFIX$oldOrdinal"
            val newPrefix = "$GENERIC_ENTRY_PREFIX$newOrdinal"
            val key = source?.get("$oldPrefix.key") as? String ?: return
            val type = source?.get("$oldPrefix.type") as? String ?: return
            target.putString("$newPrefix.key", key)
            target.putString("$newPrefix.type", type)
            when (val value = source?.get("$oldPrefix.${type.lowercase()}")) {
                is Boolean -> target.putBoolean("$newPrefix.${type.lowercase()}", value)
                is Int -> target.putInt("$newPrefix.${type.lowercase()}", value)
                is Float -> target.putFloat("$newPrefix.${type.lowercase()}", value)
                is String -> target.putString("$newPrefix.${type.lowercase()}", value)
            }
        }
    }
}

typealias GenericTtsRequestParamsSnapshot = com.github.jing332.tts_server_android.model.speech.GenericTtsRequestParamsSnapshot
typealias TtsServerSafeErrorCode = com.github.jing332.tts_server_android.model.speech.tts.TtsServerSafeErrorCode
