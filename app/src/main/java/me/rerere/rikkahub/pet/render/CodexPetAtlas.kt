package me.rerere.rikkahub.pet.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import java.io.Closeable
import java.io.File
import me.rerere.rikkahub.pet.CodexPetVersion
import me.rerere.rikkahub.pet.PetAction
import me.rerere.rikkahub.pet.action.PetClipBinding
import me.rerere.rikkahub.pet.assets.CODEX_ATLAS_COLUMNS
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_HEIGHT
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_WIDTH
import me.rerere.rikkahub.pet.assets.expectedRows

enum class AtlasFramePolicy { STRICT_CODEX, DETECT_NON_EMPTY }

enum class CodexPetAnimation(
    val action: PetAction,
    val row: Int,
    val defaultFrameCount: Int,
) {
    IDLE(PetAction.IDLE, 0, 6),
    RUNNING_RIGHT(PetAction.RUNNING_RIGHT, 1, 8),
    RUNNING_LEFT(PetAction.RUNNING_LEFT, 2, 8),
    WAVING(PetAction.WAVING, 3, 4),
    JUMPING(PetAction.JUMPING, 4, 5),
    FAILED(PetAction.FAILED, 5, 8),
    WAITING(PetAction.WAITING, 6, 6),
    RUNNING(PetAction.RUNNING, 7, 6),
    REVIEW(PetAction.REVIEW, 8, 6),
    ;

    companion object {
        fun from(action: PetAction): CodexPetAnimation = entries.firstOrNull { it.action == action } ?: IDLE
    }
}

