package com.github.jing332.tts_server_android.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Append-only status evidence for runtime publishes, containing safe metadata only. */
@Entity(tableName = "tts_runtime_reload_journal")
data class TtsRuntimeReloadJournal(
    @PrimaryKey val mutation: Long,
    val generation: Long,
    val status: String,
    val safeCode: String? = null,
)
