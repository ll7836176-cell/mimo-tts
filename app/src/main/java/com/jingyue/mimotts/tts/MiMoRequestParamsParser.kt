package com.jingyue.mimotts.tts

import android.os.Bundle
import com.jingyue.mimotts.core.spi.BundleEntrySnapshot
import com.jingyue.mimotts.core.spi.BundlePrimitiveSnapshot
import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.GenericTtsRequestParamsSnapshot
import com.jingyue.mimotts.core.spi.MiMoOverridePresenceSnapshot
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import java.nio.charset.StandardCharsets

class MiMoRequestParamsParser(
    private val maxGenericEntries: Int = MAX_GENERIC_ENTRIES,
) {
    sealed interface Result {
        data class Success(
            val generic: GenericTtsRequestParamsSnapshot,
            val overrides: MiMoOverridePresenceSnapshot,
        ) : Result

        data class Failure(val code: MiMoSafeErrorCode) : Result
    }

    fun parse(bundle: Bundle?): Result {
        val safeBundle = bundle ?: Bundle()
        val reservedResult = parseReserved(safeBundle)
        if (reservedResult is ReservedResult.Failure) return Result.Failure(reservedResult.code)
        val generic = parseGeneric(safeBundle)
        if (generic is GenericResult.Failure) return Result.Failure(generic.code)
        return Result.Success(
            generic = (generic as GenericResult.Success).snapshot,
            overrides = (reservedResult as ReservedResult.Success).snapshot,
        )
    }

    private fun parseReserved(bundle: Bundle): ReservedResult {
        val unknown = bundle.keySet().firstOrNull { key ->
            key.startsWith(RESERVED_PREFIX) && key !in setOf(KEY_MIMO_VOICE, KEY_MIMO_STYLE)
        }
        if (unknown != null) return ReservedResult.Failure(MiMoSafeErrorCode.PARAM_UNKNOWN_KEY)

        val voice = if (bundle.containsKey(KEY_MIMO_VOICE)) {
            when (val raw = bundle.get(KEY_MIMO_VOICE)) {
                is String -> FieldState.Present(raw)
                else -> return ReservedResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            }
        } else {
            FieldState.Absent
        }
        val style = if (bundle.containsKey(KEY_MIMO_STYLE)) {
            when (val raw = bundle.get(KEY_MIMO_STYLE)) {
                is String -> if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_STYLE_UTF8_BYTES) {
                    return ReservedResult.Failure(MiMoSafeErrorCode.STYLE_TOO_LONG)
                } else {
                    FieldState.Present(raw)
                }
                else -> return ReservedResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            }
        } else {
            FieldState.Absent
        }
        return ReservedResult.Success(MiMoOverridePresenceSnapshot(voice = voice, style = style))
    }

    private fun parseGeneric(bundle: Bundle): GenericResult {
        val envelopeKeys = bundle.keySet().filter { it == KEY_COUNT || it.startsWith(ENTRY_PREFIX) }
        if (envelopeKeys.isEmpty()) return GenericResult.Success(GenericTtsRequestParamsSnapshot(emptyList()))
        val rawCount = bundle.get(KEY_COUNT)
            ?: return GenericResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        if (rawCount !is Int || rawCount < 0 || rawCount > maxGenericEntries) {
            return GenericResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }

        val entries = ArrayList<BundleEntrySnapshot>(rawCount)
        for (ordinal in 0 until rawCount) {
            val prefix = "$ENTRY_PREFIX$ordinal."
            val keyName = "$prefix$key"
            val typeName = "$prefix$type"
            val rawKey = bundle.get(keyName)
            val rawType = bundle.get(typeName)
            if (rawKey !is String || rawKey.isBlank() || rawType !is String) {
                return GenericResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            }
            if (rawKey == KEY_MIMO_VOICE || rawKey == KEY_MIMO_STYLE || rawKey.startsWith(RESERVED_PREFIX)) {
                return GenericResult.Failure(MiMoSafeErrorCode.PARAM_CONFLICT)
            }

            val valueFields = listOf("bool", "int", "float", "string")
                .map { suffix -> "$prefix$suffix" }
                .filter(bundle::containsKey)
            if (valueFields.size != 1) return GenericResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            val expectedField = "$prefix${rawType.lowercase()}"
            if (rawType.lowercase() !in SUPPORTED_TYPES || valueFields.single() != expectedField) {
                return GenericResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            }
            val rawValue = bundle.get(valueFields.single())
            val value = when (rawType.lowercase()) {
                "boolean" -> (rawValue as? Boolean)?.let(BundlePrimitiveSnapshot::BooleanValue)
                "int" -> (rawValue as? Int)?.let(BundlePrimitiveSnapshot::IntValue)
                "float" -> (rawValue as? Float)?.let(BundlePrimitiveSnapshot::FloatValue)
                "string" -> (rawValue as? String)?.let(BundlePrimitiveSnapshot::StringValue)
                else -> null
            } ?: return GenericResult.Failure(MiMoSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            entries += BundleEntrySnapshot(ordinal, rawKey, value)
        }

        val allowedKeys = buildSet {
            add(KEY_COUNT)
            for (ordinal in 0 until rawCount) {
                val prefix = "$ENTRY_PREFIX$ordinal."
                add("$prefix$key")
                add("$prefix$type")
                SUPPORTED_TYPES.forEach { add("$prefix$it") }
            }
        }
        if (envelopeKeys.any { it !in allowedKeys }) {
            return GenericResult.Failure(MiMoSafeErrorCode.PARAM_UNKNOWN_KEY)
        }
        return GenericResult.Success(GenericTtsRequestParamsSnapshot(entries))
    }

    private sealed interface ReservedResult {
        data class Success(val snapshot: MiMoOverridePresenceSnapshot) : ReservedResult
        data class Failure(val code: MiMoSafeErrorCode) : ReservedResult
    }

    private sealed interface GenericResult {
        data class Success(val snapshot: GenericTtsRequestParamsSnapshot) : GenericResult
        data class Failure(val code: MiMoSafeErrorCode) : GenericResult
    }

    companion object {
        const val KEY_MIMO_VOICE = "mimo.voice"
        const val KEY_MIMO_STYLE = "mimo.style"
        const val KEY_BASE_INSTRUCTION = "tts.rule.instruction.v1"
        const val KEY_COUNT = "tts.params.v1.count"
        const val ENTRY_PREFIX = "tts.params.v1."
        const val MAX_GENERIC_ENTRIES = 64
        const val MAX_STYLE_UTF8_BYTES = 1024
        private val SUPPORTED_TYPES = setOf("boolean", "int", "float", "string")
        private const val RESERVED_PREFIX = "mimo."
        private const val key = "key"
        private const val type = "type"
    }
}
