package me.rerere.rikkahub.pet.assets

import android.graphics.BitmapFactory
import java.io.File

object AndroidPetImageProbe : PetImageProbe {
    override fun inspect(file: File): PetImageInfo? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            PetImageInfo(options.outWidth, options.outHeight)
        } else {
            null
        }
    }
}
