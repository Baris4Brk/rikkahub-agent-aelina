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
            val conversation = repo.getConversationById(conversationId)
            if (conversation == null) return@runBlocking listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NOT_FOUND"); put("message", "Conversation not found.")
            }.toString()))

            val messages = conversation.messageNodes.map { it.currentMessage }

            val filename = "chat-export-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"
            val content = buildString {
                appendLine("# ${conversation.title}")
                appendLine("*Exported on ${LocalDateTime.now()}*")
                appendLine()
                messages.forEach { msg ->
                    val role = if (msg.role == me.rerere.ai.core.MessageRole.USER) "**User**" else "**Assistant**"
                    appendLine("$role:")
                    appendLine()
                    msg.parts.forEach { part ->
                        if (part is UIMessagePart.Text) {
                            appendLine(part.text)
                        }
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            val location = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/chat-exports",
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return@runBlocking listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "EXPORT_CREATE_FAILED")
                    put("message", "Unable to create the export in Downloads.")
                }.toString()))
                try {
                    ctx.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { writer ->
                        writer.write(content)
                    } ?: error("Unable to open the export destination.")
                    val published = ctx.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    check(published == 1) { "Unable to publish the completed export." }
                    uri.toString()
                } catch (cancelled: CancellationException) {
                    ctx.contentResolver.delete(uri, null, null)
                    throw cancelled
                } catch (error: Exception) {
                    ctx.contentResolver.delete(uri, null, null)
                    return@runBlocking listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "EXPORT_WRITE_FAILED")
                        put("message", error.message ?: "Unable to write the export.")
                    }.toString()))
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                val chatExportDir = File(downloadDir, "chat-exports").apply { mkdirs() }
                File(chatExportDir, filename).apply { writeText(content) }.absolutePath
            }

            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("file_path", location)
                put("message", "Saved to Downloads/chat-exports/$filename")
            }.toString()))
        }
    }
)
