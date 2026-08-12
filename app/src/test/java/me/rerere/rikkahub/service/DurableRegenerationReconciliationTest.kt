package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.MemorySourceVersion
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.RegeneratePolicy
import me.rerere.rikkahub.service.chat.RunOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DurableRegenerationReconciliationTest {
    @Test
    fun `normal and interrupt commands expose one normalized content-bound baseline`() {
        val regeneration = regeneration(
            scopeId = " assistant-a ",
            messageIds = listOf(" message-b ", "message-a", "message-b", ""),
            sourceVersions = listOf(
                MemorySourceVersion("message-b", "B".repeat(64)),
                MemorySourceVersion(" message-a ", "a".repeat(64)),
                MemorySourceVersion("message-c", "invalid"),
            ),
        )
        val expected = DurableRegenerationBaseline(
            assistantScopeId = "assistant-a",
            selectedMessageIds = listOf("message-a", "message-b"),
            selectedSourceVersions = listOf(
                MemorySourceVersion("message-a", "a".repeat(64)),
                MemorySourceVersion("message-b", "b".repeat(64)),
            ),
        )

        assertEquals(expected, regeneration.durableRegenerationBaselineOrNull())
        assertEquals(
            expected,
            InterruptRegenerateCommand(regeneration).durableRegenerationBaselineOrNull(),
        )
        assertNull(
            regeneration.copy(
                baselineSelectedMessageIds = emptyList(),
                baselineSelectedSourceVersions = emptyList(),
            ).durableRegenerationBaselineOrNull(),
        )
    }

    @Test
    fun `legacy id-only baseline remains usable without inventing content versions`() {
        val baseline = regeneration(
            scopeId = "assistant-a",
            messageIds = listOf("message-a"),
        ).durableRegenerationBaselineOrNull()

        assertEquals(listOf("message-a"), baseline?.selectedMessageIds)
        assertTrue(baseline?.selectedSourceVersions.orEmpty().isEmpty())
    }

    @Test
    fun `failed regeneration restores transient graph while success keeps final graph`() = runBlocking {
        var failedRestoreCalled = false
        val failure = runRegenerationTransaction(
            restore = { failedRestoreCalled = true },
            operation = { RunOutcome.Failed(IllegalStateException("provider failed")) },
        )
        assertTrue(failure is RunOutcome.Failed)
        assertTrue(failedRestoreCalled)

        var successfulRestoreCalled = false
        val success = runRegenerationTransaction(
            restore = { successfulRestoreCalled = true },
            operation = { RunOutcome.Completed() },
        )
        assertTrue(success is RunOutcome.Completed)
        assertFalse(successfulRestoreCalled)
    }

    private fun regeneration(
        scopeId: String,
        messageIds: List<String>,
        sourceVersions: List<MemorySourceVersion> = emptyList(),
    ) = RegenerateCommand(
        targetMessageId = Uuid.random(),
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = Uuid.random(),
        policy = RegeneratePolicy.REJECT_IF_BUSY,
        baselineAssistantScopeId = scopeId,
        baselineSelectedMessageIds = messageIds,
        baselineSelectedSourceVersions = sourceVersions,
    )
}
