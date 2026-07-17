package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderTurnRunnerTest {
    @Test
    fun `stream chunks are relayed back to the caller flow coroutine`() = runBlocking {
        val chunk = testChunk("chunk-1")

        val delivered = flow {
            val outcome = DefaultProviderTurnRunner(runControl = null).run(
                ProviderTurnRequest(
                    stream = true,
                    streamCall = { flowOf(chunk) },
                    singleCall = { error("single call must not run") },
                    // GenerationHandler emits its updated message snapshot here. Calling this
                    // callback from the provider Deferred violates Flow's single-coroutine rule.
                    onChunk = { emit(it) },
                ),
            )
            assertEquals(ProviderTurnOutcome.Completed, outcome)
        }.toList()

        assertEquals(listOf(chunk), delivered)
    }

    @Test
    fun `non stream result is relayed back to the caller flow coroutine`() = runBlocking {
        val chunk = testChunk("single-1")

        val delivered = flow {
            val outcome = DefaultProviderTurnRunner(runControl = null).run(
                ProviderTurnRequest(
                    stream = false,
                    streamCall = { error("stream call must not run") },
                    singleCall = { chunk },
                    onChunk = { emit(it) },
                ),
            )
            assertEquals(ProviderTurnOutcome.Completed, outcome)
        }.toList()

        assertEquals(listOf(chunk), delivered)
    }

    @Test
    fun `steering cancels only provider child and leaves active run coroutine alive`() = runBlocking {
        val control = GenerationRunControl(Uuid.random())
        val providerStarted = CompletableDeferred<Unit>()
        val turn = async {
            DefaultProviderTurnRunner(control).run(
                ProviderTurnRequest(
                    stream = true,
                    streamCall = {
                        flow<MessageChunk> {
                            providerStarted.complete(Unit)
                            awaitCancellation()
                        }
                    },
                    singleCall = { error("single call must not run") },
                    onChunk = { error("cancelled provider must not deliver a chunk") },
                )
            )
        }
        providerStarted.await()

        assertEquals(
            CancelRequestResult.Requested,
            control.requestProviderCancel(ToolCancelReason.STEERING_OVERRIDE),
        )
        assertEquals(ProviderTurnOutcome.CancelledForSteering, turn.await())
        assertTrue(isActive)
    }

    private fun testChunk(id: String) = MessageChunk(
        id = id,
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                ),
                message = null,
                finishReason = null,
            ),
        ),
    )
}
