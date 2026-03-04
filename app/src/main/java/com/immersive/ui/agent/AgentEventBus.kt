package com.immersive.ui.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Agent 全局事件总线 —— 使用 SharedFlow 实现无泄漏的跨组件通信。
 *
 * 替代静态回调模式，避免 Lambda 隐式持有 Activity 引用导致内存泄漏。
 * - Service / Overlay 通过 [requestStop] 发送事件
 * - Activity 通过 lifecycleScope 监听 [stopRequests]，自动随生命周期解绑
 */
object AgentEventBus {

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Activity 监听此 Flow，收到信号后调用 stopGuide() */
    val stopRequests = _stopRequests.asSharedFlow()

    /** 悬浮按钮点击时调用 */
    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}
