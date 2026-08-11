package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
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

    @Test
    fun `stream with no meaningful progress is retried once`() = runBlocking {
        var initialCalls = 0
        var retryCalls = 0
        var rollbacks = 0
        val delivered = mutableListOf<MessageChunk>()
        val recovered = textChunk("retry", "recovered response")

        val outcome = DefaultProviderTurnRunner(runControl = null).run(
            ProviderTurnRequest(
                stream = true,
                streamCall = {
                    initialCalls += 1
                    flow { awaitCancellation() }
                },
                retryStreamCall = {
                    retryCalls += 1
                    flowOf(recovered)
                },
                singleCall = { error("single call must not run") },
                onChunk = { delivered.add(it) },
                onBeforeRetry = {
                    rollbacks += 1
                    delivered.clear()
                },
                watchdogConfig = testWatchdog(
                    firstProgressTimeoutMillis = 120L,
                ),
            ),
        )

        assertEquals(ProviderTurnOutcome.Completed, outcome)
        assertEquals(1, initialCalls)
        assertEquals(1, retryCalls)
        assertEquals(1, rollbacks)
        assertEquals(listOf(recovered), delivered)
    }

    @Test
    fun `slow partial stream is rolled back before retry output`() = runBlocking {
        val partial = textChunk("partial", "x")
        val recovered = textChunk("retry", "clean retry")
        val delivered = mutableListOf<MessageChunk>()

        val outcome = DefaultProviderTurnRunner(runControl = null).run(
            ProviderTurnRequest(
                stream = true,
                streamCall = {
                    flow {
                        emit(partial)
                        awaitCancellation()
                    }
                },
                retryStreamCall = { flowOf(recovered) },
                singleCall = { error("single call must not run") },
                onChunk = { delivered.add(it) },
                onBeforeRetry = { delivered.clear() },
                watchdogConfig = testWatchdog(),
            ),
        )

        assertEquals(ProviderTurnOutcome.Completed, outcome)
        assertEquals(listOf(recovered), delivered)
    }

    @Test
    fun `slow chunk consumer is not mistaken for a slow provider`() = runBlocking {
        var retryCalls = 0
        val delivered = mutableListOf<MessageChunk>()

        val outcome = DefaultProviderTurnRunner(runControl = null).run(
            ProviderTurnRequest(
                stream = true,
                streamCall = {
                    flow {
                        repeat(6) { index ->
                            emit(textChunk("healthy-$index", "xxxxxxxxxxxxxxxxxxxx"))
                            delay(10L)
                        }
                    }
                },
                retryStreamCall = {
                    retryCalls += 1
                    error("a healthy provider stream must not be retried")
                },
                singleCall = { error("single call must not run") },
                onChunk = { chunk ->
                    delivered.add(chunk)
                    delay(80L)
                },
                watchdogConfig = testWatchdog(),
            ),
        )

        assertEquals(ProviderTurnOutcome.Completed, outcome)
        assertEquals(0, retryCalls)
        assertEquals(6, delivered.size)
    }

    @Test
    fun `second slow stream fails instead of retrying forever`() = runBlocking {
        var calls = 0
        var rollbacks = 0
        val slowCall: suspend () -> kotlinx.coroutines.flow.Flow<MessageChunk> = {
            calls += 1
            flow {
                emit(textChunk("slow-$calls", "x"))
                awaitCancellation()
            }
        }

        val error = runCatching {
            DefaultProviderTurnRunner(runControl = null).run(
                ProviderTurnRequest(
                    stream = true,
                    streamCall = slowCall,
                    retryStreamCall = slowCall,
                    singleCall = { error("single call must not run") },
                    onChunk = {},
                    onBeforeRetry = { rollbacks += 1 },
                    watchdogConfig = testWatchdog(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is ProviderStreamStalledException)
        assertEquals(2, calls)
        assertEquals(1, rollbacks)
    }

    private fun testWatchdog(
        firstProgressTimeoutMillis: Long = 500L,
    ) = ProviderStreamWatchdogConfig(
        firstProgressTimeoutMillis = firstProgressTimeoutMillis,
        lowSpeedWindowMillis = 120L,
        checkIntervalMillis = 10L,
        minimumProgressUnitsPerWindow = 20L,
    )

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

    private fun textChunk(id: String, text: String) = MessageChunk(
        id = id,
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                ),
                message = null,
                finishReason = null,
            ),
        ),
    )
}
