package me.rerere.ai.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStreamDeliveryTest {
    @Test
    fun `fast producer reaches a slow consumer without losing order or terminal item`() = runBlocking {
        val expected = (0 until 4_096).toList() + TERMINAL

        val actual = callbackFlow {
            expected.forEach { item ->
                check(deliverProviderChunk(PROVIDER, item) {})
            }
            close()
        }.bufferProviderStream().toList()

        assertEquals(expected, actual)
    }

    @Test
    fun `delivery failure is redacted and never includes the rejected payload`() {
        val channel = Channel<String>(Channel.RENDEZVOUS)
        val logs = mutableListOf<String>()

        val accepted = channel.deliverProviderChunk(
            provider = PROVIDER,
            value = "private prompt and reasoning",
            logFailure = logs::add,
        )

        assertFalse(accepted)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(PROVIDER))
        assertFalse(logs.single().contains("private prompt"))
        channel.cancel()
    }

    @Test
    fun `collector cancellation still closes the underlying provider callback`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()

        callbackFlow {
            check(deliverProviderChunk(PROVIDER, 1) {})
            awaitClose { cancelled.complete(Unit) }
        }.bufferProviderStream().take(1).toList()

        cancelled.await()
        assertTrue(cancelled.isCompleted)
    }

    private companion object {
        const val PROVIDER = "test-provider"
        const val TERMINAL = -1
    }
}
