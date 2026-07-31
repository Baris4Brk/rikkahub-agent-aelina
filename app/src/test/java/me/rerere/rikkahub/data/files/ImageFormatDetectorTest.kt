package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatDetectorTest {
    @Test
    fun `detects raster magic even when the extension is wrong`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )

        val detected = requireNotNull(ImageFormatDetector.detect(png, "photo.txt", "text/plain"))

        assertEquals(ImageFormat.PNG, detected.format)
        assertFalse(detected.originalExtensionMatched)
        assertEquals("photo.png", detected.normalizedDisplayName("photo.txt"))
    }

    @Test
    fun `distinguishes HEIC HEIF and AVIF brands`() {
        assertEquals(ImageFormat.HEIC, detectIso("heic", "mif1").format)
        assertEquals(ImageFormat.HEIF, detectIso("mif1", "msf1").format)
        assertEquals(ImageFormat.AVIF, detectIso("avif", "mif1").format)
    }

    @Test
    fun `ICO requires its real magic`() {
        assertEquals(
            ImageFormat.ICO,
            requireNotNull(ImageFormatDetector.detect(byteArrayOf(0, 0, 1, 0, 1, 0), "icon.ico")).format,
        )
        assertNull(ImageFormatDetector.detect("not an icon".toByteArray(), "icon.ico", "image/x-icon"))
    }

    @Test
    fun `safe SVG is accepted while active content is rejected`() {
        val safe = "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0\"/></svg>"
        val script = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
        val external = "<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"https://example.test/a.png\"/></svg>"

        assertEquals(ImageFormat.SVG, requireNotNull(ImageFormatDetector.detect(safe.toByteArray())).format)
        assertNull(ImageFormatDetector.detect(script.toByteArray(), "bad.svg"))
        assertNull(ImageFormatDetector.detect(external.toByteArray(), "bad.svg"))
    }

    @Test
    fun `known extension inventory includes modern formats`() {
        assertTrue(ImageFormatDetector.knownExtensions.containsAll(setOf("heic", "heif", "avif", "ico")))
    }

    private fun detectIso(major: String, compatible: String): DetectedImageFormat {
        val bytes = ByteArray(24)
        bytes[3] = 24
        "ftyp".toByteArray().copyInto(bytes, 4)
        major.toByteArray().copyInto(bytes, 8)
        compatible.toByteArray().copyInto(bytes, 16)
        return requireNotNull(ImageFormatDetector.detect(bytes, "image.bin"))
    }
}
