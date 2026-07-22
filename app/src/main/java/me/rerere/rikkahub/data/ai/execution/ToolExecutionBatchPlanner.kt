package me.rerere.rikkahub.data.ai.execution

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext

data class ToolBatchCandidate(
    val index: Int,
    val toolCallId: String,
    val toolName: String,
    val args: JsonObject,
    val context: ToolExecutionContext,
)

data class PlannedToolBatch(
    val candidates: List<ToolBatchCandidate>,
    val parallel: Boolean,
)

/**
 * Builds only contiguous, read-only batches. It never partitions a turn into global read/write
 * buckets: an unsafe call or a resource collision is an order barrier at its original position.
 */
class ToolExecutionBatchPlanner(
    private val policyResolver: ToolExecutionPolicyResolver,
) {
    fun plan(
        candidates: List<ToolBatchCandidate>,
        enabled: Boolean,
        maxParallelism: Int,
    ): List<PlannedToolBatch> {
        if (!enabled || candidates.size < 2) return candidates.map(::serial)
        val limit = maxParallelism.coerceIn(1, MAX_PARALLELISM_HARD_CAP)
        if (limit == 1) return candidates.map(::serial)

        val result = mutableListOf<PlannedToolBatch>()
        val pending = mutableListOf<ToolBatchCandidate>()
        val pendingKeys = hashSetOf<ToolResourceKey>()

        fun flushPending() {
            if (pending.isEmpty()) return
            if (pending.size == 1) result += serial(pending.single())
            else result += PlannedToolBatch(pending.toList(), parallel = true)
            pending.clear()
            pendingKeys.clear()
        }

        candidates.forEach { candidate ->
            val policy = runCatching {
                policyResolver.resolve(candidate.toolName, candidate.args, candidate.context)
            }.getOrElse { ToolExecutionPolicy.UNKNOWN }
            val safe = policy.allowReadOnlyParallelBatch &&
                candidate.toolName != "download_file" &&
                !candidate.toolName.startsWith("plugin__")
            val conflicts = policy.resourceKeys.any(pendingKeys::contains)
            if (!safe || conflicts) {
                flushPending()
                if (!safe) {
                    result += serial(candidate)
                } else {
                    pending += candidate
                    pendingKeys += policy.resourceKeys
                }
                return@forEach
            }
            if (pending.size >= limit) flushPending()
            pending += candidate
            pendingKeys += policy.resourceKeys
        }
        flushPending()
        return result
    }

    private fun serial(candidate: ToolBatchCandidate) = PlannedToolBatch(
        candidates = listOf(candidate),
        parallel = false,
    )

    private companion object {
        const val MAX_PARALLELISM_HARD_CAP = 8
    }
}
