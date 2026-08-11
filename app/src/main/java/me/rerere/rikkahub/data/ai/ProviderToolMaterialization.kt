package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool

/**
 * Resolves model-visible schemas once for one provider call.
 *
 * Diagnostics and provider adapters both receive the returned tools, so a lazy or state-backed
 * schema supplier cannot describe one request while serializing a different one. Runtime approval
 * and execution closures are preserved and continue to be evaluated live.
 */
internal fun List<Tool>.materializeProviderToolSchemas(): List<Tool> = map { tool ->
    val resolvedParameters = tool.parameters()
    tool.copy(parameters = { resolvedParameters })
}
