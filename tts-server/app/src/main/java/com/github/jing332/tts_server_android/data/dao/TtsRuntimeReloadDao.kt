package com.github.jing332.tts_server_android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.jing332.tts_server_android.data.entities.TtsRuntimeReloadJournal
import com.github.jing332.tts_server_android.data.entities.TtsRuntimeReloadState

@Dao
interface TtsRuntimeReloadDao {
    @Query("SELECT * FROM tts_runtime_reload_state WHERE id = 1 LIMIT 1")
    fun getState(): TtsRuntimeReloadState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(state: TtsRuntimeReloadState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putJournal(entry: TtsRuntimeReloadJournal)

    @Query("SELECT * FROM tts_runtime_reload_journal WHERE mutation = :mutation LIMIT 1")
    fun getJournal(mutation: Long): TtsRuntimeReloadJournal?
}
