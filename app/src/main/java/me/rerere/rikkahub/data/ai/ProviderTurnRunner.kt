package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
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
    override suspend fun run(request: ProviderTurnRequest): ProviderTurnOutcome = coroutineScope {
        val steeringCancellationRequested = AtomicBoolean(false)
        val providerChild = async {
            if (request.stream) {
                request.streamCall().collect(request.onChunk)
            } else {
                request.onChunk(request.singleCall())
            }
        }
        val registration = runControl?.registerProviderCancel {
            steeringCancellationRequested.set(true)
            providerChild.cancel(CancellationException("Provider cancellation requested by steering"))
        }
        try {
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
        }
    }
}
