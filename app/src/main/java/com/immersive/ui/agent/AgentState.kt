package com.immersive.ui.agent

import android.util.Base64

enum class TaskMode {
    GENERAL,
    SEARCH,
    RESEARCH,
    HOMEWORK,
}

enum class HomeworkPolicy {
    REFERENCE_ONLY,
    NAVIGATION_ONLY,
}

data class TaskSpec(
    val mode: TaskMode = TaskMode.GENERAL,
    val searchQuery: String = "",
    val researchDepth: Int = 3,
    val homeworkPolicy: HomeworkPolicy = HomeworkPolicy.REFERENCE_ONLY,
    val askOnUncertain: Boolean = true,
) {
    companion object {
        fun fromRaw(
            taskMode: String?,
            searchQuery: String?,
            researchDepth: Int?,
            homeworkPolicy: String?,
            askOnUncertain: Boolean?,
        ): TaskSpec {
            val mode = try {
                TaskMode.valueOf(taskMode.orEmpty().uppercase())
            } catch (_: Exception) {
                TaskMode.GENERAL
            }
            val policy = try {
                HomeworkPolicy.valueOf(homeworkPolicy.orEmpty().uppercase())
            } catch (_: Exception) {
                HomeworkPolicy.REFERENCE_ONLY
            }
            return TaskSpec(
                mode = mode,
                searchQuery = searchQuery.orEmpty().trim(),
                researchDepth = (researchDepth ?: 3).coerceIn(1, 8),
                homeworkPolicy = policy,
                askOnUncertain = askOnUncertain ?: true,
            )
        }
    }
}

data class DecisionOption(
    val id: String,
    val title: String,
    val description: String,
    val recommended: Boolean = false,
)

data class DecisionRequest(
    val reason: String,
    val question: String,
    val options: List<DecisionOption>,
    val timeoutSeconds: Int = 60,
    val fallbackOptionId: String? = null,
) {
    fun fallbackOption(): DecisionOption? {
        fallbackOptionId?.let { preferred ->
            options.firstOrNull { it.id == preferred }?.let { return it }
        }
        return options.firstOrNull { it.recommended } ?: options.firstOrNull()
    }
}

data class KnowledgeCapture(
    val sourceTitle: String,
    val sourceSnippet: String,
    val sourceHint: String,
    val confidence: Float = 0f,
)

data class ActionSelector(
    val packageName: String = "",
    val resourceId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val className: String = "",
    val boundsHint: IntArray? = null,
    val nodeSignature: String = "",
) {
    /** 检查是否有任何有效的选择器字段 */
    fun hasAnyField(): Boolean {
        return packageName.isNotBlank() ||
            resourceId.isNotBlank() ||
            text.isNotBlank() ||
            contentDesc.isNotBlank() ||
            className.isNotBlank() ||
            nodeSignature.isNotBlank() ||
            (boundsHint != null && boundsHint.size == 4)
    }
}

data class CapturedFrame(
    val frameId: String,
    val tsMs: Long,
    val imageBase64: String = "",
    val uiSignature: String,
    /** Raw JPEG bytes — primary transport for GCS upload and local processing. */
    val imageBytes: ByteArray? = null,
    val gcsUri: String? = null,
) {
    private val inlineImageBase64 by lazy(LazyThreadSafetyMode.NONE) {
        when {
            imageBase64.isNotBlank() -> imageBase64
            imageBytes != null -> Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            else -> null
        }
    }

    fun inlineImageBase64OrNull(): String? = inlineImageBase64
}

data class IntentSpec(
    val action: String,
    val dataUri: String? = null,
    val packageName: String? = null,
    val extras: Map<String, Any?> = emptyMap(),
)

enum class ActionIntent {
    CLICK,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    TYPE,
    SUBMIT_INPUT,
    BACK,
    HOME,
    OPEN_APP,
    OPEN_INTENT,
    WAIT,
    FINISH,
}

enum class RiskLevel {
    SAFE,
    WARNING,
    HIGH,
}

