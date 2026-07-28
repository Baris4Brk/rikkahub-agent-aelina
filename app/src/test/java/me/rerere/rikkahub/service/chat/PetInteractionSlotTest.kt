package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PetInteractionSlotTest {
    @Test
    fun `pet slot is single and ordinary command preempts it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(
            appScope = scope,
            conversationId = Uuid.random(),
            dispatchers = DispatcherProvider(
                runtime = Dispatchers.Default,
                io = Dispatchers.Default,
                main = Dispatchers.Default,
            ),
            executor = RuntimeCommandExecutor { _, _ -> RunOutcome.Completed() },
        )
        withTimeout(5_000) { runtime.runtimeState.first { it == RuntimeState.Idle } }
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val first = async {
            runtime.runPetInteraction {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()

        assertTrue(runtime.runPetInteraction { Unit } is PetInteractionSlotResult.Busy)

        runtime.enqueueEnvelope(
            CommandEnvelope(
                conversationId = runtime.conversationId,
                command = SendMessageCommand(
                    RawUserContent(listOf(UIMessagePart.Text("normal command"))),
                ),
                origin = CommandOrigin.APP_UI,
                sequence = 1,
            ),
        )
        withTimeout(5_000) { runCatching { first.await() } }
        assertTrue(first.isCancelled)
        runtime.close()
        scope.cancel()
    }
}
