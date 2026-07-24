package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool

/**
 * Provider tool arrays participate in prefix-cache keys on OpenAI-compatible gateways. Tool
 * discovery may originate from file-system, plugin, or MCP collections whose iteration order is
 * not contractual, so normalize the final model-facing surface before every request.
 */
internal fun stableProviderToolOrder(tools: List<Tool>): List<Tool> = tools.sortedBy { it.name }
