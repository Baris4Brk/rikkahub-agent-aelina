package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.execution.ManagedExecutionCaller
import me.rerere.rikkahub.execution.ManagedExecutionRuntime
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

data class ToolNameSnapshot(
    val available: Set<String>,
    val known: Set<String>,
) {
    companion object {
        val EMPTY = ToolNameSnapshot(emptySet(), emptySet())
    }
}

/**
 * One-shot handoff of the exact model-facing tool names built for a parent turn.
 *
 * Tool factories need the invocation context before the complete local/search/MCP surface exists.
 * Publishing once after that surface is assembled lets a deferred `subagent_dispatch` read the
 * final allow-list without exposing mutable collections or guessing from Assistant settings.
 */
class ToolNameSurface private constructor(
    private val writable: Boolean,
) {
    constructor() : this(writable = true)

    private val snapshot = AtomicReference(ToolNameSnapshot.EMPTY)

    fun publish(
        available: Set<String>,
        known: Set<String>,
    ): Boolean {
        if (!writable) return false
        return snapshot.compareAndSet(
            ToolNameSnapshot.EMPTY,
            ToolNameSnapshot(
                available = available.toSet(),
                known = (known + available).toSet(),
            ),
        )
    }

    fun snapshot(): ToolNameSnapshot = snapshot.get()

    companion object {
        /** Shared, permanently empty fallback for legacy contexts that cannot publish a turn. */
        val EMPTY = ToolNameSurface(writable = false)
    }
}

/**
 * Phase 17 stability — context every tool factory in [LocalTools.getTools] sees about WHO
 * is invoking it. Until this layer existed, tools that needed to know the calling
 * conversation / assistant id (sub-agent recursion guard, workflow_create authoring-id
 * persistence) had no way to read it — both shipped with placeholder defaults and silent
 * gaps the audit caught.
 *
 * Convention: every getTools() caller MUST construct a ToolInvocationContext with the most
 * specific data it has. The default ([EMPTY]) is a no-op safe fallback used when the
 * caller doesn't track the data (legacy / one-off paths) — but factories should treat the
 * empty context as "I don't know" not "no constraints", and apply conservative defaults.
 *
 * Fields:
 *  - [callerAssistantId]: the assistant whose toggles are being dispatched. ChatService
 *    knows this from `settings.getCurrentAssistant().id`. Cron / workflow / sub-agent
 *    paths know it from their respective entity's assistant id.
 *  - [callerConversationId]: the conversation-uuid of the user-facing chat (interactive)
 *    or the headless conversation (cron / workflow / sub-agent / external-automation).
 *  - [callerWorkspaceId]: the Assistant-selected workspace when this execution can safely
 *    inspect that workspace's managed process ledger. It is deliberately optional because a
 *    conversation can run without a workspace.
 *  - [isHeadless]: true when the dispatch is happening from a system flow rather than the
 *    user typing in a chat. Sub-agents, cron jobs, workflows, and external-automation
 *    runs all set this to true so the recursion guard fires.
 *  - [modelCanSeeImages]: true iff the model handling this turn has image input in its
 *    modalities. `show_image` reads this so a text-only model is told plainly it cannot
 *    see the picture (and must OCR / file-process it) instead of being handed dimensions
 *    that read like "I looked at it" — the root cause of confabulated image descriptions.
 *    Defaults to `true`: the no-knowledge fallback preserves the pre-fix behaviour, and
 *    ChatService (the only LLM-driven dispatch path) always sets it explicitly.
 */
data class ToolInvocationContext(
    val callerAssistantId: String? = null,
    val callerConversationId: String? = null,
    val callerModelId: String? = null,
    val callerRunId: String? = null,
    val callerWorkspaceId: String? = null,
    val callOrigin: ToolCallOrigin? = null,
    val isHeadless: Boolean = false,
    val modelCanSeeImages: Boolean = true,
    val privilege: PrivilegedSessionContext? = null,
    val toolNameSurface: ToolNameSurface = ToolNameSurface.EMPTY,
) {
    companion object {
        /** No-knowledge fallback. Factories that depend on context MUST handle this. */
        val EMPTY = ToolInvocationContext()
    }
}

/**
 * Converts an already-scoped tool invocation into the smaller managed-execution interface.
 *
 * The managed execution tools are intentionally absent when a caller cannot prove all four
 * ownership dimensions. This prevents headless or legacy callers from listing another run's
 * records merely because the Assistant enabled Termux or SSH.
 */
internal fun ToolInvocationContext.toManagedExecutionCaller(
    options: List<LocalToolOption>,
): ManagedExecutionCaller? {
    val assistantId = callerAssistantId?.takeIf(String::isNotBlank) ?: return null
    val conversationId = callerConversationId?.takeIf(String::isNotBlank) ?: return null
    val runId = callerRunId?.takeIf(String::isNotBlank) ?: return null
    val origin = callOrigin ?: return null
    val workspaceId = callerWorkspaceId?.takeIf(String::isNotBlank)
    val allowedRuntimes = buildSet {
        if (workspaceId != null) add(ManagedExecutionRuntime.WORKSPACE)
        if (LocalToolOption.Termux in options) add(ManagedExecutionRuntime.TERMUX)
        if (LocalToolOption.Ssh in options) add(ManagedExecutionRuntime.SSH)
    }
    if (allowedRuntimes.isEmpty()) return null
    return ManagedExecutionCaller(
        assistantId = assistantId,
        conversationId = conversationId,
        runId = runId,
        origin = origin,
        allowedRuntimes = allowedRuntimes,
        workspaceId = workspaceId,
    )
}
