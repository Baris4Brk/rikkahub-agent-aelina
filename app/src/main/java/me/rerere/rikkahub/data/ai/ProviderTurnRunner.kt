package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import me.rerere.ai.ui.MessageChunk
import java.util.concurrent.atomic.AtomicBoolean

data class ProviderTurnRequest(
    val stream: Boolean,
    val streamCall: suspend () -> Flow<MessageChunk>,
    val singleCall: suspend () -> MessageChunk,
    val onChunk: suspend (MessageChunk) -> Unit,
)

sealed interface ProviderTurnOutcome {
    data object Completed : ProviderTurnOutcome
    data object CancelledForSteering : ProviderTurnOutcome
}

interface ProviderTurnRunner {
    suspend fun run(request: ProviderTurnRequest): ProviderTurnOutcome
}

class DefaultProviderTurnRunner(
    private val runControl: GenerationRunControl?,
) : ProviderTurnRunner {
    override suspend fun run(request: ProviderTurnRequest): ProviderTurnOutcome = supervisorScope {
        val steeringCancellationRequested = AtomicBoolean(false)
        // Provider work must remain a cancellable child, while callbacks must run in this scoped
        // caller coroutine. GenerationHandler's callback emits from a `flow {}` collector, and
        // invoking it directly inside the Deferred violates Flow's single-coroutine invariant.
        val chunks = Channel<MessageChunk>(capacity = Channel.RENDEZVOUS)
        val providerChild = async {
            try {
                if (request.stream) {
                    request.streamCall().collect(chunks::send)
                } else {
                    chunks.send(request.singleCall())
                }
            } catch (error: Throwable) {
                chunks.close(error)
                throw error
            } finally {
                chunks.close()
            }
        }
        val registration = runControl?.registerProviderCancel {
            steeringCancellationRequested.set(true)
            providerChild.cancel(CancellationException("Provider cancellation requested by steering"))
        }
        try {
            for (chunk in chunks) {
                request.onChunk(chunk)
            }
            providerChild.await()
            ProviderTurnOutcome.Completed
        } catch (cancelled: CancellationException) {
            if (!steeringCancellationRequested.get() ||
                !currentCoroutineContext().isActive ||
                runControl?.isRunCancellationRequested() == true
            ) throw cancelled
            ProviderTurnOutcome.CancelledForSteering
        } finally {
            registration?.close()
            chunks.cancel()
        }
    }
}
