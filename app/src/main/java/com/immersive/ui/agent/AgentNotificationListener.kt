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
 * P2 event-driven activation: notification listener service.
 *
 * Architecture:
 * - Listen to system notifications and identify events that can trigger the agent
 * - Support triggers from allowlisted apps
 * - Support keyword-based triggers
 * - Emit NotificationEvent instances for the orchestrator to consume
 *
 * Example trigger scenarios:
 * 1. Delivery-arrived notification -> open the delivery app to check the pickup code
 * 2. Package-delivered notification -> open the courier app to inspect details
 * 3. Calendar reminder notification -> open the calendar app
 * 4. Message notification -> open the chat app and reply
 *
 * Safety considerations:
 * - Only listen to notifications from allowlisted apps
 * - Sensitive apps such as banking and payment apps are excluded by default
 * - Users can disable auto-triggering at any time
 */
class AgentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AgentNotificationListener"

        @Volatile
        var instance: AgentNotificationListener? = null
            private set

        // ========== Event stream (static, externally subscribable) ==========
        private val _notificationEvents = MutableSharedFlow<NotificationEvent>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val notificationEvents: SharedFlow<NotificationEvent> = _notificationEvents.asSharedFlow()

        private val _serviceState = MutableStateFlow(ListenerState.DISCONNECTED)
        val serviceState: StateFlow<ListenerState> = _serviceState.asStateFlow()

        // ========== Configuration ==========
        private var config = NotificationListenerConfig()

        fun updateConfig(newConfig: NotificationListenerConfig) {
            config = newConfig
        }

        /**
         * Check whether notification listener access has been granted.
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
         * Open the notification listener permission settings page.
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
        // Optional: track notification removal events.
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification ?: return

        // 1. Check whether the app is on the denylist.
        if (config.blacklistPackages.contains(packageName)) {
            Log.v(TAG, "Notification from blacklisted package: $packageName")
            return
        }

        // 2. Extract notification content.
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val combinedText = "$title $text $bigText $subText"

        // 3. Check whether any trigger condition matches.
        val triggerMatch = findTriggerMatch(packageName, combinedText)
        if (triggerMatch == null && !config.whitelistPackages.contains(packageName)) {
            Log.v(TAG, "Notification doesn't match any trigger: $packageName")
            return
        }

        // 4. Build the event payload.
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

        // Check keyword triggers.
        for (trigger in config.keywordTriggers) {
            if (trigger.keywords.any { lowerText.contains(it.lowercase()) }) {
                // Check package-name filtering when configured.
                if (trigger.packageFilter.isEmpty() || trigger.packageFilter.contains(packageName)) {
                    return TriggerMatch(
                        type = TriggerType.KEYWORD_MATCH,
                        keyword = trigger.keywords.first { lowerText.contains(it.lowercase()) },
                        suggestedGoal = trigger.suggestedGoal,
                    )
                }
            }
        }

        // Check app-specific triggers.
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

// ========== Data classes ==========

data class NotificationListenerConfig(
    val enabled: Boolean = true,

    // Allowlisted apps: every notification from these apps triggers an event.
    val whitelistPackages: Set<String> = emptySet(),

    // Denylisted apps: notifications from these apps never trigger events.
    val blacklistPackages: Set<String> = setOf(
        // Banking apps
        "com.icbc", "com.ccb.ccbapp", "com.chinamworld.bocmbci",
        "com.cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity",
        // Payment apps
        "com.eg.android.AlipayGphone", "com.tencent.mm",
        // Brokerage apps
        "com.hexin.plat.android", "com.eastmoney.android.berlin",
    ),

    // Keyword-based triggers
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

    // App-specific triggers
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
