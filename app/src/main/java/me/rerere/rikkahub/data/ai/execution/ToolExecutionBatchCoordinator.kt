package me.rerere.rikkahub.data.ai.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolStartDecision

sealed interface ToolBatchExecutionOutcome<out T> {
    data class Executed<T>(val value: T) : ToolBatchExecutionOutcome<T>
    data object SkippedDueToSteering : ToolBatchExecutionOutcome<Nothing>
}

data class ToolBatchExecution<T>(
    val candidate: ToolBatchCandidate,
    val outcome: ToolBatchExecutionOutcome<T>,
)

/**
 * Runs planner-approved batches while keeping the steering boundary and result order owned by
 * one place. Callers mark durable execution state in [onBatchStarted] before any child starts,
 * then receive every result in original model-call order even when the batch ran concurrently.
 */
class ToolExecutionBatchCoordinator(
    private val planner: ToolExecutionBatchPlanner,
) {
    suspend fun <T> execute(
        candidates: List<ToolBatchCandidate>,
        enabled: Boolean,
        maxParallelism: Int,
        runControl: GenerationRunControl?,
        onBatchStarted: suspend (List<ToolBatchCandidate>) -> Unit,
        execute: suspend (ToolBatchCandidate) -> T,
    ): List<ToolBatchExecution<T>> {
        if (candidates.isEmpty()) return emptyList()

        val results = ArrayList<ToolBatchExecution<T>>(candidates.size)
        val batches = planner.plan(candidates, enabled, maxParallelism)
        for (batch in batches) {
            val toolCallIds = batch.candidates.mapTo(linkedSetOf()) { it.toolCallId }
            when (runControl?.beginToolBatchOrYieldToSteering(toolCallIds)) {
                ToolStartDecision.YieldToSteering -> {
                    results += batch.candidates.map { candidate ->
                        ToolBatchExecution(
                            candidate = candidate,
                            outcome = ToolBatchExecutionOutcome.SkippedDueToSteering,
                        )
                    }
                    continue
                }

                ToolStartDecision.RunCancelled -> throw CancellationException(
                    "Run cancelled before tool batch execution",
                )

                ToolStartDecision.Proceed,
                null -> Unit
            }

            val executionStarted = runControl != null
            try {
                onBatchStarted(batch.candidates)
                val values = if (batch.parallel) {
                    coroutineScope {
                        batch.candidates.map { candidate ->
                            async { execute(candidate) }
                        }.awaitAll()
                    }
                } else {
                    batch.candidates.map { candidate -> execute(candidate) }
                }
                results += batch.candidates.zip(values) { candidate, value ->
                    ToolBatchExecution(
                        candidate = candidate,
                        outcome = ToolBatchExecutionOutcome.Executed(value),
                    )
                }
            } finally {
                if (executionStarted) {
                    runControl?.finishToolBatch(toolCallIds)
                }
            }
        }
        return results
    }
}