/** One decoded atlas shared by all frames; drawing always uses a source rectangle. */
class CodexPetAtlas private constructor(
    private val bitmap: Bitmap,
    val version: CodexPetVersion,
    policy: AtlasFramePolicy,
) : PetSpriteAtlas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frameCounts = CodexPetAnimation.entries.associateWith { animation ->
        when (policy) {
            AtlasFramePolicy.STRICT_CODEX -> animation.defaultFrameCount
            AtlasFramePolicy.DETECT_NON_EMPTY -> detectContinuousFrames(animation)
                .takeIf { it > 0 }
                ?: animation.defaultFrameCount
        }
    }

    fun frameCount(animation: CodexPetAnimation): Int = checkNotNull(frameCounts[animation])

    override fun frameCount(clip: PetClipBinding): Int {
        require(clip.sheetId == PetClipBinding.BASE_SHEET_ID) { "pet_sheet_mismatch" }
        require(clip.row in 0 until version.expectedRows()) { "pet_clip_row_out_of_range" }
        val continuous = CodexPetAnimation.entries.firstOrNull { it.row == clip.row }
            ?.let(::frameCount)
            ?: detectContinuousFramesAtRow(clip.row).takeIf { it > 0 }
            ?: clip.frames
        return clip.frames.coerceIn(1, continuous.coerceIn(1, CODEX_ATLAS_COLUMNS))
    }

    fun sourceRect(animation: CodexPetAnimation, frame: Int): Rect {
        val column = frame.mod(frameCount(animation))
        return Rect(
            column * CODEX_FRAME_WIDTH,
            animation.row * CODEX_FRAME_HEIGHT,
            (column + 1) * CODEX_FRAME_WIDTH,
            (animation.row + 1) * CODEX_FRAME_HEIGHT,
        )
    }

    private fun sourceRect(clip: PetClipBinding, frame: Int): Rect {
        val column = frame.mod(frameCount(clip))
        return Rect(
            column * CODEX_FRAME_WIDTH,
            clip.row * CODEX_FRAME_HEIGHT,
            (column + 1) * CODEX_FRAME_WIDTH,
            (clip.row + 1) * CODEX_FRAME_HEIGHT,
        )
    }

    fun draw(canvas: Canvas, animation: CodexPetAnimation, frame: Int, destination: RectF) {
        canvas.drawBitmap(bitmap, sourceRect(animation, frame), destination, paint)
    }

    override fun draw(canvas: Canvas, clip: PetClipBinding, frame: Int, destination: RectF) {
        if (clip.mirrorX) {
            canvas.save()
            canvas.scale(-1f, 1f, destination.centerX(), destination.centerY())
            canvas.drawBitmap(bitmap, sourceRect(clip, frame), destination, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, sourceRect(clip, frame), destination, paint)
        }
    }

    /** Hit testing uses the currently displayed frame's alpha; transparent pixels pass through. */
    fun isOpaqueAt(
        animation: CodexPetAnimation,
        frame: Int,
        localX: Float,
        localY: Float,
        renderedWidth: Float,
        renderedHeight: Float,
        alphaThreshold: Int = 16,
    ): Boolean {
        if (renderedWidth <= 0f || renderedHeight <= 0f ||
            localX !in 0f..<renderedWidth || localY !in 0f..<renderedHeight
        ) return false
        val source = sourceRect(animation, frame)
        val x = source.left + (localX / renderedWidth * source.width()).toInt().coerceIn(0, source.width() - 1)
        val y = source.top + (localY / renderedHeight * source.height()).toInt().coerceIn(0, source.height() - 1)
        return bitmap.getPixel(x, y).ushr(24) >= alphaThreshold
    }

    override fun isOpaqueAt(
        clip: PetClipBinding,
        frame: Int,
        localX: Float,
        localY: Float,
        renderedWidth: Float,
        renderedHeight: Float,
        alphaThreshold: Int,
    ): Boolean {
        if (renderedWidth <= 0f || renderedHeight <= 0f ||
            localX !in 0f..<renderedWidth || localY !in 0f..<renderedHeight
        ) return false
        val source = sourceRect(clip, frame)
        val mappedX = if (clip.mirrorX) renderedWidth - localX else localX
        val x = source.left + (mappedX / renderedWidth * source.width()).toInt().coerceIn(0, source.width() - 1)
        val y = source.top + (localY / renderedHeight * source.height()).toInt().coerceIn(0, source.height() - 1)
        return bitmap.getPixel(x, y).ushr(24) >= alphaThreshold
    }

    fun opaqueRegion(
        animation: CodexPetAnimation,
        frame: Int,
        renderedWidth: Int,
        renderedHeight: Int,
        sampleStep: Int = 4,
    ): Region {
        val result = Region()
        if (renderedWidth <= 0 || renderedHeight <= 0) return result
        val source = sourceRect(animation, frame)
        var sourceY = 0
        while (sourceY < source.height()) {
            var sourceX = 0
            while (sourceX < source.width()) {
                if (bitmap.getPixel(source.left + sourceX, source.top + sourceY).ushr(24) >= 16) {
                    val left = sourceX * renderedWidth / source.width()
                    val top = sourceY * renderedHeight / source.height()
                    val right = ((sourceX + sampleStep) * renderedWidth / source.width()).coerceAtMost(renderedWidth)
                    val bottom = ((sourceY + sampleStep) * renderedHeight / source.height()).coerceAtMost(renderedHeight)
                    result.op(left, top, right, bottom, Region.Op.UNION)
                }
                sourceX += sampleStep
            }
            sourceY += sampleStep
        }
        return result
    }

    private fun detectContinuousFrames(animation: CodexPetAnimation): Int {
        return detectContinuousFramesAtRow(animation.row)
    }

    private fun detectContinuousFramesAtRow(row: Int): Int {
        var count = 0
        for (column in 0 until CODEX_ATLAS_COLUMNS) {
            if (!hasVisiblePixel(column, row)) break
            count += 1
        }
        return count
    }

    private fun hasVisiblePixel(column: Int, row: Int): Boolean {
        val left = column * CODEX_FRAME_WIDTH
        val top = row * CODEX_FRAME_HEIGHT
        val pixels = IntArray(CODEX_FRAME_WIDTH * CODEX_FRAME_HEIGHT)
        bitmap.getPixels(pixels, 0, CODEX_FRAME_WIDTH, left, top, CODEX_FRAME_WIDTH, CODEX_FRAME_HEIGHT)
        return pixels.any { it.ushr(24) >= 8 }
    }

    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        fun decode(
            file: File,
            version: CodexPetVersion,
            policy: AtlasFramePolicy = AtlasFramePolicy.DETECT_NON_EMPTY,
        ): CodexPetAtlas {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: error("pet_spritesheet_decode_failed")
            val expectedWidth = CODEX_FRAME_WIDTH * CODEX_ATLAS_COLUMNS
            val expectedHeight = CODEX_FRAME_HEIGHT * version.expectedRows()
            if (bitmap.width != expectedWidth || bitmap.height != expectedHeight) {
                bitmap.recycle()
                error("pet_spritesheet_dimensions_invalid")
            }
            return CodexPetAtlas(bitmap, version, policy)
        }
    }
}

class PetFrameClock(
    val fps: Int = 6,
) {
    init {
        require(fps in 4..30)
    }

    fun frameIndex(elapsedMs: Long, frameCount: Int, loop: Boolean = true): Int {
        if (frameCount <= 1) return 0
        val frameDuration = 1_000L / fps
        val raw = (elapsedMs.coerceAtLeast(0) / frameDuration).toInt()
        return if (loop) raw % frameCount else raw.coerceAtMost(frameCount - 1)
    }
}
