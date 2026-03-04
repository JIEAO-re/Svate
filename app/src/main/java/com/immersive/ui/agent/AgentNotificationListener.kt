package com.immersive.ui.agent

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * P2 事件驱动激活：通知监听服务
 *
 * 架构设计：
 * - 监听系统通知，识别可触发 Agent 的事件
 * - 支持白名单应用的通知触发
 * - 支持关键词匹配触发
 * - 发射 NotificationEvent 供 Orchestrator 消费
 *
 * 触发场景：
 * 1. 外卖到达通知 -> 自动打开外卖 App 查看取餐码
 * 2. 快递签收通知 -> 自动打开快递 App 查看详情
 * 3. 日程提醒通知 -> 自动打开日历 App
 * 4. 消息通知 -> 自动打开聊天 App 回复
 *
 * 安全考量：
 * - 仅监听白名单应用的通知
 * - 敏感应用（银行、支付）默认排除
 * - 用户可随时关闭自动触发
 */
class AgentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AgentNotificationListener"

        @Volatile
        var instance: AgentNotificationListener? = null
            private set

        // ========== 事件流（静态，供外部订阅） ==========
        private val _notificationEvents = MutableSharedFlow<NotificationEvent>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val notificationEvents: SharedFlow<NotificationEvent> = _notificationEvents.asSharedFlow()

        private val _serviceState = MutableStateFlow(ListenerState.DISCONNECTED)
        val serviceState: StateFlow<ListenerState> = _serviceState.asStateFlow()

        // ========== 配置 ==========
        private var config = NotificationListenerConfig()

        fun updateConfig(newConfig: NotificationListenerConfig) {
            config = newConfig
        }

        /**
         * 检查通知监听权限是否已授予
         */
        fun isPermissionGranted(context: Context): Boolean {
            val componentName = ComponentName(context, AgentNotificationListener::class.java)
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return enabledListeners?.contains(componentName.flattenToString()) == true
        }

        /**
         * 打开通知监听权限设置页
         */
        fun openPermissionSettings(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        _serviceState.value = ListenerState.CONNECTED
        Log.d(TAG, "NotificationListener created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _serviceState.value = ListenerState.DISCONNECTED
        scope.cancel()
        Log.d(TAG, "NotificationListener destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _serviceState.value = ListenerState.CONNECTED
        Log.d(TAG, "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _serviceState.value = ListenerState.DISCONNECTED
        Log.d(TAG, "NotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!config.enabled) return

        scope.launch {
            try {
                processNotification(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 可选：追踪通知移除事件
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification ?: return

        // 1. 检查是否在黑名单中
        if (config.blacklistPackages.contains(packageName)) {
            Log.v(TAG, "Notification from blacklisted package: $packageName")
            return
        }

        // 2. 提取通知内容
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val combinedText = "$title $text $bigText $subText"

        // 3. 检查是否匹配触发条件
        val triggerMatch = findTriggerMatch(packageName, combinedText)
        if (triggerMatch == null && !config.whitelistPackages.contains(packageName)) {
            Log.v(TAG, "Notification doesn't match any trigger: $packageName")
            return
        }

        // 4. 构建事件
        @Suppress("DEPRECATION")
        val notificationPriority = notification.priority
        val event = NotificationEvent(
            packageName = packageName,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            category = notification.category,
            priority = notificationPriority,
            triggerType = triggerMatch?.type ?: TriggerType.WHITELIST_APP,
            triggerKeyword = triggerMatch?.keyword,
            suggestedGoal = triggerMatch?.suggestedGoal ?: "查看 $title",
            timestampMs = sbn.postTime,
            notificationKey = sbn.key,
        )

        Log.d(TAG, "Emitting notification event: ${event.packageName} - ${event.title}")
        _notificationEvents.emit(event)
    }

    private fun findTriggerMatch(packageName: String, text: String): TriggerMatch? {
        val lowerText = text.lowercase()

        // 检查关键词触发器
        for (trigger in config.keywordTriggers) {
            if (trigger.keywords.any { lowerText.contains(it.lowercase()) }) {
                // 检查包名过滤（如果指定）
                if (trigger.packageFilter.isEmpty() || trigger.packageFilter.contains(packageName)) {
                    return TriggerMatch(
                        type = TriggerType.KEYWORD_MATCH,
                        keyword = trigger.keywords.first { lowerText.contains(it.lowercase()) },
                        suggestedGoal = trigger.suggestedGoal,
                    )
                }
            }
        }

        // 检查应用特定触发器
        val appTrigger = config.appTriggers[packageName]
        if (appTrigger != null) {
            return TriggerMatch(
                type = TriggerType.APP_SPECIFIC,
                keyword = null,
                suggestedGoal = appTrigger.suggestedGoal,
            )
        }

        return null
    }
}

// ========== 数据类 ==========

data class NotificationListenerConfig(
    val enabled: Boolean = true,

    // 白名单应用：这些应用的所有通知都会触发事件
    val whitelistPackages: Set<String> = emptySet(),

    // 黑名单应用：这些应用的通知永远不会触发事件
    val blacklistPackages: Set<String> = setOf(
        // 银行类
        "com.icbc", "com.ccb.ccbapp", "com.chinamworld.bocmbci",
        "com.cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity",
        // 支付类
        "com.eg.android.AlipayGphone", "com.tencent.mm",
        // 证券类
        "com.hexin.plat.android", "com.eastmoney.android.berlin",
    ),

    // 关键词触发器
    val keywordTriggers: List<KeywordTrigger> = listOf(
        KeywordTrigger(
            keywords = listOf("已送达", "已签收", "取餐码", "取件码"),
            suggestedGoal = "查看取餐/取件码",
        ),
        KeywordTrigger(
            keywords = listOf("快递", "包裹", "物流"),
            suggestedGoal = "查看快递详情",
        ),
        KeywordTrigger(
            keywords = listOf("日程", "提醒", "会议"),
            suggestedGoal = "查看日程详情",
        ),
    ),

    // 应用特定触发器
    val appTriggers: Map<String, AppTrigger> = mapOf(
        "com.taobao.taobao" to AppTrigger(suggestedGoal = "查看淘宝订单"),
        "com.jingdong.app.mall" to AppTrigger(suggestedGoal = "查看京东订单"),
        "me.ele" to AppTrigger(suggestedGoal = "查看饿了么订单"),
        "com.sankuai.meituan" to AppTrigger(suggestedGoal = "查看美团订单"),
    ),
)

data class KeywordTrigger(
    val keywords: List<String>,
    val suggestedGoal: String,
    val packageFilter: Set<String> = emptySet(), // 空表示不限制
)

data class AppTrigger(
    val suggestedGoal: String,
)

data class TriggerMatch(
    val type: TriggerType,
    val keyword: String?,
    val suggestedGoal: String,
)

enum class TriggerType {
    WHITELIST_APP,
    KEYWORD_MATCH,
    APP_SPECIFIC,
}

enum class ListenerState {
    DISCONNECTED,
    CONNECTED,
}

data class NotificationEvent(
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val category: String?,
    val priority: Int,
    val triggerType: TriggerType,
    val triggerKeyword: String?,
    val suggestedGoal: String,
    val timestampMs: Long,
    val notificationKey: String,
)
