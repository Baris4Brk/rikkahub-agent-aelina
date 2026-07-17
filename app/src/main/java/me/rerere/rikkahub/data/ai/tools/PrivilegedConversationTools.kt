package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.privilege.PrivilegedMessageSubmission
import me.rerere.rikkahub.service.chat.SubmitResult
import kotlin.uuid.Uuid

private const val MAX_SECOND_USER_MESSAGE_CHARS = 32 * 1024
private const val MAX_SECOND_USER_REQUEST_ID_CHARS = 128

/**
 * Creates the only privileged cross-conversation write seam. It validates identity and
 * recursion here, then delegates to ChatService so the target message retains the normal
 * durable queue, dedupe, recovery, and origin rules.
 */
fun createConversationSendMessageTool(
    invocationContext: ToolInvocationContext,
    conversationExists: suspend (Uuid) -> Boolean,
    submit: suspend (PrivilegedMessageSubmission) -> SubmitResult,
): Tool = Tool(
    name = "conversation_send_message",
    description = """
        Send a persistent message as the configured second-user identity to another conversation.
        The target receives the message through its normal durable queue and may answer it.
        Use a stable request_id for retries. Never target the current privileged conversation;
        reply normally or use steering there instead.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Target conversation UUID")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Message text, up to 32768 characters")
                })
                put("answer", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether the target assistant should answer; defaults to true")
                })
                put("request_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Stable caller-generated idempotency key for retries")
                })
            },
            required = listOf("conversation_id", "text", "request_id"),
        )
    },
    execute = { input ->
        val privilege = invocationContext.privilege
        if (privilege?.isPrivileged != true) {
            return@Tool privilegedToolResult(
                ok = false,
                code = "PRIVILEGED_SESSION_REQUIRED",
                message = "This tool is only available in the configured privileged conversation.",
            )
        }

        val inputObject = input.jsonObject
        val targetId = inputObject["conversation_id"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return@Tool privilegedToolResult(false, "INVALID_CONVERSATION_ID", "conversation_id must be a UUID.")
        if (targetId == privilege.conversationId) {
            return@Tool privilegedToolResult(
                false,
                "SAME_CONVERSATION_NOT_SUPPORTED",
                "Use a normal reply or steering inside the current privileged conversation.",
            )
        }
        if (!conversationExists(targetId)) {
            return@Tool privilegedToolResult(false, "CONVERSATION_NOT_FOUND", "The target conversation does not exist.")
        }

        val text = inputObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            return@Tool privilegedToolResult(false, "EMPTY_MESSAGE", "text must not be empty.")
        }
        if (text.length > MAX_SECOND_USER_MESSAGE_CHARS) {
            return@Tool privilegedToolResult(false, "MESSAGE_TOO_LONG", "text exceeds 32768 characters.")
        }
        val requestId = inputObject["request_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (requestId.isEmpty() || requestId.length > MAX_SECOND_USER_REQUEST_ID_CHARS) {
            return@Tool privilegedToolResult(
                false,
                "INVALID_REQUEST_ID",
                "request_id must contain 1 to 128 characters.",
            )
        }

        val result = submit(
            PrivilegedMessageSubmission(
                conversationId = targetId,
                parts = listOf(UIMessagePart.Text(text)),
                answer = inputObject["answer"]?.jsonPrimitive?.booleanOrNull ?: true,
                dedupeKey = "second-user:${privilege.conversationId}:$requestId",
                annotations = listOf(
                    UIMessageAnnotation.SecondUser(
                        sourceAssistantId = privilege.assistantId,
                        sourceConversationId = privilege.conversationId,
                        displayName = privilege.identityName,
                    )
                ),
            )
        )
        when (result) {
            is SubmitResult.Accepted -> privilegedToolResult(
                true,
                "ACCEPTED",
                "The message was accepted by the target conversation queue.",
                buildJsonObject {
                    put("command_id", result.commandId.toString())
                    put("conversation_id", targetId.toString())
                },
            )
            is SubmitResult.QueueFull -> privilegedToolResult(
                false,
                "QUEUE_FULL",
                "The target conversation queue is full.",
                buildJsonObject { put("limit", result.limit) },
            )
            is SubmitResult.RuntimeUnavailable -> privilegedToolResult(
                false,
                "RUNTIME_UNAVAILABLE",
                result.reason,
            )
            is SubmitResult.Rejected -> privilegedToolResult(false, "REJECTED", result.reason)
        }
    },
)

internal fun privilegedToolResult(
    ok: Boolean,
    code: String,
    message: String,
    data: kotlinx.serialization.json.JsonObject? = null,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", ok)
            put("code", code)
            put("message", message)
            data?.let { put("data", it) }
        }.toString()
    )
)
