package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun hasMediaPerm(ctx: Context, vararg perms: String): Boolean =
    perms.all { ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

private fun mediaErr(code: String, msg: String) = listOf(UIMessagePart.Text(buildJsonObject {
    put("error", code); put("message", msg)
}.toString()))

fun mediaListImagesTool(context: Context): Tool = Tool(
    name = "media_list_images",
    description = "List image files from the device's media store. Returns file name, date added, and size.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("limit", buildJsonObject { put("type", "integer"); put("description", "Max results (default 20, max 100)") })
        })
    },
    execute = { args ->
        val ctx = context
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!hasMediaPerm(ctx, perm)) return@Tool mediaErr("NO_PERMISSION", "Media read permission not granted.")
        val limit = (args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val items = buildJsonArray {
            var count = 0
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.TITLE, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "Untitled")
                        put("date_added", cursor.getLong(2))
                        put("size_bytes", cursor.getLong(3))
                    })
                    count++
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("count", items.size); put("images", items) }.toString()))
    }
)

fun mediaListAudioTool(context: Context): Tool = Tool(
    name = "media_list_audio",
    description = "List audio files from the device's media store. Returns file name, artist, album, duration, and size.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("limit", buildJsonObject { put("type", "integer"); put("description", "Max results (default 20, max 100)") })
        })
    },
    execute = { args ->
        val ctx = context
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!hasMediaPerm(ctx, perm)) return@Tool mediaErr("NO_PERMISSION", "Media read permission not granted.")
        val limit = (args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val items = buildJsonArray {
            var count = 0
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE),
                null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "Untitled")
                        put("artist", cursor.getString(2) ?: "")
                        put("album", cursor.getString(3) ?: "")
                        put("duration_ms", cursor.getLong(4))
                        put("size_bytes", cursor.getLong(5))
                    })
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("count", items.size); put("audio", items) }.toString()))
    }
)
