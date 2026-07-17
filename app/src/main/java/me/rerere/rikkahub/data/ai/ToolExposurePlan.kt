package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.assistant.InvocationSurfaceContext
import me.rerere.rikkahub.assistant.InvocationSurfacePresence
import me.rerere.rikkahub.assistant.SystemAssistantHostKind
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ToolInvocationSurface

/** One model-visible and executable tool plan derived from the current invocation surface. */
data class ToolExposurePlan(
    val origin: ToolCallOrigin,
    val surfaceAvailable: Boolean,
    val activityOverlayAuthorized: Boolean,
) {
    fun canExpose(toolName: String): Boolean = blockReason(toolName) == null

    fun blockReason(toolName: String): String? {
        if (!surfaceAvailable) return "$toolName is unavailable from the current invocation surface."
        if (origin != ToolCallOrigin.SystemAssistant) return null
        if (toolName in ALWAYS_BLOCKED_SYSTEM_ASSISTANT_TOOLS) {
            return "$toolName is unavailable from the system-assistant surface."
        }
        val descriptor = CapabilityCatalog.byToolName(toolName)
            ?: return "$toolName is not classified for the system-assistant surface."
        if (origin !in descriptor.allowedOrigins) {
            return "$toolName is not allowed from $origin according to the capability catalog."
        }
        val invocationSurface = CapabilityCatalog.toolInvocationSurface(toolName)
        if (!activityOverlayAuthorized) {
            return if (invocationSurface == ToolInvocationSurface.Background) null else
                "$toolName requires the AI-key Activity overlay and is unavailable from VoiceInteraction."
        }
        return when (invocationSurface) {
            ToolInvocationSurface.SystemConsent,
            ToolInvocationSurface.Phase1Unavailable,
            ToolInvocationSurface.Unclassified,
            -> "$toolName requires an unsupported consent or unclassified surface."
            else -> null
        }
    }

    companion object {
        private val ALWAYS_BLOCKED_SYSTEM_ASSISTANT_TOOLS = setOf(
            "ask_user",
            "call_phone",
            "answer_phone_call",
        )

        fun create(
            origin: ToolCallOrigin,
            deviceLocked: Boolean,
            hasAuthorizedInvocation: Boolean,
            surfaceContext: InvocationSurfaceContext?,
        ): ToolExposurePlan {
            val surfaceAvailable = InvocationSurfacePolicy.canExposeToolSurface(
                origin = origin,
                deviceLocked = deviceLocked,
                hasAuthorizedInvocation = hasAuthorizedInvocation,
            )
            val activityOverlayAuthorized = origin == ToolCallOrigin.SystemAssistant &&
                surfaceAvailable &&
                surfaceContext?.hostKind == SystemAssistantHostKind.ACTIVITY_OVERLAY &&
                surfaceContext.unlockedOwner &&
                surfaceContext.presence in setOf(
                    InvocationSurfacePresence.OVERLAY_VISIBLE,
                    InvocationSurfacePresence.RUNNING_AFTER_OVERLAY_CLOSED,
                )
            return ToolExposurePlan(origin, surfaceAvailable, activityOverlayAuthorized)
        }
    }
}
