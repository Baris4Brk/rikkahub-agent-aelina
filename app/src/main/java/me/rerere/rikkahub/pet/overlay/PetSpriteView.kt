package me.rerere.rikkahub.pet.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import me.rerere.rikkahub.pet.PetBodyRegion
import me.rerere.rikkahub.pet.PetStatusBadge
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.PetActionProfile
import me.rerere.rikkahub.pet.action.PetClipBinding
import me.rerere.rikkahub.pet.action.PetClipLoopMode
import me.rerere.rikkahub.pet.action.ResolvedPetAction
import me.rerere.rikkahub.pet.render.PetFrameClock
import me.rerere.rikkahub.pet.render.PetSpriteAtlas

data class PetDragEvent(
    val deltaXpx: Int,
    val deltaYpx: Int,
    val horizontalSpeedDpPerSecond: Float,
    val verticalSpeedDpPerSecond: Float,
    val finished: Boolean,
)

class PetSpriteView(
    context: Context,
    private val atlas: PetSpriteAtlas,
    private val onInteraction: (String, PetBodyRegion) -> Unit,
    private val onDrag: (PetDragEvent) -> Unit,
    private val headBoundary: Float = 0.34f,
    private val bodyBoundary: Float = 0.76f,
    private val defaultAnimationFps: Int = 6,
) : View(context), Choreographer.FrameCallback {
    private var clock = PetFrameClock(defaultAnimationFps.coerceIn(4, 30))
    private var clip: PetClipBinding = PetActionProfile.standard()
        .bindings
        .getValue(CorePetActions.IDLE)
    private var activeFrameCount = atlas.frameCount(clip)
    private var loopMode = PetClipLoopMode.LOOP
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
    private var lastMotionAtMs = 0L
    private var lastHorizontalSpeedDpPerSecond = 0f
    private var lastVerticalSpeedDpPerSecond = 0f
    private var pathLength = 0f
    private var lastTapAtMs = 0L
    private var pendingSingleTap: Runnable? = null
    private var localFeedbackUntilMs = 0L
    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6F91.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var statusBadge: PetStatusBadge? = null

    /** Renderer-only entrypoint. Business state must go through PetBehaviorOrchestrator. */
    internal fun setResolvedAction(action: ResolvedPetAction) {
        val next = action.clip
        val safeFrameCount = atlas.frameCount(next)
        val requestedFps = action.clip.fps ?: defaultAnimationFps
        val safeFps = requestedFps.coerceIn(4, 30)
        val nextLoopMode = action.clip.loopMode
        if (next == clip &&
            activeFrameCount == safeFrameCount &&
            this.loopMode == nextLoopMode &&
            clock.fps == safeFps
        ) return
        clip = next
        activeFrameCount = safeFrameCount
        this.loopMode = nextLoopMode
        clock = PetFrameClock(safeFps)
        startedAtMs = SystemClock.uptimeMillis()
        frame = 0
        invalidate()
    }

    fun setStatusBadge(badge: PetStatusBadge?) {
        if (statusBadge == badge) return
        statusBadge = badge
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

    /** Local-only feedback used when trusted runtime state forbids model interaction. */
    fun showLocalFeedback() {
        localFeedbackUntilMs = SystemClock.uptimeMillis() + LOCAL_FEEDBACK_MS
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val nextFrame = clock.frameIndex(
            elapsedMs = SystemClock.uptimeMillis() - startedAtMs,
            frameCount = activeFrameCount,
            loop = loopMode == PetClipLoopMode.LOOP,
        )
        if (nextFrame != frame) {
            frame = nextFrame
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        atlas.draw(canvas, clip, frame, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        val remaining = localFeedbackUntilMs - SystemClock.uptimeMillis()
        if (remaining > 0L) {
            feedbackPaint.textSize = minOf(width, height) * 0.28f
            feedbackPaint.alpha = (255f * (remaining.toFloat() / LOCAL_FEEDBACK_MS)).toInt().coerceIn(48, 255)
            canvas.drawText("♥", width * 0.72f, height * 0.28f, feedbackPaint)
            postInvalidateOnAnimation()
        }
        statusBadge?.let { drawStatusBadge(canvas, it) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (!atlas.isOpaqueAt(clip, frame, event.x, event.y, width.toFloat(), height.toFloat())) {
                false
            } else {
                downRawX = event.rawX
                downRawY = event.rawY
                downLocalX = event.x
                downLocalY = event.y
                lastLocalX = event.x
                lastLocalY = event.y
                downAtMs = SystemClock.uptimeMillis()
                lastMotionAtMs = event.eventTime
                lastHorizontalSpeedDpPerSecond = 0f
                lastVerticalSpeedDpPerSecond = 0f
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
                val elapsedMs = (event.eventTime - lastMotionAtMs).coerceAtLeast(1L)
                val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
                lastHorizontalSpeedDpPerSecond = dx / density * 1_000f / elapsedMs
                lastVerticalSpeedDpPerSecond = dy / density * 1_000f / elapsedMs
                onDrag(
                    PetDragEvent(
                        deltaXpx = dx,
                        deltaYpx = dy,
                        horizontalSpeedDpPerSecond = lastHorizontalSpeedDpPerSecond,
                        verticalSpeedDpPerSecond = lastVerticalSpeedDpPerSecond,
                        finished = false,
                    ),
                )
                downRawX = event.rawX
                downRawY = event.rawY
                lastMotionAtMs = event.eventTime
            }
            true
        }
        MotionEvent.ACTION_UP -> {
            if (dragged) {
                onDrag(
                    PetDragEvent(
                        deltaXpx = 0,
                        deltaYpx = 0,
                        horizontalSpeedDpPerSecond = lastHorizontalSpeedDpPerSecond,
                        verticalSpeedDpPerSecond = lastVerticalSpeedDpPerSecond,
                        finished = true,
                    ),
                )
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
            if (dragged) {
                onDrag(
                    PetDragEvent(
                        deltaXpx = 0,
                        deltaYpx = 0,
                        horizontalSpeedDpPerSecond = lastHorizontalSpeedDpPerSecond,
                        verticalSpeedDpPerSecond = lastVerticalSpeedDpPerSecond,
                        finished = true,
                    ),
                )
            }
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

    private fun drawStatusBadge(canvas: Canvas, badge: PetStatusBadge) {
        val (symbol, color) = when (badge) {
            PetStatusBadge.QUESTION -> "?" to 0xFFF4B400.toInt()
            PetStatusBadge.FAILURE -> "!" to 0xFFD94343.toInt()
            PetStatusBadge.SERVICE -> "•" to 0xFF4CAF50.toInt()
        }
        val radius = minOf(width, height) * 0.12f
        val cx = width - radius * 1.15f
        val cy = radius * 1.15f
        badgePaint.color = color
        badgePaint.alpha = 238
        canvas.drawCircle(cx, cy, radius, badgePaint)
        badgePaint.color = 0xFFFFFFFF.toInt()
        badgePaint.textSize = radius * 1.45f
        badgePaint.alpha = 255
        val baseline = cy - (badgePaint.ascent() + badgePaint.descent()) / 2
        canvas.drawText(symbol, cx, baseline, badgePaint)
    }

    override fun onDetachedFromWindow() {
        pauseAnimation()
        pendingSingleTap?.let(::removeCallbacks)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LOCAL_FEEDBACK_MS = 650L
    }
}
