package com.immersive.ui.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global event bus for the agent, using SharedFlow for leak-free cross-component communication.
 *
 * Replaces static callbacks to avoid leaking Activity references through captured lambdas.
 * - Services and overlays emit events through [requestStop]
 * - The Activity listens to [stopRequests] with lifecycleScope and is automatically unbound with the lifecycle
 */
object AgentEventBus {

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Flow observed by the Activity; call stopGuide() when a signal arrives. */
    val stopRequests = _stopRequests.asSharedFlow()

    /** Called when the floating stop button is tapped. */
    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}
