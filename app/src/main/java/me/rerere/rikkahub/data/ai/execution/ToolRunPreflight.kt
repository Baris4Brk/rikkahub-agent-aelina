package me.rerere.rikkahub.data.ai.execution

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.ToolExecutionGate
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext

/**
 * Context that must accompany every non-interactive ToolRuntime call.
 *
 * A worker does not have a visible conversation, but it still needs stable ownership for
 * policy, cancellation, browser/display isolation, and audit events. Callers therefore create
 * one identity for the whole worker run rather than inventing a new identity for each action.
 */
data class ToolRuntimeInvocation(
    val executionContext: ToolExecutionContext,
    val unrestrictedOverride: Boolean = false,
)

/**
 * Converts the existing application security gate into the runtime's pre-execution contract.
 * Keeping this as a narrow interface lets headless callers use exactly the same hard gates as
 * chat while making their runtime integration independently testable.
 */
fun interface ToolRunPreflight {
    suspend fun authorize(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
        unrestrictedOverride: Boolean,
    ): ToolPreExecutionDecision
}

class DefaultToolRunPreflight(
    private val toolExecutionGate: ToolExecutionGate,
) : ToolRunPreflight {
    override suspend fun authorize(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
        unrestrictedOverride: Boolean,
    ): ToolPreExecutionDecision = when (
        val result = toolExecutionGate.evaluate(
            toolName = toolName,
            origin = context.callOrigin,
            conversationId = context.conversationId,
            commandId = context.runId,
            arguments = args,
            unrestrictedOverride = unrestrictedOverride,
        )
    ) {
        ToolExecutionGate.GateResult.Allowed -> ToolPreExecutionDecision.Allow
        is ToolExecutionGate.GateResult.Denied -> ToolPreExecutionDecision.Deny(
            errorCode = "tool_blocked",
            reason = result.reason,
        )
    }
}
