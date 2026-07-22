package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.display.DisplayAutomationRuntime
import me.rerere.rikkahub.display.DisplayRequest
import me.rerere.rikkahub.display.DisplayResult
import me.rerere.rikkahub.display.DisplaySession

/**
 * Keeps the experimental display-session surface out of every caller that cannot own it.
 *
 * Creation additionally re-checks the caller in [DisplayAutomationRuntime], but hiding the
 * tools here prevents Telegram, cron, workflow, and legacy no-context turns from discovering
 * an operation they are never allowed to perform.
 */
internal fun displaySessionToolsForInvocation(
    runtime: DisplayAutomationRuntime,
    featureEnabled: Boolean,
    options: List<LocalToolOption>,
    invocationContext: ToolInvocationContext,
): List<Tool> {
    if (!featureEnabled || LocalToolOption.ScreenAutomation !in options) return emptyList()
    val caller = invocationContext.toDisplayCaller() ?: return emptyList()
    if (caller.origin != me.rerere.rikkahub.data.ai.ToolCallOrigin.LocalChat &&
        caller.origin != me.rerere.rikkahub.data.ai.ToolCallOrigin.SystemAssistant
    ) {
        return emptyList()
    }
    return displaySessionTools(runtime, invocationContext)
}

fun displaySessionTools(
    runtime: DisplayAutomationRuntime,
    invocationContext: ToolInvocationContext,
): List<Tool> = listOf(
    Tool(
        name = "display_session_create",
        description = "Create one managed virtual-display session owned by this assistant, conversation, and run. Never falls back to the primary phone display.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val caller = invocationContext.toDisplayCaller()
                ?: return@Tool displayError("display_session_key_missing")
            displayResult(runtime.dispatch(DisplayRequest.Create(caller)))
        },
    ),
    Tool(
        name = "display_session_list",
        description = "List active managed display sessions owned by this exact assistant conversation run.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val caller = invocationContext.toDisplayCaller()
                ?: return@Tool displayError("display_session_key_missing")
            displayResult(runtime.dispatch(DisplayRequest.ListOwned(caller)))
        },
    ),
    Tool(
        name = "display_session_status",
        description = "Read the status and supported capabilities of one owned managed display session.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("display_session_id", stringSchema("Owned display session id"))
                },
                required = listOf("display_session_id"),
            )
        },
        execute = { input ->
            val caller = invocationContext.toDisplayCaller()
                ?: return@Tool displayError("display_session_key_missing")
            val id = input.jsonObject["display_session_id"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotEmpty)
                ?: return@Tool displayError("display_session_id_required")
            displayResult(runtime.dispatch(DisplayRequest.Status(caller, id)))
        },
    ),
    Tool(
        name = "display_session_close",
        description = "Close one owned managed display session and release its virtual display.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("display_session_id", stringSchema("Owned display session id"))
                },
                required = listOf("display_session_id"),
            )
        },
        execute = { input ->
            val caller = invocationContext.toDisplayCaller()
                ?: return@Tool displayError("display_session_key_missing")
            val id = input.jsonObject["display_session_id"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotEmpty)
                ?: return@Tool displayError("display_session_id_required")
            displayResult(runtime.dispatch(DisplayRequest.Close(caller, id)))
        },
    ),
)

private fun displayResult(result: DisplayResult): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        when (result) {
            is DisplayResult.Created -> buildJsonObject {
                put("success", true)
                put("session", result.session.toJson())
            }
            is DisplayResult.Sessions -> buildJsonObject {
                put("sessions", buildJsonArray {
                    result.sessions.forEach { add(it.toJson()) }
                })
            }
            is DisplayResult.SessionStatus -> buildJsonObject {
                put("session", result.session.toJson())
            }
            is DisplayResult.Resolved -> buildJsonObject {
                put("success", true)
                put("display_session_id", result.sessionId)
            }
            is DisplayResult.Closed -> buildJsonObject {
                put("success", true)
                put("display_session_id", result.sessionId)
            }
            is DisplayResult.Error -> buildJsonObject { put("error", result.code) }
        }.toString()
    )
)

private fun displayError(code: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject { put("error", code) }.toString())
)

private fun DisplaySession.toJson() = buildJsonObject {
    put("display_session_id", id)
    put("lifecycle", lifecycle.name.lowercase())
    put("capabilities", buildJsonArray {
        capabilities.sortedBy { it.name }.forEach { add(it.name.lowercase()) }
    })
    put("created_at_ms", createdAtMs)
    put("hard_expires_at_ms", hardExpiresAtMs)
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}
