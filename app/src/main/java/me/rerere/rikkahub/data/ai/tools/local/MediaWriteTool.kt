package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.IOException
import java.net.URLConnection

internal enum class MediaCollection { Images, Video, Audio }

internal data class MediaCopyRequest(
    val source: String,
    val collection: MediaCollection,
    val album: String,
    val displayName: String?,
    val mimeType: String?,
)

internal data class MediaMoveRequest(
    val collection: MediaCollection,
    val id: Long,
    val album: String,
    val displayName: String?,
)

internal sealed interface MediaWriteResult {
    data class Success(
        val uri: String,
        val bytesCopied: Long? = null,
        val relativePath: String,
    ) : MediaWriteResult
    data class Error(val code: String, val message: String) : MediaWriteResult
}

/** Seam around MediaStore; Tool tests use a fake adapter and production uses ContentResolver. */
internal interface MediaWriteBackend {
    suspend fun copy(request: MediaCopyRequest): MediaWriteResult
    suspend fun move(request: MediaMoveRequest): MediaWriteResult
}

private const val MAX_MEDIA_COPY_BYTES = 1_073_741_824L

private class AndroidMediaWriteBackend(private val context: Context) : MediaWriteBackend {
    override suspend fun copy(request: MediaCopyRequest): MediaWriteResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext MediaWriteResult.Error(
                "UNSUPPORTED_ANDROID_VERSION",
                "MediaStore folder writes require Android 10 or newer.",
            )
        }
        val source = openSource(request.source)
            ?: return@withContext MediaWriteResult.Error(
                "SOURCE_UNAVAILABLE",
                "The source is missing, unsafe, or not covered by a persisted SAF grant.",
            )
        val displayName = request.displayName ?: source.second
        if (displayName == null) {
            runCatching { source.third.close() }
            return@withContext MediaWriteResult.Error(
                "MISSING_DISPLAY_NAME",
                "display_name is required when it cannot be derived from source.",
            )
        }
        val mimeType = request.mimeType ?: source.first ?: URLConnection.guessContentTypeFromName(displayName)
        if (mimeType == null) {
            runCatching { source.third.close() }
            return@withContext MediaWriteResult.Error(
                "UNKNOWN_MIME_TYPE",
                "Provide mime_type because Android could not infer it from the source.",
            )
        }
        if (!mimeMatches(request.collection, mimeType)) {
            runCatching { source.third.close() }
            return@withContext MediaWriteResult.Error(
                "MIME_COLLECTION_MISMATCH",
                "$mimeType does not belong in ${request.collection.name.lowercase()}.",
            )
        }

        val relativePath = relativePath(request.collection, request.album)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(collectionUri(request.collection), values)
        } catch (cancelled: CancellationException) {
            runCatching { source.third.close() }
            throw cancelled
        } catch (error: Exception) {
            runCatching { source.third.close() }
            return@withContext MediaWriteResult.Error(
                "INSERT_FAILED",
                error.message ?: "MediaStore refused the new item.",
            )
        }
        if (uri == null) {
            runCatching { source.third.close() }
            return@withContext MediaWriteResult.Error("INSERT_FAILED", "MediaStore refused the new item.")
        }
        try {
            val copied = source.third.use { input ->
                resolver.openOutputStream(uri, "w")?.use { output ->
                    copyCapped(input, output)
                } ?: throw IOException("MediaStore output stream unavailable")
            }
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (published != 1) {
                throw IOException("MediaStore did not publish the pending item")
            }
            MediaWriteResult.Success(uri.toString(), copied, relativePath)
        } catch (tooLarge: MediaCopyTooLargeException) {
            runCatching { resolver.delete(uri, null, null) }
            MediaWriteResult.Error("SOURCE_TOO_LARGE", "Media copy is limited to 1 GiB per call.")
        } catch (cancelled: CancellationException) {
            runCatching { resolver.delete(uri, null, null) }
            throw cancelled
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            MediaWriteResult.Error("COPY_FAILED", error.message ?: "Media copy failed.")
        }
    }

    override suspend fun move(request: MediaMoveRequest): MediaWriteResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext MediaWriteResult.Error(
                "UNSUPPORTED_ANDROID_VERSION",
                "MediaStore folder organization requires Android 10 or newer.",
            )
        }
        val uri = ContentUris.withAppendedId(collectionUri(request.collection), request.id)
        val relativePath = relativePath(request.collection, request.album)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            request.displayName?.let { put(MediaStore.MediaColumns.DISPLAY_NAME, it) }
        }
        try {
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated == 0) {
                MediaWriteResult.Error("NOT_FOUND", "No matching media item was found or it is not writable.")
            } else {
                MediaWriteResult.Success(uri.toString(), relativePath = relativePath)
            }
        } catch (security: SecurityException) {
            if (security.javaClass.name == "android.app.RecoverableSecurityException") {
                MediaWriteResult.Error(
                    "USER_CONFIRMATION_REQUIRED",
                    "Android requires a local confirmation before this item can be changed.",
                )
            } else {
                MediaWriteResult.Error("NO_PERMISSION", security.message ?: "Media item is not writable.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MediaWriteResult.Error("MOVE_FAILED", error.message ?: "Media move failed.")
        }
    }

    private fun openSource(raw: String): Triple<String?, String?, java.io.InputStream>? {
        if (raw.startsWith("content://")) {
            if (ContentUriSafetyGuard.checkSensitiveRead(raw) != null) return null
            val uri = Uri.parse(raw)
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val stream = runCatching { ContentUriResolver.openInput(context, raw) }.getOrNull() ?: return null
            return Triple(
                mimeType,
                uri.lastPathSegment?.substringAfterLast('/'),
                stream,
            )
        }
        val expanded = AgentWorkspace.expand(raw).removePrefix("file://")
        if (PathSafetyGuard.checkSensitiveRead(expanded) != null) return null
        val file = File(expanded)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            Triple(URLConnection.guessContentTypeFromName(file.name), file.name, file.inputStream())
        }.getOrNull()
    }

    private fun collectionUri(collection: MediaCollection): Uri = when (collection) {
        MediaCollection.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaCollection.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaCollection.Audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun relativePath(collection: MediaCollection, album: String): String {
        val root = when (collection) {
            MediaCollection.Images -> "Pictures"
            MediaCollection.Video -> "Movies"
            MediaCollection.Audio -> "Music"
        }
        return if (album.isBlank()) "$root/" else "$root/$album/"
    }

    private fun mimeMatches(collection: MediaCollection, mimeType: String): Boolean = when (collection) {
        MediaCollection.Images -> mimeType.startsWith("image/")
        MediaCollection.Video -> mimeType.startsWith("video/")
        MediaCollection.Audio -> mimeType.startsWith("audio/")
    }

    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > MAX_MEDIA_COPY_BYTES) throw MediaCopyTooLargeException()
            output.write(buffer, 0, count)
            total += count
        }
        return total
    }
}

