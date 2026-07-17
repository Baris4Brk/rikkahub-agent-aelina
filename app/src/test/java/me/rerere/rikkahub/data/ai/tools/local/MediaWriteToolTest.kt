package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaWriteToolTest {
    private fun execute(tool: Tool, args: String) = runBlocking {
        val part = tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        Json.parseToJsonElement(part.text).jsonObject
    }

    @Test
    fun `media copy validates and forwards a structured import request`() {
        val backend = RecordingMediaWriteBackend()

        val result = execute(
            mediaCopyTool(backend),
            """{"source":"~/photo.jpg","collection":"images","album":"Trips/2026","display_name":"day-1.jpg"}""",
        )

        assertEquals("~/photo.jpg", backend.copyRequest?.source)
        assertEquals(MediaCollection.Images, backend.copyRequest?.collection)
        assertEquals("Trips/2026", backend.copyRequest?.album)
        assertEquals("day-1.jpg", backend.copyRequest?.displayName)
        assertEquals("content://media/new/1", result["uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun `media move rejects traversal before touching MediaStore`() {
        val backend = RecordingMediaWriteBackend()

        val result = execute(
            mediaMoveTool(backend),
            """{"collection":"images","id":42,"album":"../Private"}""",
        )

        assertEquals("INVALID_ALBUM", result["error"]?.jsonPrimitive?.content)
        assertFalse(backend.moveCalled)
    }

    @Test
    fun `media copy cannot import core second user data`() {
        val backend = RecordingMediaWriteBackend()
        val source = "/data/user/0/${BuildConfig.APPLICATION_ID}/databases/rikka_hub"

        val result = execute(
            mediaCopyTool(backend),
            """{"source":"$source","collection":"images"}""",
        )

        assertEquals("SOURCE_UNAVAILABLE", result["error"]?.jsonPrimitive?.content)
        assertEquals(null, backend.copyRequest)
    }

    @Test
    fun `media copy preserves content uri sources`() {
        val backend = RecordingMediaWriteBackend()
        val source = "content://com.example.documents/document/photo"

        execute(
            mediaCopyTool(backend),
            """{"source":"$source","collection":"images"}""",
        )

        assertEquals(source, backend.copyRequest?.source)
    }

    @Test
    fun `media copy cannot import core data through own file provider`() {
        val backend = RecordingMediaWriteBackend()
        val source =
            "content://0@${BuildConfig.APPLICATION_ID}.fileprovider/upload/browser-profile/Cookies"

        val result = execute(
            mediaCopyTool(backend),
            """{"source":"$source","collection":"images"}""",
        )

        assertEquals("SOURCE_UNAVAILABLE", result["error"]?.jsonPrimitive?.content)
        assertEquals(null, backend.copyRequest)
    }

    @Test(expected = CancellationException::class)
    fun `media copy preserves backend cancellation`() {
        execute(
            mediaCopyTool(object : MediaWriteBackend {
                override suspend fun copy(request: MediaCopyRequest): MediaWriteResult =
                    throw CancellationException("cancelled")

                override suspend fun move(request: MediaMoveRequest): MediaWriteResult =
                    error("not used")
            }),
            """{"source":"content://com.example.documents/document/photo","collection":"images"}""",
        )
    }

    @Test
    fun `media write tools require approval`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("media_copy"))
        assertTrue(ToolApprovalDefaults.requiresApproval("media_move"))
    }

    private class RecordingMediaWriteBackend : MediaWriteBackend {
        var copyRequest: MediaCopyRequest? = null
        var moveCalled = false

        override suspend fun copy(request: MediaCopyRequest): MediaWriteResult {
            copyRequest = request
            return MediaWriteResult.Success(
                uri = "content://media/new/1",
                bytesCopied = 128,
                relativePath = "Pictures/Trips/2026/",
            )
        }

        override suspend fun move(request: MediaMoveRequest): MediaWriteResult {
            moveCalled = true
            return MediaWriteResult.Success(
                uri = "content://media/images/42",
                relativePath = "Pictures/${request.album}/",
            )
        }
    }
}
