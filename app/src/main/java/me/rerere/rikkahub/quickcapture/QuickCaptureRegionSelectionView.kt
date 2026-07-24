package me.rerere.rikkahub.quickcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Transparent overlay that only records a user-selected rectangle; it never stores screenshots. */
internal class QuickCaptureRegionSelectionView(context: Context) : View(context) {
    private val dimPaint = Paint().apply { color = 0x66000000 }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var selecting = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                selecting = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    fun selection(): RectF? {
        if (!selecting) return null
        val rect = RectF(
            min(startX, endX),
            min(startY, endY),
            max(startX, endX),
            max(startY, endY),
        )
        return rect.takeIf { it.width() >= MIN_SELECTION_PX && it.height() >= MIN_SELECTION_PX }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        selection()?.let { canvas.drawRect(it, borderPaint) }
    }

    private companion object {
        const val MIN_SELECTION_PX = 12f
    }
}