enum class ObservationReason {
    APP_START,
    UI_CHANGED,
    AFTER_ACTION,
    RECOVERY,
    TIMEOUT,
}

data class AgentAction(
    val intent: ActionIntent,
    val targetDesc: String,
    val targetBbox: IntArray?,
    val targetSomId: Int? = null,
    // ========== P1 新增：Spatial Grounding 原生坐标 ==========
    // 当 Gemini 返回纯视觉定位坐标时，此字段为首选执行路径。
    // 优先级：spatialCoordinates > targetSomId > selector > targetBbox
    val spatialCoordinates: FloatArray? = null,
    val inputText: String? = null,
    val packageName: String? = null,
    val intentSpec: IntentSpec? = null,
    val riskLevel: RiskLevel = RiskLevel.SAFE,
    val actionId: String = "",
    val selector: ActionSelector? = null,
    val expectedPackage: String? = null,
    val expectedPageType: String? = null,
    val expectedElements: List<String> = emptyList(),
    val elderlyNarration: String,
    val reasoning: String,
    val subStepCompleted: Boolean = false,
    val decisionRequest: DecisionRequest? = null,
    val knowledgeCapture: KnowledgeCapture? = null,
) {
    /**
     * 获取首选的定位方式。
     * 优先级：SPATIAL_COORDINATES > SOM_ID > SELECTOR > LEGACY_BBOX
     */
    fun getPreferredTargetingMethod(): TargetingMethod? {
        return ActionResolver.detectTargetingMethod(
            spatialCoordinates = spatialCoordinates?.toList(),
            targetSomId = targetSomId,
            selector = selector,
            targetBbox = targetBbox,
        )
    }

    /** 是否使用 Spatial Grounding 纯视觉定位 */
    fun usesSpatialGrounding(): Boolean {
        return spatialCoordinates != null && spatialCoordinates.size == 2
    }
}

enum class AgentPhase {
    IDLE,
    DECOMPOSING,
    PERCEIVING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    REFLECTING,
    RECOVERING,
    WAITING_CONFIRM,
    WAITING_USER_DECISION,
    WAITING_STABLE,
    REPORTING,
    COMPLETED,
    FAILED,
}

data class StepRecord(
    val stepIndex: Int,
    val action: AgentAction,
    val success: Boolean,
    val resultSummary: String,
    val reviewVerdict: String? = null,
    val resolveScore: Int? = null,
    val checkpointMatched: Boolean = false,
    val failCode: String? = null,
)

data class ResolvedNodeReceipt(
    val text: String = "",
    val contentDesc: String = "",
    val resourceId: String = "",
    val className: String = "",
    val packageName: String = "",
)

data class ExecutionReceipt(
    val actionId: String,
    val resolvedNode: ResolvedNodeReceipt? = null,
    val resolveScore: Int? = null,
    val executeResult: String,
    val checkpointMatch: Boolean,
    val failCode: String? = null,
    val uiSignatureBefore: String,
    val uiSignatureAfter: String,
    val mediaSource: String = "SCREEN_RECORDING",
    val frameCount: Int = 0,
)

data class AgentContext(
    val globalGoal: String = "",
    val targetAppName: String = "",
    val taskSpec: TaskSpec = TaskSpec(),
    val phase: AgentPhase = AgentPhase.IDLE,
    val stepIndex: Int = 0,
    val maxSteps: Int = 30,
    val history: List<StepRecord> = emptyList(),
    val lastUiTreeSummary: String? = null,
    val lastScreenBase64: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val taskPlan: TaskPlan? = null,
    val currentSubStepIndex: Int = 0,
    val decisionNotes: List<String> = emptyList(),
    val knowledgeCaptures: List<KnowledgeCapture> = emptyList(),
    val finalReport: String? = null,
    val consecutiveFailCount: Int = 0,
    val reflectionUsed: Boolean = false,
    val alternativeStrategy: String? = null,
    val traceId: String? = null,
    val shadowAction: AgentAction? = null,
    val serverLatencyMs: Long = 0L,
    val lastReviewerVerdict: String? = null,
) {
    fun isTerminal(): Boolean = phase == AgentPhase.COMPLETED || phase == AgentPhase.FAILED
    fun isOverStepLimit(): Boolean = stepIndex >= maxSteps
    fun isOverRetryLimit(): Boolean = retryCount >= maxRetries

    fun currentSubStep(): SubStep? = taskPlan?.steps?.getOrNull(currentSubStepIndex)
    fun isAllSubStepsDone(): Boolean {
        val plan = taskPlan ?: return false
        return currentSubStepIndex >= plan.steps.size
    }
}

