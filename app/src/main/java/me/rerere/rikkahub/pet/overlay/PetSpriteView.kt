package me.rerere.rikkahub.pet.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import me.rerere.rikkahub.pet.PetAction
import me.rerere.rikkahub.pet.PetBodyRegion
import me.rerere.rikkahub.pet.render.CodexPetAnimation
import me.rerere.rikkahub.pet.render.CodexPetAtlas
import me.rerere.rikkahub.pet.render.PetFrameClock

class PetSpriteView(
    context: Context,
    private val atlas: CodexPetAtlas,
    private val onInteraction: (String, PetBodyRegion) -> Unit,
    private val onDrag: (dx: Int, dy: Int, finished: Boolean) -> Unit,
    private val headBoundary: Float = 0.34f,
    private val bodyBoundary: Float = 0.76f,
    animationFps: Int = 6,
) : View(context), Choreographer.FrameCallback {
    private val clock = PetFrameClock(animationFps.coerceIn(4, 30))
    private var animation = CodexPetAnimation.IDLE
    private var startedAtMs = SystemClock.uptimeMillis()
    private var frame = 0
    private var running = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragged = false
    private var downAtMs = 0L
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var lastLocalX = 0f
    private var lastLocalY = 0f
    private var pathLength = 0f
    private var lastTapAtMs = 0L
    private var pendingSingleTap: Runnable? = null

    fun setAction(action: PetAction) {
        val next = CodexPetAnimation.from(action)
        if (next == animation) return
        animation = next
        startedAtMs = SystemClock.uptimeMillis()
        frame = 0
        invalidate()
    }

    fun resumeAnimation() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pauseAnimation() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val nextFrame = clock.frameIndex(
            elapsedMs = SystemClock.uptimeMillis() - startedAtMs,
            frameCount = atlas.frameCount(animation),
        )
        if (nextFrame != frame) {
            frame = nextFrame
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        atlas.draw(canvas, animation, frame, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (!atlas.isOpaqueAt(animation, frame, event.x, event.y, width.toFloat(), height.toFloat())) {
                false
            } else {
                downRawX = event.rawX
                downRawY = event.rawY
                downLocalX = event.x
                downLocalY = event.y
                lastLocalX = event.x
                lastLocalY = event.y
                downAtMs = SystemClock.uptimeMillis()
                pathLength = 0f
                dragged = false
                true
            }
        }
        MotionEvent.ACTION_MOVE -> {
            val dx = (event.rawX - downRawX).toInt()
            val dy = (event.rawY - downRawY).toInt()
            pathLength += kotlin.math.hypot(event.x - lastLocalX, event.y - lastLocalY)
            lastLocalX = event.x
            lastLocalY = event.y
            val displacement = kotlin.math.hypot(event.x - downLocalX, event.y - downLocalY)
            if (displacement > 24f) dragged = true
            if (dragged) {
                onDrag(dx, dy, false)
                downRawX = event.rawX
                downRawY = event.rawY
            }
            true
        }
        MotionEvent.ACTION_UP -> {
            if (dragged) {
                onDrag(0, 0, true)
            } else {
                val region = regionAt(event.y)
                val now = SystemClock.uptimeMillis()
                when {
                    pathLength > 60f -> onInteraction("pat", region)
                    now - downAtMs >= 600L -> onInteraction("long_press", region)
                    now - lastTapAtMs <= 300L -> {
                        pendingSingleTap?.let(::removeCallbacks)
                        pendingSingleTap = null
                        lastTapAtMs = 0L
                        onInteraction("double_tap", region)
                    }
                    else -> {
                        lastTapAtMs = now
                        Runnable { onInteraction("tap", region) }.also { runnable ->
                            pendingSingleTap = runnable
                            postDelayed(runnable, 300L)
                        }
                    }
                }
            }
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            if (dragged) onDrag(0, 0, true)
            true
        }
        else -> false
    }

    private fun regionAt(y: Float): PetBodyRegion = when {
        height <= 0 -> PetBodyRegion.UNKNOWN
        y < height * headBoundary.coerceIn(0.1f, 0.6f) -> PetBodyRegion.HEAD
        y < height * bodyBoundary.coerceIn(headBoundary + 0.1f, 0.95f) -> PetBodyRegion.BODY
        else -> PetBodyRegion.FEET
    }

    override fun onDetachedFromWindow() {
        pauseAnimation()
        pendingSingleTap?.let(::removeCallbacks)
        super.onDetachedFromWindow()
    }
}
