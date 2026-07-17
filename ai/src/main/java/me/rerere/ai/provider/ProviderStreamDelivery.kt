package me.rerere.ai.provider

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer

/**
 * Lossless hand-off between OkHttp/SSE callbacks and a potentially slow Flow collector.
 *
 * The failure message intentionally contains neither the chunk nor its serialized size because
 * both can reveal prompts, tool arguments, provider reasoning, or answer text.
 */
internal fun <T> SendChannel<T>.deliverProviderChunk(
    provider: String,
    value: T,
    logFailure: (String) -> Unit,
): Boolean {
    val result = trySend(value)
    if (result.isSuccess) return true

    val channelState = if (result.isClosed) "closed" else "backpressured"
    val failureType = result.exceptionOrNull()?.javaClass?.simpleName ?: "none"
    logFailure(
        "provider stream delivery rejected: provider=$provider, " +
            "channel=$channelState, failureType=$failureType",
    )
    if (!result.isClosed) {
        close(ProviderStreamDeliveryException(provider))
    }
    return false
}

internal class ProviderStreamDeliveryException(provider: String) :
    IllegalStateException("Provider stream delivery failed for $provider")

/**
 * `callbackFlow` normally has a small bounded channel. SSE callbacks cannot suspend, so a burst
 * larger than that channel would make `trySend` fail and silently drop chunks. Flow/channel fusion
 * applies this unlimited capacity directly to the callbackFlow producer.
 */
internal fun <T> Flow<T>.bufferProviderStream(): Flow<T> = buffer(Channel.UNLIMITED)
