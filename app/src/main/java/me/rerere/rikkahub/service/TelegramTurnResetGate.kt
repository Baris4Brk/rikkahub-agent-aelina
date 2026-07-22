package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/**
 * Orders reset commands and ordinary Telegram turns by Telegram's per-chat message id.
 * Coroutines may start out of order even though updates were received in order, so relying on
 * mutex acquisition order alone can let a pre-/new message leak into the fresh conversation.
 */
internal class TelegramTurnResetGate {
    private val resetMessageIds = ConcurrentHashMap<Long, Long>()

    fun markReset(chatId: Long, messageId: Long) {
        resetMessageIds.merge(chatId, messageId, ::maxOf)
    }

    fun mayProcess(chatId: Long, messageId: Long): Boolean =
        messageId > (resetMessageIds[chatId] ?: Long.MIN_VALUE)
}

internal data class TelegramTurnRegistration(
    val messageId: Long,
    val job: Job,
)

internal fun telegramCommandMayCancelTurn(
    activeMessageId: Long,
    commandMessageId: Long,
): Boolean = activeMessageId <= commandMessageId
