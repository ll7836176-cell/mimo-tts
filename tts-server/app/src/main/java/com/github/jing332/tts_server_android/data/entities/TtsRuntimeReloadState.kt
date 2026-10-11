package com.github.jing332.tts_server_android.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable recovery marker; it intentionally contains no request/config payload. */
@Entity(tableName = "tts_runtime_reload_state")
data class TtsRuntimeReloadState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val generation: Long,
    val mutation: Long,
    val status: String,
    val safeCode: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val STATUS_APPLIED = "APPLIED"
    }
}
