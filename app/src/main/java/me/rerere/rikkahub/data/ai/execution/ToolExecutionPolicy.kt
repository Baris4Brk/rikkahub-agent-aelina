package me.rerere.rikkahub.data.ai.execution

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext

/** Effects are deliberately coarse. A call can carry more than one effect. */
enum class ToolEffect {
    LOCAL_READ,
    SENSITIVE_READ,
    NETWORK_READ,
    NETWORK_WRITE,
    FILE_READ,
    FILE_WRITE,
    BROWSER_READ,
    BROWSER_WRITE,
    DISPLAY_READ,
    DISPLAY_WRITE,
    COMMUNICATION,
    SHELL_EXECUTION,
    PERSISTENT_STATE,
    UNKNOWN,
}

enum class ToolConcurrency {
    PARALLEL_SAFE,
    RESOURCE_SERIAL,
    GLOBAL_SERIAL,
}

/** What cancelling the returned execution handle can actually guarantee. */
enum class ToolCancellationCapability {
    REAL,
    COOPERATIVE,
    LOCAL_WAIT_ONLY,
    UNKNOWN,
}

/**
 * A non-secret lock identity. [opaqueId] is a truncated SHA-256, never a path, URL, token,
 * browser page id, display lease id, or remote profile name supplied by the model.
 */
data class ToolResourceKey(
    val namespace: String,
    val opaqueId: String,
) {
    init {
        require(namespace.matches(Regex("[a-z0-9_-]{1,32}")))
        require(opaqueId.matches(Regex("[0-9a-f]{16}")))
    }

    override fun toString(): String = "$namespace:$opaqueId"
}

data class ToolExecutionPolicy(
    val effects: Set<ToolEffect>,
    val concurrency: ToolConcurrency,
    val resourceKeys: Set<ToolResourceKey> = emptySet(),
    val cancellationCapability: ToolCancellationCapability,
) {
    val allowReadOnlyParallelBatch: Boolean
        get() = concurrency == ToolConcurrency.PARALLEL_SAFE &&
            effects.isNotEmpty() &&
            effects.all { it in READ_ONLY_EFFECTS } &&
            cancellationCapability != ToolCancellationCapability.UNKNOWN

    companion object {
        private val READ_ONLY_EFFECTS = setOf(
            ToolEffect.LOCAL_READ,
            ToolEffect.NETWORK_READ,
            ToolEffect.FILE_READ,
            ToolEffect.BROWSER_READ,
            ToolEffect.DISPLAY_READ,
        )

        val UNKNOWN = ToolExecutionPolicy(
            effects = setOf(ToolEffect.UNKNOWN),
            concurrency = ToolConcurrency.GLOBAL_SERIAL,
            cancellationCapability = ToolCancellationCapability.UNKNOWN,
        )
    }
}

fun interface ToolExecutionPolicyResolver {
    fun resolve(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionPolicy
}

