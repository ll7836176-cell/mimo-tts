package com.jingyue.mimotts.tts

import com.jingyue.mimotts.core.spi.SynthesisProvider

/**
 * Production-only injection seam. Debug fake providers and counters live in
 * the debug source set and can only install an in-process provider here.
 */
object MiMoTargetProcessHooks {
    private val lock = Any()
    private var providerOverride: SynthesisProvider? = null
    private var providerDecorator: ((SynthesisProvider) -> SynthesisProvider)? = null
    private var sinkEventRecorder: (() -> Unit)? = null

    fun install(provider: SynthesisProvider, sinkEventRecorder: () -> Unit) = synchronized(lock) {
        providerOverride = provider
        providerDecorator = null
        this.sinkEventRecorder = sinkEventRecorder
    }

    fun installDecorator(
        decorator: (SynthesisProvider) -> SynthesisProvider,
        sinkEventRecorder: () -> Unit,
    ) = synchronized(lock) {
        providerOverride = null
        providerDecorator = decorator
        this.sinkEventRecorder = sinkEventRecorder
    }

    fun clear() = synchronized(lock) {
        providerOverride = null
        providerDecorator = null
        sinkEventRecorder = null
    }

    fun providerOr(default: SynthesisProvider): SynthesisProvider = synchronized(lock) {
        providerOverride ?: providerDecorator?.invoke(default) ?: default
    }

    fun recordSinkEvent() {
        synchronized(lock) { sinkEventRecorder }?.invoke()
    }
}