private class MediaCopyTooLargeException : IOException()

private fun parseCollection(raw: String?): MediaCollection? = when (raw?.lowercase()) {
    "image", "images" -> MediaCollection.Images
    "video", "videos" -> MediaCollection.Video
    "audio" -> MediaCollection.Audio
    else -> null
}

private fun normalizeAlbum(raw: String?): String? {
    if (raw.isNullOrBlank()) return ""
    if (raw.startsWith('/') || raw.startsWith('\\') || raw.any { it.code < 32 }) return null
    val parts = raw.replace('\\', '/').split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." || ':' in it }) return null
    return parts.joinToString("/")
}

private fun normalizeDisplayName(raw: String?): String? {
    if (raw == null) return null
    val name = raw.trim()
    return name.takeIf {
        it.isNotEmpty() && it.length <= 255 && '/' !in it && '\\' !in it && it != "." && it != ".."
    }
}

private fun mediaWriteParts(result: MediaWriteResult): List<UIMessagePart> =
    listOf(UIMessagePart.Text(when (result) {
        is MediaWriteResult.Error -> buildJsonObject {
            put("error", result.code)
            put("message", result.message)
        }
        is MediaWriteResult.Success -> buildJsonObject {
            put("success", true)
            put("uri", result.uri)
            result.bytesCopied?.let { put("bytes_copied", it) }
            put("relative_path", result.relativePath)
        }
    }.toString()))

