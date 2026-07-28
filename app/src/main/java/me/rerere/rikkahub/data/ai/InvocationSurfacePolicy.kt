package me.rerere.rikkahub.data.ai

/**
 * Complete security decision for one invocation surface.
 *
 * The origin is captured when a command is accepted. In particular, a keyguard invocation never
 * becomes trusted merely because the device is unlocked later in the same run.
 */
data class InvocationSurfaceDecision(
    val allowsToolExecution: Boolean,
    val allowsPrivilegedToolInjection: Boolean,
    val allowsAutoApproval: Boolean,
    val allowsSelectedConversationUnrestricted: Boolean,
    val requiresVisibleForegroundSurface: Boolean,
    val allowsForegroundOnlyTools: Boolean,
)

/** Single source of truth for origin trust, tool-surface and elevation policy. */
object InvocationSurfacePolicy {
    /** Interactive local origins that may use non-Activity tools while visibly unlocked. */
    val LOCAL_UNLOCKED: Set<ToolCallOrigin> = setOf(
        ToolCallOrigin.LocalChat,
        ToolCallOrigin.SystemAssistant,
    )

    /** Interactive local origins plus an explicitly trusted background workflow. */
    val LOCAL_OR_WORKFLOW: Set<ToolCallOrigin> = LOCAL_UNLOCKED + ToolCallOrigin.TrustedWorkflow

    /** Origins controlled outside the foreground RikkaHub UI. */
    val REMOTE: Set<ToolCallOrigin> = setOf(
        ToolCallOrigin.Telegram,
        ToolCallOrigin.WebServer,
        ToolCallOrigin.MCP,
        ToolCallOrigin.ExternalIntent,
    )

    /** Every origin that may own a tool surface. Keyguard is intentionally absent. */
    val ALL_NON_KEYGUARD: Set<ToolCallOrigin> = LOCAL_OR_WORKFLOW + REMOTE + setOf(
        ToolCallOrigin.PetHandoffConfirmed,
        ToolCallOrigin.PetHandoffAuto,
    )

    fun forOrigin(origin: ToolCallOrigin): InvocationSurfaceDecision = when (origin) {
        ToolCallOrigin.LocalChat -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = true,
            allowsAutoApproval = true,
            allowsSelectedConversationUnrestricted = true,
            requiresVisibleForegroundSurface = true,
            allowsForegroundOnlyTools = true,
        )
        ToolCallOrigin.SystemAssistant -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = true,
            allowsAutoApproval = true,
            allowsSelectedConversationUnrestricted = true,
            requiresVisibleForegroundSurface = true,
            allowsForegroundOnlyTools = false,
        )
        ToolCallOrigin.PetHandoffConfirmed -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = true,
            allowsAutoApproval = true,
            allowsSelectedConversationUnrestricted = true,
            requiresVisibleForegroundSurface = true,
            allowsForegroundOnlyTools = true,
        )
        ToolCallOrigin.PetHandoffAuto -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = true,
            allowsAutoApproval = false,
            allowsSelectedConversationUnrestricted = false,
            requiresVisibleForegroundSurface = false,
            allowsForegroundOnlyTools = false,
        )
        ToolCallOrigin.PetInteraction -> InvocationSurfaceDecision(
            allowsToolExecution = false,
            allowsPrivilegedToolInjection = false,
            allowsAutoApproval = false,
            allowsSelectedConversationUnrestricted = false,
            requiresVisibleForegroundSurface = false,
            allowsForegroundOnlyTools = false,
        )
        ToolCallOrigin.QuickCapture -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = true,
            allowsAutoApproval = true,
            allowsSelectedConversationUnrestricted = true,
            requiresVisibleForegroundSurface = true,
            allowsForegroundOnlyTools = false,
        )
        ToolCallOrigin.SystemAssistantKeyguard -> InvocationSurfaceDecision(
            allowsToolExecution = false,
            allowsPrivilegedToolInjection = false,
            allowsAutoApproval = false,
            allowsSelectedConversationUnrestricted = false,
            requiresVisibleForegroundSurface = true,
            allowsForegroundOnlyTools = false,
        )
        ToolCallOrigin.TrustedWorkflow -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = false,
            allowsAutoApproval = true,
            allowsSelectedConversationUnrestricted = false,
            requiresVisibleForegroundSurface = false,
            allowsForegroundOnlyTools = true,
        )
        ToolCallOrigin.Telegram,
        ToolCallOrigin.WebServer,
        ToolCallOrigin.MCP,
        ToolCallOrigin.ExternalIntent,
        -> InvocationSurfaceDecision(
            allowsToolExecution = true,
            allowsPrivilegedToolInjection = false,
            allowsAutoApproval = true,
            allowsSelectedConversationUnrestricted = false,
            requiresVisibleForegroundSurface = false,
            allowsForegroundOnlyTools = true,
        )
    }

    fun canInjectPrivilegedTools(origin: ToolCallOrigin, isHeadless: Boolean): Boolean =
        !isHeadless && forOrigin(origin).allowsPrivilegedToolInjection

    fun toolExecutionBlockReason(toolName: String, origin: ToolCallOrigin): String? =
        if (forOrigin(origin).allowsToolExecution) {
            null
        } else {
            "$toolName is unavailable from this invocation surface. Open the unlocked app to continue."
        }

    /**
     * The trusted system-assistant elevation exists only while its native overlay is visible or
     * while a command accepted from that overlay still holds a conversation-scoped run lease.
     * The device must remain unlocked in either case.
     */
    fun systemAssistantVisibilityBlockReason(
        origin: ToolCallOrigin,
        deviceLocked: Boolean,
        hasAuthorizedInvocation: Boolean,
    ): String? = when {
        !forOrigin(origin).requiresVisibleForegroundSurface -> null
        origin != ToolCallOrigin.SystemAssistant && origin != ToolCallOrigin.QuickCapture -> null
        deviceLocked -> "Unlock the device and invoke the system assistant again."
        !hasAuthorizedInvocation ->
            "The trusted assistant overlay is no longer visible for this conversation. Invoke it again."
        else -> null
    }

    fun canExposeToolSurface(
        origin: ToolCallOrigin,
        deviceLocked: Boolean,
        hasAuthorizedInvocation: Boolean,
    ): Boolean = forOrigin(origin).allowsToolExecution &&
        systemAssistantVisibilityBlockReason(
            origin = origin,
            deviceLocked = deviceLocked,
            hasAuthorizedInvocation = hasAuthorizedInvocation,
        ) == null

    /** Local tools fail closed unless Catalog explicitly classifies them for this surface. */
    fun canExposeSystemAssistantLocalTool(
        toolName: String,
        catalogAllowsOrigin: Boolean,
        requiresForegroundApp: Boolean,
    ): Boolean = toolName != "ask_user" &&
        toolName != "call_phone" &&
        toolName != "answer_phone_call" &&
        catalogAllowsOrigin &&
        !requiresForegroundApp

    fun foregroundRequirementBlockReason(
        toolName: String,
        origin: ToolCallOrigin,
        requiresForegroundApp: Boolean,
        appInForeground: Boolean,
    ): String? = when {
        !requiresForegroundApp -> null
        !forOrigin(origin).allowsForegroundOnlyTools ->
            "$toolName requires the full RikkaHub activity in the foreground."
        !appInForeground -> "$toolName requires RikkaHub to be in the foreground."
        else -> null
    }
}
