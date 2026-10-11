package com.github.jing332.tts_server_android.model.speech.tts

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class LocalTtsParameter(var type: String, var key: String, var value: String) : Parcelable {
    companion object {
        val typeList = listOf(
            "Boolean",
            "Int",
            "Float",
            "String"
        )
    }

    fun putValueFromBundle(b: Bundle) {
        when (type) {
            "Boolean" -> b.putBoolean(key, value.toBoolean())
            "Int" -> b.putInt(key, value.toInt())
            "Float" -> b.putFloat(key, value.toFloat())
            "String" -> b.putString(key, value)
            else -> throw IllegalArgumentException(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED.wireValue)
        }
    }

    sealed interface SnapshotResult {
        data class Success(val entry: BundleEntrySnapshot) : SnapshotResult
        data class Failure(val code: TtsServerSafeErrorCode) : SnapshotResult
    }

    fun toSnapshot(ordinal: Int): SnapshotResult {
        if (ordinal < 0 || key.isBlank()) return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_CONFLICT)
        val primitive = when (type) {
            "Boolean" -> when (value.lowercase()) {
                "true" -> BundlePrimitiveSnapshot.BooleanValue(true)
                "false" -> BundlePrimitiveSnapshot.BooleanValue(false)
                else -> return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            }
            "Int" -> value.toIntOrNull()?.let(BundlePrimitiveSnapshot::IntValue)
                ?: return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            "Float" -> value.toFloatOrNull()?.takeIf { it.isFinite() }?.let(BundlePrimitiveSnapshot::FloatValue)
                ?: return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
            "String" -> BundlePrimitiveSnapshot.StringValue(value)
            else -> return SnapshotResult.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        return SnapshotResult.Success(BundleEntrySnapshot(ordinal, key, primitive))
    }
}