data class SafetyCheckResult(
    val allowed: Boolean,
    val reason: String? = null,
)

object AgentActionSafety {
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val MIN_BBOX_SIDE = 24

    private val HARD_BLOCK_KEYWORDS = listOf(
        "pay",
        "payment",
        "transfer",
        "recharge",
        "order",
        "purchase",
        "checkout",
        "submit",
        "publish",
        "authorize",
        "password",
        "delete",
        "uninstall",
        "format",
        "factory_reset",
        "wipe",
        "\u652f\u4ed8",   // 支付
        "\u8f6c\u8d26",   // 转账
        "\u5bc6\u7801",   // 密码
        "\u6388\u6743",   // 授权
        "\u63d0\u4ea4",   // 提交
        "\u53d1\u5e03",   // 发布
        "\u4e0b\u5355",   // 下单
        "\u5220\u9664",   // 删除
        "\u5378\u8f7d",   // 卸载
        "\u683c\u5f0f\u5316", // 格式化
        "\u6e05\u7a7a",   // 清空
    )

    fun validateClickBbox(
        bbox: IntArray?,
        minBboxSide: Int = MIN_BBOX_SIDE,
    ): SafetyCheckResult {
        if (bbox == null) return SafetyCheckResult(false, "missing_bbox")
        if (bbox.size != 4) return SafetyCheckResult(false, "invalid_bbox_size")

        val ymin = bbox[0]
        val xmin = bbox[1]
        val ymax = bbox[2]
        val xmax = bbox[3]

        if (bbox.any { it !in 0..1000 }) return SafetyCheckResult(false, "bbox_out_of_range")
        if (ymax <= ymin || xmax <= xmin) return SafetyCheckResult(false, "bbox_invalid_order")
        if ((ymax - ymin) < minBboxSide || (xmax - xmin) < minBboxSide) {
            return SafetyCheckResult(false, "bbox_too_small")
        }
        return SafetyCheckResult(true)
    }

    fun containsHardBlockedKeyword(text: String?): Boolean {
        val normalized = text.orEmpty().lowercase()
        if (normalized.isBlank()) return false
        val isSearchSubmit = (normalized.contains("submit") || normalized.contains("\u63d0\u4ea4")) &&
            (normalized.contains("search") ||
                normalized.contains("find") ||
                normalized.contains("query") ||
                normalized.contains("\u641c\u7d22") ||
                normalized.contains("\u67e5\u627e"))
        if (isSearchSubmit) return false
        return HARD_BLOCK_KEYWORDS.any { keyword ->
            normalized.contains(keyword.lowercase())
        }
    }

    fun isKnownLauncherPackage(
        packageName: String?,
        launchablePackages: Set<String>,
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        return launchablePackages.contains(packageName)
    }

    fun isBlockedSystemPackage(
        packageName: String?,
        blockedPackage: String = SYSTEM_UI_PACKAGE,
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName == blockedPackage
    }

    fun isSystemUiTarget(
        bbox: IntArray,
        nodes: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
        blockedPackage: String = SYSTEM_UI_PACKAGE,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val centerX = (((bbox[1] + bbox[3]) / 2f) / 1000f * screenWidth).toInt()
        val centerY = (((bbox[0] + bbox[2]) / 2f) / 1000f * screenHeight).toInt()
        return nodes.any { node ->
            node.packageName == blockedPackage &&
                centerX >= node.bounds.left &&
                centerX < node.bounds.right &&
                centerY >= node.bounds.top &&
                centerY < node.bounds.bottom
        }
    }
}
