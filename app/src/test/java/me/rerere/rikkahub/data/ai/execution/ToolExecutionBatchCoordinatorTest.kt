package me.rerere.rikkahub.data.ai.execution

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.SteeringNote
import me.rerere.rikkahub.data.ai.SteeringRegistrationResult
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolExecutionBatchCoordinatorTest {
    private val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant",
        callOrigin = ToolCallOrigin.LocalChat,
    )

    @Test
    fun `parallel batch marks all calls before work and preserves source result order`() = runBlocking {
        val coordinator = ToolExecutionBatchCoordinator(
            ToolExecutionBatchPlanner(
                ToolExecutionPolicyResolver { _, _, _ ->
                    ToolExecutionPolicy(
                        effects = setOf(ToolEffect.LOCAL_READ),
                        concurrency = ToolConcurrency.PARALLEL_SAFE,
                        cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
                    )
                },
            ),
        )
        val control = GenerationRunControl(context.runId)
        val candidates = listOf(candidate("first"), candidate("second"))
        val events = mutableListOf<String>()
        var active = 0
        var maxActive = 0

        val results = coordinator.execute(
            candidates = candidates,
            enabled = true,
            maxParallelism = 3,
            runControl = control,
            onBatchStarted = { batch ->
                events += "marked:${batch.joinToString(",") { it.toolCallId }}"
            },
            execute = { candidate ->
                active += 1
                maxActive = maxOf(maxActive, active)
                events += "started:${candidate.toolCallId}"
                delay(if (candidate.toolCallId == "first") 20 else 1)
                active -= 1
                "result:${candidate.toolCallId}"
            },
        )

        assertEquals("marked:first,second", events.first())
        assertTrue(maxActive >= 2)
        assertEquals(
            listOf("first", "second"),
            results.map { it.candidate.toolCallId },
        )
        assertEquals(
            listOf("result:first", "result:second"),
            results.map { (it.outcome as ToolBatchExecutionOutcome.Executed).value },
        )
        assertTrue(control.executingToolCallIds().isEmpty())
    }

    @Test
    fun `guidance submitted during a started batch skips only the next batch`() = runBlocking {
        val coordinator = ToolExecutionBatchCoordinator(
            ToolExecutionBatchPlanner(
                ToolExecutionPolicyResolver { _, _, _ ->
                    ToolExecutionPolicy(
                        effects = setOf(ToolEffect.LOCAL_READ),
                        concurrency = ToolConcurrency.PARALLEL_SAFE,
                        cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
                    )
                },
            ),
        )
        val control = GenerationRunControl(context.runId)
        val executed = mutableListOf<String>()

        val results = coordinator.execute(
            candidates = listOf(candidate("first"), candidate("second"), candidate("third")),
            enabled = true,
            maxParallelism = 2,
            runControl = control,
            onBatchStarted = { batch ->
                if (batch.first().toolCallId == "first") {
                    assertEquals(
                        SteeringRegistrationResult.Accepted,
                        control.submitSteering(
                            SteeringNote(
                                commandId = Uuid.random(),
                                runId = context.runId,
                                text = "Apply this after the current batch",
                                source = me.rerere.rikkahub.service.chat.CommandOrigin.APP_UI,
                            ),
                        ),
                    )
                }
            },
            execute = { candidate ->
                executed += candidate.toolCallId
                "result:${candidate.toolCallId}"
            },
        )

        assertEquals(listOf("first", "second"), executed)
        assertEquals(
            listOf(
                ToolBatchExecutionOutcome.Executed("result:first"),
                ToolBatchExecutionOutcome.Executed("result:second"),
                ToolBatchExecutionOutcome.SkippedDueToSteering,
            ),
            results.map { it.outcome },
        )
        assertTrue(control.executingToolCallIds().isEmpty())
    }

    @Test
    fun `cancelled run does not mark or invoke a new batch`() = runBlocking {
        val coordinator = ToolExecutionBatchCoordinator(
            ToolExecutionBatchPlanner(
                ToolExecutionPolicyResolver { _, _, _ ->
                    ToolExecutionPolicy(
                        effects = setOf(ToolEffect.LOCAL_READ),
                        concurrency = ToolConcurrency.PARALLEL_SAFE,
                        cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
                    )
                },
            ),
        )
        val control = GenerationRunControl(context.runId)
        control.markInterruptedBy(Uuid.random())
        var marked = false
        var invoked = false

        val failure = runCatching {
            coordinator.execute(
                candidates = listOf(candidate("first")),
                enabled = true,
                maxParallelism = 3,
                runControl = control,
                onBatchStarted = { marked = true },
                execute = {
                    invoked = true
                    "unexpected"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertFalse(marked)
        assertFalse(invoked)
        assertTrue(control.executingToolCallIds().isEmpty())
    }

    @Test
    fun `disabled feature preserves serial marks and execution`() = runBlocking {
        val coordinator = ToolExecutionBatchCoordinator(
            ToolExecutionBatchPlanner(
                ToolExecutionPolicyResolver { _, _, _ ->
                    ToolExecutionPolicy(
                        effects = setOf(ToolEffect.LOCAL_READ),
                        concurrency = ToolConcurrency.PARALLEL_SAFE,
                        cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
                    )
                },
            ),
        )
        val marks = mutableListOf<String>()
        val executions = mutableListOf<String>()
        var active = 0
        var maxActive = 0

        coordinator.execute(
            candidates = listOf(candidate("first"), candidate("second")),
            enabled = false,
            maxParallelism = 3,
            runControl = GenerationRunControl(context.runId),
            onBatchStarted = { batch -> marks += batch.single().toolCallId },
            execute = { candidate ->
                active += 1
                maxActive = maxOf(maxActive, active)
                executions += candidate.toolCallId
                delay(1)
                active -= 1
                candidate.toolCallId
            },
        )

        assertEquals(listOf("first", "second"), marks)
        assertEquals(listOf("first", "second"), executions)
        assertEquals(1, maxActive)
    }

    private fun candidate(id: String) = ToolBatchCandidate(
        index = when (id) {
            "first" -> 0
            "second" -> 1
            else -> 2
        },
        toolCallId = id,
        toolName = "get_time_info",
        args = buildJsonObject { },
        context = context,
    )
}
