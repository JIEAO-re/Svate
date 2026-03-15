package com.immersive.ui.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * SoM (Set-of-Mark) renderer.
 *
 * Draw numbered translucent red boxes for interactive UI nodes on top of the screenshot
 * so Gemini can return marker IDs instead of guessing coordinates.
 *
 * Output: the annotated screenshot as Base64 plus a Map<markerId, UiNode>.
 */
object SomRenderer {

    data class SomResult(
        /** Annotated screenshot encoded as Base64. */
        val annotatedImageBase64: String,
        /** Mapping from SoM marker IDs to UiNode objects. */
        val markerMap: Map<Int, UiNode>,
    )

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 50, 50) // 半透明红色边框
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 0, 0) // 极淡红色填充
        style = Paint.Style.FILL
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255) // 白色背景
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    /**
     * Filter nodes that provide interaction value (clickable, editable, scrollable, or text-bearing).
     * This also prunes the UI tree deeply by removing purely decorative nodes.
     */
    fun filterInteractiveNodes(nodes: List<UiNode>): List<UiNode> {
        return nodes.filter { node ->
            val hasSize = node.bounds.width() > 10 && node.bounds.height() > 10
            val isInteractive = node.isClickable || node.isEditable || node.isScrollable
            val hasContent = node.text.isNotBlank() || node.contentDesc.isNotBlank()
            hasSize && (isInteractive || hasContent)
        }
    }

    /**
     * Draw SoM annotations on the screenshot bitmap.
     *
     * @param screenshotBytes Raw JPEG bytes of the screenshot.
     * @param interactiveNodes Filtered list of interactive nodes.
     * @param screenWidth Actual screen width in pixels.
     * @param screenHeight Actual screen height in pixels.
     * @return A SomResult containing the annotated image Base64 and the marker map.
     */
    fun render(
        screenshotBytes: ByteArray,
        interactiveNodes: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): SomResult? {
        if (interactiveNodes.isEmpty()) return null

        val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(
            screenshotBytes,
            0,
            screenshotBytes.size,
        )
            ?: return null

        val bitmapWidth = originalBitmap.width
        val bitmapHeight = originalBitmap.height

        // The screenshot resolution may differ from the real screen resolution, so compute a scale ratio.
        val scaleX = bitmapWidth.toFloat() / screenWidth
        val scaleY = bitmapHeight.toFloat() / screenHeight

        // Draw on a mutable copy.
        val annotated = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        originalBitmap.recycle()

        val canvas = Canvas(annotated)
        val markerMap = mutableMapOf<Int, UiNode>()

        // Limit the overlay to 30 markers to keep the screenshot readable.
        val nodesToMark = interactiveNodes.take(30)

        nodesToMark.forEachIndexed { index, node ->
            val markerId = index + 1 // 从 1 开始编号
            markerMap[markerId] = node

            // Convert screen coordinates to screenshot coordinates and clamp first to avoid overflow.
            val clampedLeft = node.bounds.left.coerceIn(0, screenWidth)
            val clampedTop = node.bounds.top.coerceIn(0, screenHeight)
            val clampedRight = node.bounds.right.coerceIn(0, screenWidth)
            val clampedBottom = node.bounds.bottom.coerceIn(0, screenHeight)

            val left = (clampedLeft * scaleX).roundToInt().coerceIn(0, bitmapWidth - 1)
            val top = (clampedTop * scaleY).roundToInt().coerceIn(0, bitmapHeight - 1)
            val right = (clampedRight * scaleX).roundToInt().coerceIn(left + 1, bitmapWidth)
            val bottom = (clampedBottom * scaleY).roundToInt().coerceIn(top + 1, bitmapHeight)

            val rect = Rect(left, top, right, bottom)

            // Draw the translucent fill and red border.
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, borderPaint)

            // Draw the numbered badge in the top-left corner.
            val label = markerId.toString()
            val textWidth = textPaint.measureText(label)
            val badgeWidth = textWidth + 12f
            val badgeHeight = textPaint.textSize + 8f

            val badgeLeft = left.toFloat()
            val badgeTop = top.toFloat()
            val badgeRight = (badgeLeft + badgeWidth).coerceAtMost(bitmapWidth.toFloat())
            val badgeBottom = (badgeTop + badgeHeight).coerceAtMost(bitmapHeight.toFloat())

            // White rounded rectangle background.
            canvas.drawRoundRect(
                badgeLeft, badgeTop, badgeRight, badgeBottom,
                6f, 6f, badgePaint,
            )
            // Black marker text.
            canvas.drawText(
                label,
                badgeLeft + badgeWidth / 2f,
                badgeTop + badgeHeight - 6f,
                textPaint,
            )
        }

        // Encode to Base64.
        val output = ByteArrayOutputStream()
        annotated.compress(Bitmap.CompressFormat.JPEG, 75, output)
        annotated.recycle()
        val annotatedBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)

        return SomResult(
            annotatedImageBase64 = annotatedBase64,
            markerMap = markerMap,
        )
    }
}
