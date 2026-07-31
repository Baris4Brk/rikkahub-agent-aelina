package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.java.KoinJavaComponent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

fun exportConversationTool(currentConversationId: String?): Tool = Tool(
    name = "export_conversation",
    description = "Export the current conversation as a Markdown file.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = { _ ->
        val conversationIdStr = currentConversationId
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "CONTEXT_REQUIRED")
                put("message", "The current conversation is unavailable for export.")
            }.toString()))

        val conversationId = runCatching { Uuid.parse(conversationIdStr) }.getOrNull()
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_CONTEXT")
                put("message", "The current conversation identifier is invalid.")
            }.toString()))

        val repo: ConversationRepository = KoinJavaComponent.get(ConversationRepository::class.java)
        val ctx: Context = KoinJavaComponent.get(Context::class.java)

        runBlocking {
            when (val result = exportConversationToDownloads(ctx, repo, conversationId)) {
                is ConversationExportResult.Failure -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", result.code)
                    put("message", result.message)
                }.toString()))
                is ConversationExportResult.Success -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("file_path", result.location)
                    put("message", "Saved to Downloads/chat-exports/${result.filename}")
                }.toString()))
            }
        }
    }
)

internal sealed interface ConversationExportResult {
    data class Success(val filename: String, val location: String) : ConversationExportResult
    data class Failure(val code: String, val message: String) : ConversationExportResult
}

/** Shared typed export path used by both the legacy tool and the compact Owner transaction. */
internal suspend fun exportConversationToDownloads(
    context: Context,
    repository: ConversationRepository,
    conversationId: Uuid,
): ConversationExportResult {
    val conversation = repository.getConversationById(conversationId)
        ?: return ConversationExportResult.Failure("NOT_FOUND", "Conversation not found.")
    val now = LocalDateTime.now()
    val filename = "chat-export-${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"
    val content = buildString {
        appendLine("# ${conversation.title}")
        appendLine("*Exported on $now*")
        appendLine()
        conversation.messageNodes.map { it.currentMessage }.forEach { message ->
            val role = if (message.role == me.rerere.ai.core.MessageRole.USER) "**User**" else "**Assistant**"
            appendLine("$role:")
            appendLine()
            message.parts.filterIsInstance<UIMessagePart.Text>().forEach { appendLine(it.text) }
            appendLine()
            appendLine("---")
            appendLine()
        }
    }
    val location = if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/chat-exports")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ConversationExportResult.Failure(
                "EXPORT_CREATE_FAILED",
                "Unable to create the export in Downloads.",
            )
        try {
            context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(content) }
                ?: error("Unable to open the export destination.")
            val published = context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            check(published == 1) { "Unable to publish the completed export." }
            uri.toString()
        } catch (cancelled: CancellationException) {
            context.contentResolver.delete(uri, null, null)
            throw cancelled
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            return ConversationExportResult.Failure(
                "EXPORT_WRITE_FAILED",
                error.message?.take(500) ?: "Unable to write the export.",
            )
        }
    } else {
        try {
            @Suppress("DEPRECATION")
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val chatExportDir = File(downloadDir, "chat-exports").apply { mkdirs() }
            File(chatExportDir, filename).apply { writeText(content) }.absolutePath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ConversationExportResult.Failure(
                "EXPORT_WRITE_FAILED",
                error.message?.take(500) ?: "Unable to write the export.",
            )
        }
    }
    return ConversationExportResult.Success(filename, location)
}
