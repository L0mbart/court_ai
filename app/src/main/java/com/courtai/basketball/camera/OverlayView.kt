package com.courtai.basketball.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.courtai.basketball.tracking.BallPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#FFB020")
    }
    private val rimFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33FFB020")
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FF6A00")
    }
    private val ballFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#55FF6A00")
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCF7F1E8")
        textSize = 36f
    }

    /** Normalized rim box in view coordinates (0..1). */
    var rim = RectF(0.35f, 0.18f, 0.65f, 0.30f)
        set(value) {
            field = value
            invalidate()
        }

    var ball: BallPoint? = null
        set(value) {
            field = value
            invalidate()
        }

    var showGuide: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showRim: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class DragMode { NONE, MOVE, RESIZE_BR }

    fun rimNormalized(): RectF = RectF(rim)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (showRim) {
            val rect = RectF(rim.left * w, rim.top * h, rim.right * w, rim.bottom * h)
            canvas.drawRect(rect, rimFill)
            canvas.drawRect(rect, rimPaint)
            canvas.drawCircle(rect.right, rect.bottom, 18f, ballPaint)
        }

        ball?.let { b ->
            val cx = b.x * w
            val cy = b.y * h
            val r = max(18f, b.radius * min(w, h))
            canvas.drawCircle(cx, cy, r, ballFill)
            canvas.drawCircle(cx, cy, r, ballPaint)
        }

        if (showGuide && showRim) {
            canvas.drawText("Drag rim box onto hoop", 40f, h * 0.42f, guidePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!showRim) return false
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val x = event.x
        val y = event.y
        val rect = RectF(rim.left * w, rim.top * h, rim.right * w, rim.bottom * h)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                val nearBr = abs(x - rect.right) < 56 && abs(y - rect.bottom) < 56
                dragMode = when {
                    nearBr -> DragMode.RESIZE_BR
                    rect.contains(x, y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = (x - lastX) / w
                val dy = (y - lastY) / h
                lastX = x
                lastY = y
                val next = RectF(rim)
                when (dragMode) {
                    DragMode.MOVE -> {
                        val widthN = next.width()
                        val heightN = next.height()
                        next.offset(dx, dy)
                        if (next.left < 0f) next.offset(-next.left, 0f)
                        if (next.top < 0f) next.offset(0f, -next.top)
                        if (next.right > 1f) next.offset(1f - next.right, 0f)
                        if (next.bottom > 1f) next.offset(0f, 1f - next.bottom)
                        // preserve size if clamped oddly
                        if (next.width() < widthN * 0.9f || next.height() < heightN * 0.9f) {
                            next.set(
                                next.left.coerceIn(0f, 1f - widthN),
                                next.top.coerceIn(0f, 1f - heightN),
                                0f,
                                0f
                            )
                            next.right = next.left + widthN
                            next.bottom = next.top + heightN
                        }
                    }
                    DragMode.RESIZE_BR -> {
                        next.right = (next.right + dx).coerceIn(next.left + 0.08f, 1f)
                        next.bottom = (next.bottom + dy).coerceIn(next.top + 0.05f, 1f)
                    }
                    else -> Unit
                }
                rim = next
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
