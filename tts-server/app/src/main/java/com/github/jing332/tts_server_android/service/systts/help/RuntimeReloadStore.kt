package com.github.jing332.tts_server_android.service.systts.help

import androidx.room.withTransaction
import com.github.jing332.tts_server_android.data.AppDatabase
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.data.entities.TtsRuntimeReloadJournal
import com.github.jing332.tts_server_android.data.entities.TtsRuntimeReloadState

/**
 * The only Room-backed reload marker facade. It stores safe lifecycle metadata,
 * never request text, style, bundles, credentials, or provider payloads.
 */
class RuntimeReloadStore(
    private val database: AppDatabase = appDb,
) {
    data class AppliedMarker(
        val generation: Long,
        val mutation: Long,
        val safeCode: String?,
    )

    fun readApplied(): AppliedMarker? = database.ttsRuntimeReload.getState()
        ?.takeIf { it.status == TtsRuntimeReloadState.STATUS_APPLIED }
        ?.let { AppliedMarker(it.generation, it.mutation, it.safeCode) }

    suspend fun recordApplied(
        generation: Long,
        mutation: Long,
        safeCode: String? = null,
    ) {
        require(generation >= 0 && mutation >= 0)
        database.withTransaction {
            recordAppliedInTransaction(generation, mutation, safeCode)
        }
    }

    /** Must be called from the caller's already-open Room transaction. */
    fun recordAppliedInTransaction(
        generation: Long,
        mutation: Long,
        safeCode: String? = null,
    ) {
        require(generation >= 0 && mutation >= 0)
        database.ttsRuntimeReload.putJournal(
            TtsRuntimeReloadJournal(
                mutation = mutation,
                generation = generation,
                status = TtsRuntimeReloadState.STATUS_APPLIED,
                safeCode = safeCode,
            ),
        )
        database.ttsRuntimeReload.putState(
            TtsRuntimeReloadState(
                generation = generation,
                mutation = mutation,
                status = TtsRuntimeReloadState.STATUS_APPLIED,
                safeCode = safeCode,
            ),
        )
    }
}
