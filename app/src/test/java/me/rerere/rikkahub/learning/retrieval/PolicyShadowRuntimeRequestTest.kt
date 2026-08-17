package me.rerere.rikkahub.learning.retrieval

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.exposure.PolicyLearningCommandContext
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PolicyShadowRuntimeRequestTest {
    private val assistant = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val command = PolicyLearningCommandContext(
        scope = LearningScope.Assistant(assistant),
        consumingAssistantId = assistant,
        lineageId = Uuid.parse("00000000-0000-4000-8000-000000000002"),
        branchAnchorMessageId = Uuid.parse("00000000-0000-4000-8000-000000000003"),
        branchAnchorMessageRevision = 7L,
        logicalRunId = Uuid.parse("00000000-0000-4000-8000-000000000004"),
    )
    private val task = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )

    @Test
    fun `same command request identity survives retry without query content`() {
        val first = PolicyShadowRuntimeRequest.forCommand(command, task, "第一段正文")
        val retry = PolicyShadowRuntimeRequest.forCommand(command, task, "不同正文也不得持久化")

        assertEquals(first.requestIdentity, retry.requestIdentity)
        assertFalse(first.toString().contains("第一段正文"))
        assertFalse(first.requestIdentity.contains("第一段正文"))
    }

    @Test
    fun `logical request identity fences run scope and task`() {
        val baseline = PolicyShadowRuntimeRequest.forCommand(command, task, "query")
        val otherRun = PolicyShadowRuntimeRequest.forCommand(
            command.copy(logicalRunId = Uuid.parse("00000000-0000-4000-8000-000000000005")),
            task,
            "query",
        )
        assertNotEquals(baseline.requestIdentity, otherRun.requestIdentity)
        assertNotEquals(
            baseline.requestIdentity,
            PolicyShadowRuntimeRequest.forCommand(
                command.copy(branchAnchorMessageRevision = 8L),
                task,
                "query",
            ).requestIdentity,
        )
    }

    @Test
    fun `unreviewed runtime gate identity is rejected`() {
        val request = PolicyShadowRuntimeRequest.forCommand(command, task, "query")
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(admissionGateIdentity = "runtime-score-gate-v1")
        }
    }
}
