package com.github.jing332.tts_server_android.service.systts.config

import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import com.github.jing332.tts_server_android.service.systts.help.RuntimeReloadStore

/** Single entry point for writes that must publish an APPLIED runtime marker. */
object RuntimeConfigWriter {
    private val coordinator by lazy {
        RuntimeConfigWriteCoordinator<Unit>(
            onPublished = { SystemTtsService.notifyUpdateConfig() },
        )
    }

    suspend fun publish(applyCurrentRows: () -> Unit): Boolean =
        when (coordinator.publish(
            applyCurrentRows = applyCurrentRows,
            buildCommittedCandidate = {},
        )) {
            is RuntimeConfigWriteCoordinator.PublishResult.Published -> true
            is RuntimeConfigWriteCoordinator.PublishResult.Rejected -> false
        }

    /** Rebuilds the in-process publication root from the committed marker. */
    suspend fun recover(): Boolean = coordinator.recover { Unit }

    fun appliedGeneration(): Long = RuntimeReloadStore(appDb).readApplied()?.generation ?: 0L
}
