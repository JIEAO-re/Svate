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
 * SoM (Set-of-Mark) 渲染器。
 *
 * 在截图上为每个可交互 UI 节点绘制带编号的红色半透明框，
 * 让 Gemini 只需返回编号 ID 而不用猜坐标，准确率逼近 99%。
 *
 * 输出：标注后的截图 Base64 + Map<编号, UiNode> 映射表
 */
object SomRenderer {

    data class SomResult(
        /** Base64 编码的标注后截图 */
        val annotatedImageBase64: String,
        /** SoM 编号 → UiNode 的映射表 */
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
     * 过滤出有交互价值的节点（可点击/可编辑/可滚动/有文本内容）。
     * 这也做了 UI 树的深度剪枝——去掉纯装饰性节点。
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
     * 在截图 Bitmap 上绘制 SoM 标注。
     *
     * @param screenshotBase64 原始截图的 Base64
     * @param interactiveNodes 已过滤的可交互节点列表
     * @param screenWidth 真实屏幕像素宽度
     * @param screenHeight 真实屏幕像素高度
     * @return SomResult 包含标注图 Base64 和映射表
     */
    fun render(
        screenshotBase64: String,
        interactiveNodes: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): SomResult? {
        if (interactiveNodes.isEmpty()) return null

        // 解码原始截图
        val imageBytes = Base64.decode(screenshotBase64, Base64.NO_WRAP)
        val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return null

        val bitmapWidth = originalBitmap.width
        val bitmapHeight = originalBitmap.height

        // 截图分辨率可能与屏幕分辨率不同（截图时做了缩放），需要计算比例
        val scaleX = bitmapWidth.toFloat() / screenWidth
        val scaleY = bitmapHeight.toFloat() / screenHeight

        // 在副本上绘制
        val annotated = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        originalBitmap.recycle()

        val canvas = Canvas(annotated)
        val markerMap = mutableMapOf<Int, UiNode>()

        // 限制最多标注 30 个节点，避免截图过于拥挤
        val nodesToMark = interactiveNodes.take(30)

        nodesToMark.forEachIndexed { index, node ->
            val markerId = index + 1 // 从 1 开始编号
            markerMap[markerId] = node

            // 将屏幕坐标转为截图坐标，先 clamp 屏幕坐标防止越界
            val clampedLeft = node.bounds.left.coerceIn(0, screenWidth)
            val clampedTop = node.bounds.top.coerceIn(0, screenHeight)
            val clampedRight = node.bounds.right.coerceIn(0, screenWidth)
            val clampedBottom = node.bounds.bottom.coerceIn(0, screenHeight)

            val left = (clampedLeft * scaleX).roundToInt().coerceIn(0, bitmapWidth - 1)
            val top = (clampedTop * scaleY).roundToInt().coerceIn(0, bitmapHeight - 1)
            val right = (clampedRight * scaleX).roundToInt().coerceIn(left + 1, bitmapWidth)
            val bottom = (clampedBottom * scaleY).roundToInt().coerceIn(top + 1, bitmapHeight)

            val rect = Rect(left, top, right, bottom)

            // 绘制半透明填充 + 红色边框
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, borderPaint)

            // 在左上角绘制编号徽章
            val label = markerId.toString()
            val textWidth = textPaint.measureText(label)
            val badgeWidth = textWidth + 12f
            val badgeHeight = textPaint.textSize + 8f

            val badgeLeft = left.toFloat()
            val badgeTop = top.toFloat()
            val badgeRight = (badgeLeft + badgeWidth).coerceAtMost(bitmapWidth.toFloat())
            val badgeBottom = (badgeTop + badgeHeight).coerceAtMost(bitmapHeight.toFloat())

            // 白底圆角矩形
            canvas.drawRoundRect(
                badgeLeft, badgeTop, badgeRight, badgeBottom,
                6f, 6f, badgePaint,
            )
            // 黑色数字
            canvas.drawText(
                label,
                badgeLeft + badgeWidth / 2f,
                badgeTop + badgeHeight - 6f,
                textPaint,
            )
        }

        // 编码为 Base64
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
