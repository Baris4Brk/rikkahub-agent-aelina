package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputActionPolicyTest {
    @Test
    fun `loading input sends guidance while empty input stops generation`() {
        assertEquals(
            ChatInputPrimaryAction.SEND,
            resolveChatInputPrimaryAction(loading = true, inputIsEmpty = false),
        )
        assertEquals(
            ChatInputPrimaryAction.STOP,
            resolveChatInputPrimaryAction(loading = true, inputIsEmpty = true),
        )
        assertEquals(
            ChatInputPrimaryAction.SEND,
            resolveChatInputPrimaryAction(loading = false, inputIsEmpty = false),
        )
    }
}
