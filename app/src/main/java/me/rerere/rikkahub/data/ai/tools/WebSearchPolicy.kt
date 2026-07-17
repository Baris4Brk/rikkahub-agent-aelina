package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.model.Assistant

/** Single policy seam for deciding whether built-in web-search tools enter a model turn. */
object WebSearchPolicy {
    fun canInject(
        assistant: Assistant,
        origin: ToolCallOrigin,
        toolSurfaceAvailable: Boolean,
    ): Boolean = assistant.enableWebSearch &&
        toolSurfaceAvailable &&
        origin in InvocationSurfacePolicy.ALL_NON_KEYGUARD
}
