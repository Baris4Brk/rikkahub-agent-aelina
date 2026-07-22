package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBuiltInCommandsTest {
    @Test
    fun `telegram menu exposes steering and interrupt commands exactly once`() {
        val commands = TelegramBotService.BUILT_IN_COMMANDS.map { it.first }

        assertEquals(commands.distinct(), commands)
        assertEquals(1, commands.count { it == "steer" })
        assertEquals(1, commands.count { it == "interrupt" })
    }

    @Test
    fun `steering command descriptions explain their cancellation behavior`() {
        val descriptions = TelegramBotService.BUILT_IN_COMMANDS.toMap()

        assertTrue(descriptions.getValue("steer").contains("without stopping", ignoreCase = true))
        assertTrue(descriptions.getValue("interrupt").contains("stop", ignoreCase = true))
    }
}
