package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.MessageChunk
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderTurnRunnerTest {
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
}
