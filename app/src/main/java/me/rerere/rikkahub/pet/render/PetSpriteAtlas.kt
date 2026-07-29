package me.rerere.rikkahub.pet.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.Closeable
import java.io.File
import me.rerere.rikkahub.pet.action.PetClipBinding
import me.rerere.rikkahub.pet.action.PetSheetBinding

/** Safe static-sprite rendering contract shared by Codex and declarative extra sheets. */
interface PetSpriteAtlas : Closeable {
    fun frameCount(clip: PetClipBinding): Int
    fun draw(canvas: Canvas, clip: PetClipBinding, frame: Int, destination: RectF)
    fun isOpaqueAt(
        clip: PetClipBinding,
        frame: Int,
        localX: Float,
        localY: Float,
        renderedWidth: Float,
        renderedHeight: Float,
        alphaThreshold: Int = 16,
    ): Boolean
}

/** A bounded declarative grid. It deliberately has no script, URI, class, or network hooks. */
class StaticPetSpriteAtlas private constructor(
    private val bitmap: Bitmap,
    private val sheet: PetSheetBinding,
) : PetSpriteAtlas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun frameCount(clip: PetClipBinding): Int = clip.frames.coerceIn(1, sheet.columns)

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
        val x = source.left + (mappedX / renderedWidth * source.width()).toInt()
            .coerceIn(0, source.width() - 1)
        val y = source.top + (localY / renderedHeight * source.height()).toInt()
            .coerceIn(0, source.height() - 1)
        return bitmap.getPixel(x, y).ushr(24) >= alphaThreshold
    }

    private fun sourceRect(clip: PetClipBinding, frame: Int): Rect {
        require(clip.sheetId == sheet.sheetId) { "pet_sheet_mismatch" }
        require(clip.row in 0 until sheet.rows) { "pet_clip_row_out_of_range" }
        val column = frame.mod(frameCount(clip))
        return Rect(
            column * sheet.frameWidth,
            clip.row * sheet.frameHeight,
            (column + 1) * sheet.frameWidth,
            (clip.row + 1) * sheet.frameHeight,
        )
    }

    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        fun decode(file: File, sheet: PetSheetBinding): StaticPetSpriteAtlas {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: error("pet_extra_sheet_decode_failed")
            if (bitmap.width != sheet.frameWidth * sheet.columns ||
                bitmap.height != sheet.frameHeight * sheet.rows
            ) {
                bitmap.recycle()
                error("pet_extra_sheet_dimensions_invalid")
            }
            return StaticPetSpriteAtlas(bitmap, sheet)
        }
    }
}

/** Routes a validated clip to its decoded static image sheet. */
class CompositePetSpriteAtlas(
    private val sheets: Map<String, PetSpriteAtlas>,
) : PetSpriteAtlas {
    override fun frameCount(clip: PetClipBinding): Int = sheet(clip).frameCount(clip)

    override fun draw(canvas: Canvas, clip: PetClipBinding, frame: Int, destination: RectF) {
        sheet(clip).draw(canvas, clip, frame, destination)
    }

    override fun isOpaqueAt(
        clip: PetClipBinding,
        frame: Int,
        localX: Float,
        localY: Float,
        renderedWidth: Float,
        renderedHeight: Float,
        alphaThreshold: Int,
    ): Boolean = sheet(clip).isOpaqueAt(
        clip,
        frame,
        localX,
        localY,
        renderedWidth,
        renderedHeight,
        alphaThreshold,
    )

    override fun close() {
        sheets.values.toSet().forEach(Closeable::close)
    }

    private fun sheet(clip: PetClipBinding): PetSpriteAtlas =
        sheets[clip.sheetId] ?: error("pet_profile_sheet_unavailable")
}
