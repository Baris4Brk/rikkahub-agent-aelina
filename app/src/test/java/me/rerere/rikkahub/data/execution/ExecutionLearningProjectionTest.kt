package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ExecutionLearningProjectionTest {
    @Test
    fun `all terminal statuses have explicit stable learning codes`() {
        assertEquals("SUCCEEDED", ExecutionStatus.succeeded.toLearningTerminalCode())
        assertEquals("FAILED", ExecutionStatus.failed.toLearningTerminalCode())
        assertEquals("CANCELLED", ExecutionStatus.cancelled.toLearningTerminalCode())
        assertEquals("TIMED_OUT", ExecutionStatus.timed_out.toLearningTerminalCode())
        assertEquals("ORPHANED", ExecutionStatus.orphaned.toLearningTerminalCode())
        assertEquals("UNKNOWN", ExecutionStatus.unknown.toLearningTerminalCode())
    }

    @Test
    fun `execution event source id is bounded deterministic and domain separated`() {
        val first = LearningCanonicalId.executionEventSourceId("mutation:terminal:one")
        val replay = LearningCanonicalId.executionEventSourceId("mutation:terminal:one")
        val other = LearningCanonicalId.executionEventSourceId("mutation:terminal:two")

        assertEquals(first, replay)
        assertNotEquals(first, other)
        assertEquals(true, first.startsWith("execution-event-v1:"))
        assertEquals(true, first.length <= 256)
    }

    @Test
    fun `only frozen execution columns restore learning scope`() {
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val base = record(
            learningScopeKind = LearningScope.Assistant(assistantId).kind.name,
            learningScopeId = assistantId.toString(),
        )

        assertEquals(LearningScope.Assistant(assistantId), base.learningScopeOrNull())
        assertNull(base.copy(learningScopeKind = null, learningScopeId = null).learningScopeOrNull())
        assertNull(base.copy(learningScopeKind = "GLOBAL").learningScopeOrNull())
        assertNull(base.copy(learningScopeId = "not-a-uuid").learningScopeOrNull())
    }

    private fun record(
        learningScopeKind: String?,
        learningScopeId: String?,
    ) = ExecutionRecord(
        id = "execution",
        traceId = "run",
        learningScopeKind = learningScopeKind,
        learningScopeId = learningScopeId,
        subjectId = "subject",
        subjectType = "LOCAL_ASSISTANT",
        origin = "LocalChat",
        capabilityKeys = "tool.run",
        resourceSummary = "tool",
        runtime = ExecutionRuntime.LOCAL_TOOL.name,
        status = ExecutionStatus.running.name,
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )
}
