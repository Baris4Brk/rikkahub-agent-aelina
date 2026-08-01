package me.rerere.rikkahub.data.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotConfigTest {
    @Test
    fun `vault slot is a usable credential without persisted plaintext`() {
        val config = TelegramBotConfig(
            token = "",
            vaultSlotId = "telegram-primary",
            enabled = true,
        )

        assertTrue(config.hasCredential)
        assertTrue(config.isUsable)
    }

    @Test
    fun `disabled channel stays unusable even when credential exists`() {
        val config = TelegramBotConfig(vaultSlotId = "telegram-primary", enabled = false)

        assertTrue(config.hasCredential)
        assertFalse(config.isUsable)
    }

    @Test
    fun `blank legacy and vault credentials fail closed`() {
        val config = TelegramBotConfig(token = "", vaultSlotId = " ", enabled = true)

        assertFalse(config.hasCredential)
        assertFalse(config.isUsable)
    }
}
