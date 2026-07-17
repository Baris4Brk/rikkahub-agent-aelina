package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class FastPathDecisionTest {
    private val text = UIMessagePart.Text("hello")

    @Test
    fun `router contract preserves handled response`() = runBlocking {
        val router = FastPathRouter { FastPathDecision.Handled(listOf(text)) }
        assertEquals(FastPathDecision.Handled(listOf(text)), router.resolve(fakeContext()))
    }

    @Test
    fun `router contract preserves transformed model content`() = runBlocking {
        val router = FastPathRouter { FastPathDecision.ContinueToModel(listOf(text)) }
        assertEquals(FastPathDecision.ContinueToModel(listOf(text)), router.resolve(fakeContext()))
    }

    @Test
    fun `router contract preserves not matched and rejected branches`() = runBlocking {
        val notMatched = FastPathRouter { FastPathDecision.NotMatched }
        val rejected = FastPathRouter { FastPathDecision.Rejected("blocked") }
        assertEquals(FastPathDecision.NotMatched, notMatched.resolve(fakeContext()))
        assertEquals(FastPathDecision.Rejected("blocked"), rejected.resolve(fakeContext()))
    }

    private fun fakeContext(): FastPathContext = FastPathContext(
        commandId = Uuid.random(),
        conversation = me.rerere.rikkahub.data.model.Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        ),
        content = listOf(text),
        origin = CommandOrigin.APP_UI,
        assistant = me.rerere.rikkahub.data.model.Assistant(),
    )
    @Test
    fun `commit plan writes handled user and assistant exactly once`() {
        val processed = listOf(UIMessagePart.Text("input"))
        val response = listOf(UIMessagePart.Text("reply"))
        assertEquals(
            FastPathCommitPlan.Handled(processed, response),
            buildFastPathCommitPlan(processed, FastPathDecision.Handled(response)),
        )
    }

    @Test
    fun `commit plan writes transformed content exactly once before model`() {
        val processed = listOf(UIMessagePart.Text("raw"))
        val transformed = listOf(UIMessagePart.Text("normalized"))
        assertEquals(
            FastPathCommitPlan.ContinueToModel(transformed),
            buildFastPathCommitPlan(processed, FastPathDecision.ContinueToModel(transformed)),
        )
    }

    @Test
    fun `commit plan rejects without a partial user or assistant write`() {
        val plan = buildFastPathCommitPlan(
            processedContent = listOf(UIMessagePart.Text("blocked")),
            decision = FastPathDecision.Rejected("not allowed"),
        )
        assertEquals(FastPathCommitPlan.Rejected("not allowed"), plan)
    }
}