private fun mediaWriteError(code: String, message: String) =
    mediaWriteParts(MediaWriteResult.Error(code, message))

internal fun mediaCopyTool(backend: MediaWriteBackend): Tool = Tool(
    name = "media_copy",
    description = "Copy a safe local file or granted content URI into Android MediaStore.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject { put("type", "string") })
                put("collection", buildJsonObject {
                    put("type", "string")
                    put("description", "images, video, or audio")
                })
                put("album", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional relative album path below Pictures, Movies, or Music.")
                })
                put("display_name", buildJsonObject { put("type", "string") })
                put("mime_type", buildJsonObject { put("type", "string") })
            },
            required = listOf("source", "collection"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val source = obj["source"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool mediaWriteError("MISSING_SOURCE", "source is required.")
        val collection = parseCollection(obj["collection"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool mediaWriteError("INVALID_COLLECTION", "collection must be images, video, or audio.")
        val rawAlbum = obj["album"]?.jsonPrimitive?.contentOrNull
        val album = normalizeAlbum(rawAlbum)
            ?: return@Tool mediaWriteError("INVALID_ALBUM", "album must be a safe relative path without '..'.")
        val rawDisplayName = obj["display_name"]?.jsonPrimitive?.contentOrNull
        val displayName = normalizeDisplayName(rawDisplayName)
        if (rawDisplayName != null && displayName == null) {
            return@Tool mediaWriteError("INVALID_DISPLAY_NAME", "display_name must be a filename, not a path.")
        }
        val sourceViolation = if (source.startsWith("content://")) {
            ContentUriSafetyGuard.checkSensitiveRead(source)
        } else {
            PathSafetyGuard.checkSensitiveRead(source.removePrefix("file://"))
        }
        if (sourceViolation != null) {
            return@Tool mediaWriteError(
                "SOURCE_UNAVAILABLE",
                "The source is missing, unsafe, or not covered by a persisted SAF grant.",
            )
        }
        mediaWriteParts(
            backend.copy(
                MediaCopyRequest(
                    source = source,
                    collection = collection,
                    album = album,
                    displayName = displayName,
                    mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull,
                )
            )
        )
    },
)

fun mediaCopyTool(context: Context): Tool = mediaCopyTool(AndroidMediaWriteBackend(context))

internal fun mediaMoveTool(backend: MediaWriteBackend): Tool = Tool(
    name = "media_move",
    description = "Move a MediaStore item into a safe relative album and optionally rename it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("collection", buildJsonObject {
                    put("type", "string")
                    put("description", "images, video, or audio")
                })
                put("id", buildJsonObject { put("type", "integer") })
                put("album", buildJsonObject { put("type", "string") })
                put("display_name", buildJsonObject { put("type", "string") })
            },
            required = listOf("collection", "id", "album"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val collection = parseCollection(obj["collection"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool mediaWriteError("INVALID_COLLECTION", "collection must be images, video, or audio.")
        val id = obj["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            ?: return@Tool mediaWriteError("INVALID_ID", "id must be a positive MediaStore ID.")
        val album = normalizeAlbum(obj["album"]?.jsonPrimitive?.contentOrNull)
            ?: return@Tool mediaWriteError("INVALID_ALBUM", "album must be a safe relative path without '..'.")
        val rawDisplayName = obj["display_name"]?.jsonPrimitive?.contentOrNull
        val displayName = normalizeDisplayName(rawDisplayName)
        if (rawDisplayName != null && displayName == null) {
            return@Tool mediaWriteError("INVALID_DISPLAY_NAME", "display_name must be a filename, not a path.")
        }
        mediaWriteParts(backend.move(MediaMoveRequest(collection, id, album, displayName)))
    },
)

fun mediaMoveTool(context: Context): Tool = mediaMoveTool(AndroidMediaWriteBackend(context))
