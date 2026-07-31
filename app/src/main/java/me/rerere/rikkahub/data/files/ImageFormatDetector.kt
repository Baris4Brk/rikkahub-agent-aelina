package me.rerere.rikkahub.data.files

import java.io.File
import java.io.FileInputStream

enum class ImageFormat(
    val mimeType: String,
    val defaultExtension: String,
    val extensions: Set<String>,
) {
    PNG("image/png", "png", setOf("png")),
    JPEG("image/jpeg", "jpg", setOf("jpg", "jpeg")),
    WEBP("image/webp", "webp", setOf("webp")),
    GIF("image/gif", "gif", setOf("gif")),
    BMP("image/bmp", "bmp", setOf("bmp")),
    SVG("image/svg+xml", "svg", setOf("svg")),
    HEIC("image/heic", "heic", setOf("heic")),
    HEIF("image/heif", "heif", setOf("heif", "hif")),
    AVIF("image/avif", "avif", setOf("avif")),
    ICO("image/x-icon", "ico", setOf("ico")),
}

data class DetectedImageFormat(
    val format: ImageFormat,
    val originalExtensionMatched: Boolean,
    val declaredMimeMatched: Boolean,
) {
    val mimeType: String get() = format.mimeType
    val extension: String get() = format.defaultExtension

    fun normalizedDisplayName(originalName: String?): String {
        val clean = originalName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace('\u0000', '_')
            ?.ifBlank { null }
            ?: "image.${format.defaultExtension}"
        val oldExtension = clean.substringAfterLast('.', "").lowercase()
        if (oldExtension in format.extensions) return clean
        val stem = if (oldExtension.isNotEmpty()) clean.substringBeforeLast('.') else clean
        return "${stem.ifBlank { "image" }}.${format.defaultExtension}"
    }
}

/** Header-first image detection shared by managed files and Workspace attachments. */
object ImageFormatDetector {
    private const val MAX_HEADER_BYTES = 4096

    private val heicBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")
    private val heifBrands = setOf("mif1", "msf1")
    private val avifBrands = setOf("avif", "avis")

    val knownExtensions: Set<String> = ImageFormat.entries.flatMapTo(mutableSetOf()) { it.extensions }

    fun detect(
        bytes: ByteArray,
        fileName: String? = null,
        declaredMimeType: String? = null,
    ): DetectedImageFormat? {
        val sample = if (bytes.size <= MAX_HEADER_BYTES) bytes else bytes.copyOf(MAX_HEADER_BYTES)
        val format = detectHeader(sample) ?: detectSafeSvg(sample) ?: return null
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return DetectedImageFormat(
            format = format,
            originalExtensionMatched = extension in format.extensions,
            declaredMimeMatched = declaredMimeType
                ?.substringBefore(';')
                ?.trim()
                ?.equals(format.mimeType, ignoreCase = true) == true,
        )
    }

    fun detect(
        file: File,
        fileName: String? = file.name,
        declaredMimeType: String? = null,
    ): DetectedImageFormat? {
        if (!file.isFile) return null
        val sample = ByteArray(MAX_HEADER_BYTES)
        val count = runCatching {
            FileInputStream(file).use { it.read(sample) }
        }.getOrDefault(-1)
        if (count <= 0) return null
        return detect(sample.copyOf(count), fileName, declaredMimeType)
    }

    private fun detectHeader(bytes: ByteArray): ImageFormat? = when {
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> ImageFormat.PNG
        bytes.startsWith(0xff, 0xd8, 0xff) -> ImageFormat.JPEG
        bytes.ascii(0, 6) in setOf("GIF87a", "GIF89a") -> ImageFormat.GIF
        bytes.startsWith(0x42, 0x4d) -> ImageFormat.BMP
        bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> ImageFormat.WEBP
        bytes.startsWith(0x00, 0x00, 0x01, 0x00) -> ImageFormat.ICO
        bytes.ascii(4, 4) == "ftyp" -> detectIsoBmff(bytes)
        else -> null
    }

    private fun detectIsoBmff(bytes: ByteArray): ImageFormat? {
        if (bytes.size < 12) return null
        val brands = buildSet {
            add(bytes.ascii(8, 4))
            var offset = 16 // bytes 12..15 are the minor version, not a compatible brand
            while (offset + 4 <= bytes.size.coerceAtMost(80)) {
                add(bytes.ascii(offset, 4))
                offset += 4
            }
        }
        return when {
            brands.any { it in avifBrands } -> ImageFormat.AVIF
            brands.any { it in heicBrands } -> ImageFormat.HEIC
            brands.any { it in heifBrands } -> ImageFormat.HEIF
            else -> null
        }
    }

    private fun detectSafeSvg(bytes: ByteArray): ImageFormat? {
        val source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (!Regex("<svg\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)) return null
        val unsafe = listOf(
            Regex("<script\\b", RegexOption.IGNORE_CASE),
            Regex("<!doctype\\b", RegexOption.IGNORE_CASE),
            Regex("<!entity\\b", RegexOption.IGNORE_CASE),
            Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE),
            Regex("(?:href|xlink:href)\\s*=\\s*['\"]\\s*(?:https?:|//|file:|content:|data:)", RegexOption.IGNORE_CASE),
            Regex("url\\(\\s*['\"]?(?!#)", RegexOption.IGNORE_CASE),
        ).any { it.containsMatchIn(source) }
        return if (unsafe) null else ImageFormat.SVG
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { (this[it].toInt() and 0xff) == expected[it] }

    private fun ByteArray.ascii(offset: Int, count: Int): String {
        if (offset < 0 || count < 0 || offset + count > size) return ""
        return copyOfRange(offset, offset + count).toString(Charsets.US_ASCII)
    }
}
