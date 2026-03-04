package com.immersive.ui.agent

import kotlin.math.max
import kotlin.math.min

data class ActionResolveResult(
    val node: UiNode,
    val score: Int,
    val normalizedBbox: IntArray,
)

// ============================================================================
// P1 新增：Spatial Grounding 坐标解析结果
// ============================================================================
/**
 * Spatial Grounding 解析结果，用于纯视觉盲打模式。
 *
 * 当 Gemini 返回 spatial_coordinates 时，无需依赖 UI 树，
 * 直接将归一化坐标转换为绝对像素坐标执行。
 */
data class SpatialResolveResult(
    /** 归一化 X 坐标 [0.0, 1.0] */
    val normalizedX: Float,
    /** 归一化 Y 坐标 [0.0, 1.0] */
    val normalizedY: Float,
    /** 绝对像素 X 坐标 */
    val absoluteX: Float,
    /** 绝对像素 Y 坐标 */
    val absoluteY: Float,
)

/**
 * 动作定位方式枚举，按优先级排序。
 */
enum class TargetingMethod {
    /** P1 首选：Gemini Spatial Grounding 纯视觉坐标 */
    SPATIAL_COORDINATES,
    /** SoM 标注 ID */
    SOM_ID,
    /** UI 树选择器 */
    SELECTOR,
    /** 旧版 Bbox（已废弃，仅兼容） */
    LEGACY_BBOX,
}

// ============================================================================
// clampBbox: 将 (ymin, xmin, ymax, xmax) 坐标 clamp 到 [0, 1000] 范围
// 防止越界坐标导致误触或渲染异常
// ============================================================================
/**
 * Clamp bbox 坐标到 [0, 1000] 范围，格式 (ymin, xmin, ymax, xmax)。
 * 同时确保 min <= max。
 *
 * @param bbox 原始 bbox 数组，长度必须为 4
 * @return clamped 后的 IntArray，或 null（输入无效时）
 */
fun clampBbox(bbox: IntArray?): IntArray? {
    if (bbox == null || bbox.size != 4) return null
    val ymin = bbox[0].coerceIn(0, 1000)
    val xmin = bbox[1].coerceIn(0, 1000)
    val ymax = bbox[2].coerceIn(0, 1000)
    val xmax = bbox[3].coerceIn(0, 1000)
    return intArrayOf(
        minOf(ymin, ymax),
        minOf(xmin, xmax),
        maxOf(ymin, ymax),
        maxOf(xmin, xmax),
    )
}

object ActionResolver {
    private const val RESOURCE_EXACT_SCORE = 5
    private const val TEXT_EXACT_SCORE = 3
    private const val DESC_EXACT_SCORE = 3
    private const val CLASS_MATCH_SCORE = 1
    private const val BOUNDS_IOU_SCORE = 2
    private const val BOUNDS_IOU_THRESHOLD = 0.4f
    const val RESOLVE_THRESHOLD = 5

    // ========== P1 新增：Spatial Grounding 坐标解析 ==========
    /**
     * 解析 Spatial Grounding 归一化坐标为绝对像素坐标。
     *
     * 这是 P1 视觉基座革命的核心解析路径，优先级最高。
     * 当云端返回 spatial_coordinates 时，直接调用此方法，
     * 无需依赖 UI 树即可执行盲打。
     *
     * @param spatialCoordinates [x, y] 归一化坐标，范围 [0.0, 1.0]
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     * @return SpatialResolveResult 或 null（坐标无效时）
     */
    fun resolveSpatialCoordinates(
        spatialCoordinates: List<Float>,
        screenWidth: Int,
        screenHeight: Int,
    ): SpatialResolveResult? {
        if (spatialCoordinates.size != 2) return null
        val (x, y) = spatialCoordinates
        if (x !in 0f..1f || y !in 0f..1f) return null
        if (screenWidth <= 0 || screenHeight <= 0) return null

        return SpatialResolveResult(
            normalizedX = x,
            normalizedY = y,
            absoluteX = x * screenWidth,
            absoluteY = y * screenHeight,
        )
    }

    /**
     * 从 FloatArray 解析 Spatial Grounding 坐标。
     */
    fun resolveSpatialCoordinates(
        spatialCoordinates: FloatArray,
        screenWidth: Int,
        screenHeight: Int,
    ): SpatialResolveResult? {
        return resolveSpatialCoordinates(spatialCoordinates.toList(), screenWidth, screenHeight)
    }

