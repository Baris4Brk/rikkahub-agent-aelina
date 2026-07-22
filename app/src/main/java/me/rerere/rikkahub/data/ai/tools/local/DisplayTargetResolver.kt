package me.rerere.rikkahub.data.ai.tools.local

import android.accessibilityservice.GestureDescription
import android.os.Build
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.display.DisplayAutomationRuntime
import me.rerere.rikkahub.display.DisplayCaller
import me.rerere.rikkahub.display.DisplayCapability
import me.rerere.rikkahub.display.DisplayRequest
import me.rerere.rikkahub.display.DisplayResult

data class ResolvedDisplayTarget(
    val displayId: Int,
    val sessionId: String?,
) {
    val isPrimary: Boolean get() = displayId == android.view.Display.DEFAULT_DISPLAY
}

sealed interface DisplayTargetResolution {
    data class Resolved(val target: ResolvedDisplayTarget) : DisplayTargetResolution
    data class Error(val code: String) : DisplayTargetResolution
}

class DisplayTargetResolver(
    private val runtime: DisplayAutomationRuntime,
) {
    suspend fun resolve(
        input: JsonElement,
        invocationContext: ToolInvocationContext,
        requiredCapability: DisplayCapability,
        legacyDisplayIdKey: String? = null,
    ): DisplayTargetResolution {
        val obj = input.jsonObject
        val rawSessionId = obj[DISPLAY_SESSION_ID]?.jsonPrimitive?.contentOrNull
        if (obj.containsKey(DISPLAY_SESSION_ID) && rawSessionId.isNullOrBlank()) {
            return DisplayTargetResolution.Error("display_session_id_required")
        }
        val sessionId = rawSessionId?.trim()?.takeIf(String::isNotEmpty)
        val legacyDisplayId = legacyDisplayIdKey?.let { key ->
            obj[key]?.jsonPrimitive?.intOrNull
        }
        if (sessionId == null) {
            return if (legacyDisplayId == null || legacyDisplayId == PRIMARY_DISPLAY_ID) {
                DisplayTargetResolution.Resolved(
                    ResolvedDisplayTarget(PRIMARY_DISPLAY_ID, sessionId = null)
                )
            } else {
                DisplayTargetResolution.Error("display_session_required")
            }
        }
        val caller = invocationContext.toDisplayCaller()
            ?: return DisplayTargetResolution.Error("display_session_key_missing")
        return when (val result = runtime.dispatch(
            DisplayRequest.Resolve(caller, sessionId, requiredCapability)
        )) {
            is DisplayResult.Resolved -> {
                if (result.displayId == PRIMARY_DISPLAY_ID) {
                    // A managed session is never allowed to degrade into an operation on the
                    // user's physical display, even if a broken bridge reports display 0.
                    DisplayTargetResolution.Error("display_primary_forbidden")
                } else if (legacyDisplayId != null && legacyDisplayId != result.displayId) {
                    DisplayTargetResolution.Error("display_id_mismatch")
                } else {
                    DisplayTargetResolution.Resolved(
                        ResolvedDisplayTarget(result.displayId, result.sessionId)
                    )
                }
            }
            is DisplayResult.Error -> DisplayTargetResolution.Error(result.code)
            else -> DisplayTargetResolution.Error("display_resolution_failed")
        }
    }

    companion object {
        const val DISPLAY_SESSION_ID = "display_session_id"
        private const val PRIMARY_DISPLAY_ID = 0
    }
}

fun ToolInvocationContext.toDisplayCaller(): DisplayCaller? {
    val assistantId = callerAssistantId?.takeIf(String::isNotBlank) ?: return null
    val conversationId = callerConversationId?.takeIf(String::isNotBlank) ?: return null
    val runId = callerRunId?.takeIf(String::isNotBlank) ?: return null
    val origin = callOrigin ?: return null
    return DisplayCaller(assistantId, conversationId, runId, origin)
}

fun GestureDescription.Builder.buildForDisplay(displayId: Int): GestureDescription? {
    if (displayId != android.view.Display.DEFAULT_DISPLAY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        setDisplayId(displayId)
    }
    return build()
}

suspend fun resolveDisplayTargetOrPrimary(
    resolver: DisplayTargetResolver?,
    input: JsonElement,
    invocationContext: ToolInvocationContext,
    requiredCapability: DisplayCapability,
    legacyDisplayIdKey: String? = null,
): DisplayTargetResolution {
    val obj = input.jsonObject
    val rawSessionId = obj[DisplayTargetResolver.DISPLAY_SESSION_ID]
        ?.jsonPrimitive?.contentOrNull
    if (obj.containsKey(DisplayTargetResolver.DISPLAY_SESSION_ID) && rawSessionId.isNullOrBlank()) {
        return DisplayTargetResolution.Error("display_session_id_required")
    }
    if (resolver != null) {
        return resolver.resolve(
            input,
            invocationContext,
            requiredCapability,
            legacyDisplayIdKey,
        )
    }
    if (!obj[DisplayTargetResolver.DISPLAY_SESSION_ID]
            ?.jsonPrimitive?.contentOrNull.isNullOrBlank()
    ) {
        return DisplayTargetResolution.Error("display_runtime_unavailable")
    }
    val legacyId = legacyDisplayIdKey?.let { obj[it]?.jsonPrimitive?.intOrNull }
    return if (legacyId == null || legacyId == android.view.Display.DEFAULT_DISPLAY) {
        DisplayTargetResolution.Resolved(
            ResolvedDisplayTarget(android.view.Display.DEFAULT_DISPLAY, null)
        )
    } else {
        DisplayTargetResolution.Error("display_session_required")
    }
}

fun displaySessionIdSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", "Optional owned managed display session id; omitted means primary display")
}

fun displayTargetError(code: String): JsonObject = buildJsonObject { put("error", code) }
