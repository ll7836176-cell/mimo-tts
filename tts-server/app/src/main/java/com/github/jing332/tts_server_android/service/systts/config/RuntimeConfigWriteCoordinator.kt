package com.github.jing332.tts_server_android.service.systts.config

import androidx.room.withTransaction
import com.github.jing332.tts_server_android.data.AppDatabase
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.service.systts.help.RuntimeReloadStore
import com.github.jing332.tts_server_android.model.speech.tts.TtsServerSafeErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes Room current-row writes and publishes immutable runtime roots only
 * after the transaction has committed. A stale generation can never replace a
 * newer root.
 */
class RuntimeConfigWriteCoordinator<T : Any>(
    private val database: AppDatabase = appDb,
    private val reloadStore: RuntimeReloadStore = RuntimeReloadStore(database),
    private val onPublished: (Long) -> Unit = {},
) {
    data class RuntimeRoot<T>(val generation: Long, val value: T)

    sealed interface PublishResult<out T> {
        data class Published<T>(val root: RuntimeRoot<T>) : PublishResult<T>
        data class Rejected(val code: TtsServerSafeErrorCode) : PublishResult<Nothing>
    }

    private val mutex = Mutex()
    private val root = AtomicReference<RuntimeRoot<T>?>(null)
    private val mutation = AtomicLong(reloadStore.readApplied()?.mutation ?: 0L)

    suspend fun publish(
        applyCurrentRows: () -> Unit,
        buildCommittedCandidate: () -> T,
        safeCode: String? = null,
    ): PublishResult<T> = mutex.withLock {
        val previous = reloadStore.readApplied()
        val generation = maxOf(previous?.generation ?: 0L, root.get()?.generation ?: 0L) + 1L
        val mutationId = maxOf(previous?.mutation ?: 0L, mutation.get()) + 1L
        database.withTransaction {
            applyCurrentRows()
            reloadStore.recordAppliedInTransaction(generation, mutationId, safeCode)
        }
        mutation.set(mutationId)
        val candidate = RuntimeRoot(generation, buildCommittedCandidate())
        var result: PublishResult<T>? = null
        while (result == null) {
            val current = root.get()
            if (current != null && current.generation >= candidate.generation) {
                result = PublishResult.Rejected(TtsServerSafeErrorCode.RUNTIME_PUBLISH_FAILED)
            } else if (root.compareAndSet(current, candidate)) {
                result = PublishResult.Published(candidate)
                runCatching { onPublished(candidate.generation) }
            }
        }
        checkNotNull(result)
    }

    suspend fun recover(loadCommittedCandidate: (Long) -> T?): Boolean = mutex.withLock {
        val marker = reloadStore.readApplied()
        val value = marker?.let { loadCommittedCandidate(it.generation) }
        if (marker == null || value == null) {
            false
        } else {
            val candidate = RuntimeRoot(marker.generation, value)
            var recovered = false
            var finished = false
            while (!finished) {
                val current = root.get()
                if (current != null && current.generation >= candidate.generation) {
                    finished = true
                } else if (root.compareAndSet(current, candidate)) {
                    recovered = true
                    finished = true
                }
            }
            recovered
        }
    }

    fun snapshot(): RuntimeRoot<T>? = root.get()
}
