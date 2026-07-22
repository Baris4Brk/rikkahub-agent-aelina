package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTurnResetGateTest {
    @Test
    fun `reset discards older queued turns but preserves later messages`() {
        val gate = TelegramTurnResetGate()

        gate.markReset(chatId = 7L, messageId = 20L)

        assertFalse(gate.mayProcess(chatId = 7L, messageId = 19L))
        assertFalse(gate.mayProcess(chatId = 7L, messageId = 20L))
        assertTrue(gate.mayProcess(chatId = 7L, messageId = 21L))
        assertTrue(gate.mayProcess(chatId = 8L, messageId = 1L))
    }

    @Test
    fun `late delivery of an older reset cannot move the watermark backwards`() {
        val gate = TelegramTurnResetGate()

        gate.markReset(chatId = 7L, messageId = 30L)
        gate.markReset(chatId = 7L, messageId = 20L)

        assertFalse(gate.mayProcess(chatId = 7L, messageId = 25L))
        assertTrue(gate.mayProcess(chatId = 7L, messageId = 31L))
    }

    @Test
    fun `command cancellation never kills a later turn that started first`() {
        assertTrue(telegramCommandMayCancelTurn(activeMessageId = 10L, commandMessageId = 11L))
        assertFalse(telegramCommandMayCancelTurn(activeMessageId = 12L, commandMessageId = 11L))
    }
}
