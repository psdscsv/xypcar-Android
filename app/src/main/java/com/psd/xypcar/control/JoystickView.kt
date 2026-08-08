// JoystickView.kt
package com.psd.xypcar.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class JoystickView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    interface OnJoystickMoveListener {
        fun onMove(speed: Float, turn: Float)
    }

    private var listener: OnJoystickMoveListener? = null

    fun setOnJoystickMoveListener(listener: OnJoystickMoveListener) {
        this.listener = listener
    }

    private val basePaint = Paint().apply {
        color = Color.parseColor("#2A3A5A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#4A6A9A")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val thumbPaint = Paint().apply {
        color = Color.parseColor("#FFAA44")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val shadowPaint = Paint().apply {
        color = Color.parseColor("#22000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f

    private var normalizedX = 0f
    private var normalizedY = 0f
    private var isTouching = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = w.coerceAtMost(h)
        centerX = (w / 2).toFloat()
        centerY = (h / 2).toFloat()
        baseRadius = (size / 2 * 0.85f).toFloat()
        thumbRadius = baseRadius * 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX + 5, centerY + 5, baseRadius, shadowPaint)
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, borderPaint)

        val maxOffset = baseRadius - thumbRadius
        val offsetX = normalizedX * maxOffset
        val offsetY = normalizedY * maxOffset

        canvas.drawCircle(centerX + offsetX, centerY + offsetY, thumbRadius, thumbPaint)
        val highlightPaint = Paint().apply {
            color = Color.WHITE
            alpha = 60
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX + offsetX - thumbRadius * 0.2f,
            centerY + offsetY - thumbRadius * 0.2f,
            thumbRadius * 0.3f, highlightPaint)
    }

    // 强制归零
    private fun reset() {
        if (normalizedX != 0f || normalizedY != 0f) {
            normalizedX = 0f
            normalizedY = 0f
            listener?.onMove(0f, 0f)
            invalidate()
        }
        isTouching = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                handleMove(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleMove(event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                reset()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleMove(event: MotionEvent) {
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distance = hypot(dx, dy)
        val maxDistance = baseRadius - thumbRadius

        if (distance > maxDistance) {
            normalizedX = (dx / distance) * maxDistance / maxDistance
            normalizedY = (dy / distance) * maxDistance / maxDistance
        } else {
            normalizedX = dx / maxDistance
            normalizedY = dy / maxDistance
        }
        normalizedX = normalizedX.coerceIn(-1f, 1f)
        normalizedY = normalizedY.coerceIn(-1f, 1f)

        val deadZone = 0.05f
        val speed = if (kotlin.math.abs(normalizedY) > deadZone) -normalizedY else 0f
        val turn = if (kotlin.math.abs(normalizedX) > deadZone) normalizedX else 0f

        listener?.onMove(speed, turn)
        invalidate()
    }

    // 当窗口焦点丢失时，强制归零（例如弹出系统对话框）
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && isTouching) {
            reset()
        }
    }

    // 当 View 被移除时，强制归零
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isTouching) {
            reset()
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
    fun resetJoystick() {
        if (normalizedX != 0f || normalizedY != 0f) {
            normalizedX = 0f
            normalizedY = 0f
            listener?.onMove(0f, 0f)
            invalidate()
        }
        // 如果正在触摸，立即终止触摸状态
        if (isTouching) {
            isTouching = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }
    fun isTouching(): Boolean = isTouching
}