package com.immersive.ui.agent

import kotlin.math.max
import kotlin.math.min

data class ActionResolveResult(
    val node: UiNode,
    val score: Int,
    val normalizedBbox: IntArray,
)

// ============================================================================
// P1 addition: Spatial Grounding coordinate resolution result
// ============================================================================
/**
 * Spatial Grounding result for pure visual interaction mode.
 *
 * When Gemini returns spatial_coordinates, the action can bypass the UI tree
 * and execute directly from normalized coordinates converted to absolute pixels.
 */
data class SpatialResolveResult(
    /** Normalized X coordinate in [0.0, 1.0]. */
    val normalizedX: Float,
    /** Normalized Y coordinate in [0.0, 1.0]. */
    val normalizedY: Float,
    /** Absolute pixel X coordinate. */
    val absoluteX: Float,
    /** Absolute pixel Y coordinate. */
    val absoluteY: Float,
)

/**
 * Action targeting strategies, ordered by priority.
 */
enum class TargetingMethod {
    /** P1 preferred path: Gemini Spatial Grounding visual coordinates. */
    SPATIAL_COORDINATES,
    /** SoM marker ID. */
    SOM_ID,
    /** UI tree selector. */
    SELECTOR,
    /** Legacy bbox path (deprecated, compatibility only). */
    LEGACY_BBOX,
}

// ============================================================================
// clampBbox: clamp (ymin, xmin, ymax, xmax) into the [0, 1000] range.
// This prevents out-of-bounds coordinates from causing bad taps or render issues.
// ============================================================================
/**
 * Clamp bbox coordinates into the [0, 1000] range with the (ymin, xmin, ymax, xmax) format.
 * Also guarantees min <= max.
 *
 * @param bbox Raw bbox array, which must have length 4.
 * @return The clamped IntArray, or null when the input is invalid.
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

    // ========== P1 addition: Spatial Grounding coordinate parsing ==========
    /**
     * Parse normalized Spatial Grounding coordinates into absolute pixel coordinates.
     *
     * This is the highest-priority parsing path in the P1 visual foundation update.
     * When the cloud response includes spatial_coordinates, this method can execute
     * directly without relying on the UI tree.
     *
     * @param spatialCoordinates Normalized [x, y] coordinates in the [0.0, 1.0] range.
     * @param screenWidth Screen width in pixels.
     * @param screenHeight Screen height in pixels.
     * @return A SpatialResolveResult, or null when the coordinates are invalid.
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
     * Parse Spatial Grounding coordinates from a FloatArray.
     */
    fun resolveSpatialCoordinates(
        spatialCoordinates: FloatArray,
        screenWidth: Int,
        screenHeight: Int,
    ): SpatialResolveResult? {
        return resolveSpatialCoordinates(spatialCoordinates.toList(), screenWidth, screenHeight)
    }

    /**
     * Determine which targeting strategy an action command uses, ordered by priority.
     *
     * Priority: SPATIAL_COORDINATES > SOM_ID > SELECTOR > LEGACY_BBOX
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