    /**
     * 判断动作命令使用的定位方式（按优先级）。
     *
     * 优先级：SPATIAL_COORDINATES > SOM_ID > SELECTOR > LEGACY_BBOX
     */
    fun detectTargetingMethod(
        spatialCoordinates: List<Float>?,
        targetSomId: Int?,
        selector: ActionSelector?,
        targetBbox: IntArray?,
    ): TargetingMethod? {
        return when {
            spatialCoordinates != null && spatialCoordinates.size == 2 -> TargetingMethod.SPATIAL_COORDINATES
            targetSomId != null && targetSomId > 0 -> TargetingMethod.SOM_ID
            selector != null && selector.hasAnyField() -> TargetingMethod.SELECTOR
            targetBbox != null && targetBbox.size == 4 -> TargetingMethod.LEGACY_BBOX
            else -> null
        }
    }

    fun resolve(
        selector: ActionSelector,
        nodes: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): ActionResolveResult? {
        if (nodes.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return null

        var bestResult: ActionResolveResult? = null
        var bestScore = Int.MIN_VALUE

        for (node in nodes) {
            if (selector.packageName.isNotBlank() &&
                !selector.packageName.equals(node.packageName, ignoreCase = true)
            ) {
                continue
            }
            val normalized = toNormalizedBbox(node, screenWidth, screenHeight)
            val score = scoreNode(selector, node, normalized)
            if (score > bestScore) {
                bestScore = score
                bestResult = ActionResolveResult(
                    node = node,
                    score = score,
                    normalizedBbox = normalized,
                )
            }
        }

        return bestResult?.takeIf { it.score >= RESOLVE_THRESHOLD }
    }

    private fun scoreNode(
        selector: ActionSelector,
        node: UiNode,
        normalizedBbox: IntArray,
    ): Int {
        var score = 0
        if (selector.resourceId.isNotBlank() &&
            selector.resourceId.equals(node.viewIdResourceName, ignoreCase = true)
        ) {
            score += RESOURCE_EXACT_SCORE
        }
        if (selector.text.isNotBlank() &&
            selector.text.equals(node.text, ignoreCase = true)
        ) {
            score += TEXT_EXACT_SCORE
        }
        if (selector.contentDesc.isNotBlank() &&
            selector.contentDesc.equals(node.contentDesc, ignoreCase = true)
        ) {
            score += DESC_EXACT_SCORE
        }
        if (selector.className.isNotBlank()) {
            val selectorClass = selector.className.substringAfterLast(".")
            val nodeClass = node.className.substringAfterLast(".")
            if (selectorClass.equals(nodeClass, ignoreCase = true)) {
                score += CLASS_MATCH_SCORE
            }
        }
        val hint = selector.boundsHint
        if (hint != null && hint.size == 4) {
            val iou = calculateIoU(hint, normalizedBbox)
            if (iou > BOUNDS_IOU_THRESHOLD) {
                score += BOUNDS_IOU_SCORE
            }
        }
        return score
    }

    private fun toNormalizedBbox(
        node: UiNode,
        screenWidth: Int,
        screenHeight: Int,
    ): IntArray {
        fun normX(value: Int): Int {
            if (screenWidth <= 0) return 0
            return ((value.toFloat() / screenWidth) * 1000f).toInt().coerceIn(0, 1000)
        }

        fun normY(value: Int): Int {
            if (screenHeight <= 0) return 0
            return ((value.toFloat() / screenHeight) * 1000f).toInt().coerceIn(0, 1000)
        }

        val raw = intArrayOf(
            normY(node.bounds.top),
            normX(node.bounds.left),
            normY(node.bounds.bottom),
            normX(node.bounds.right),
        )
        // Apply clamp to guarantee [0, 1000] and min <= max
        return clampBbox(raw) ?: raw
    }

    private fun calculateIoU(a: IntArray, b: IntArray): Float {
        val interTop = max(a[0], b[0])
        val interLeft = max(a[1], b[1])
        val interBottom = min(a[2], b[2])
        val interRight = min(a[3], b[3])

        val interWidth = (interRight - interLeft).coerceAtLeast(0)
        val interHeight = (interBottom - interTop).coerceAtLeast(0)
        val interArea = interWidth * interHeight
        if (interArea <= 0) return 0f

        val areaA = (a[3] - a[1]).coerceAtLeast(0) * (a[2] - a[0]).coerceAtLeast(0)
        val areaB = (b[3] - b[1]).coerceAtLeast(0) * (b[2] - b[0]).coerceAtLeast(0)
        val union = (areaA + areaB - interArea).coerceAtLeast(1)
        return interArea.toFloat() / union
    }
}

