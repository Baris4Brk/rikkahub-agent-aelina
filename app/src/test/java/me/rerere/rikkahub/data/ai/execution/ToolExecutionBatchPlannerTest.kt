package me.rerere.rikkahub.data.ai.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolExecutionBatchPlannerTest {
    private val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant",
        callOrigin = ToolCallOrigin.LocalChat,
    )
    private val planner = ToolExecutionBatchPlanner(DefaultToolExecutionPolicyResolver())

    @Test
    fun `feature off preserves exact serial order`() {
        val planned = planner.plan(
            candidates = listOf(read("a", "/a"), read("b", "/b")),
            enabled = false,
            maxParallelism = 3,
        )

        assertEquals(listOf(listOf("a"), listOf("b")), planned.map { batch ->
            batch.candidates.map(ToolBatchCandidate::toolCallId)
        })
        assertTrue(planned.none(PlannedToolBatch::parallel))
    }

    @Test
    fun `only consecutive safe reads with disjoint resources form a batch`() {
        val planned = planner.plan(
            candidates = listOf(
                read("a", "/a"),
                read("b", "/b"),
                write("c", "/c"),
                read("d", "/d"),
                read("e", "/e"),
            ),
            enabled = true,
            maxParallelism = 3,
        )

        assertEquals(
            listOf(listOf("a", "b"), listOf("c"), listOf("d", "e")),
            planned.map { batch -> batch.candidates.map(ToolBatchCandidate::toolCallId) },
        )
        assertEquals(listOf(true, false, true), planned.map(PlannedToolBatch::parallel))
    }

    @Test
    fun `same resource download and plugin calls create serial barriers`() {
        val candidates = listOf(
            read("same-1", "/same"),
            read("same-2", "/same"),
            ToolBatchCandidate(
                index = 2,
                toolCallId = "download",
                toolName = "download_file",
                args = buildJsonObject { put("path", "/out") },
                context = context,
            ),
            ToolBatchCandidate(
                index = 3,
                toolCallId = "plugin",
                toolName = "plugin__0123456789ab__read_state",
                args = buildJsonObject {},
                context = context,
            ),
        )

        val planned = planner.plan(candidates, enabled = true, maxParallelism = 3)

        assertEquals(
            listOf(listOf("same-1"), listOf("same-2"), listOf("download"), listOf("plugin")),
            planned.map { batch -> batch.candidates.map(ToolBatchCandidate::toolCallId) },
        )
        assertFalse(planned.any(PlannedToolBatch::parallel))
    }

    @Test
    fun `batch size is capped without reordering`() {
        val planned = planner.plan(
            candidates = (0 until 7).map { read("r$it", "/$it") },
            enabled = true,
            maxParallelism = 3,
        )

        assertEquals(listOf(3, 3, 1), planned.map { it.candidates.size })
        assertEquals((0 until 7).map { "r$it" }, planned.flatMap { batch ->
            batch.candidates.map(ToolBatchCandidate::toolCallId)
        })
    }

    private fun read(id: String, path: String) = ToolBatchCandidate(
        index = id.hashCode(),
        toolCallId = id,
        toolName = "read_file",
        args = buildJsonObject { put("path", path) },
        context = context,
    )

    private fun write(id: String, path: String) = ToolBatchCandidate(
        index = id.hashCode(),
        toolCallId = id,
        toolName = "write_text_file",
        args = buildJsonObject { put("path", path) },
        context = context,
    )
}
