package com.immersive.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

class RainbowLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val strokeWidth = 16f
    private val rect = RectF()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RainbowLockView.strokeWidth
    }

    private val colors = intArrayOf(
        0xFFFF003C.toInt(), // 红
        0xFFFF7A00.toInt(), // 橙
        0xFFFFE600.toInt(), // 黄
        0xFF2DE06D.toInt(), // 绿
        0xFF3A6BFF.toInt(), // 蓝
        0xFFFF003C.toInt(), // 回到红，闭环
    )

    private var sweepGradient: SweepGradient? = null
    private var rotationDegrees: Float = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sweepGradient = SweepGradient(w / 2f, h / 2f, colors, null)
    }

    fun setRotationDegrees(value: Float) {
        rotationDegrees = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shader = sweepGradient ?: return
        matrix.reset()
        matrix.preRotate(rotationDegrees, width / 2f, height / 2f)
        shader.setLocalMatrix(matrix)
        paint.shader = shader

        val inset = strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(rect, 38f, 38f, paint)
    }
}
