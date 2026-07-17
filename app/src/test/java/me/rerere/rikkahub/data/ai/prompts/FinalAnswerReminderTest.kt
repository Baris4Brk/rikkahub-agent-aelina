package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAnswerReminderTest {
    @Test
    fun `legacy english default is upgraded to chinese reminder`() {
        val resolved = resolveFinalAnswerReminderPrompt(
            LEGACY_ENGLISH_FINAL_ANSWER_REMINDER_PROMPT,
        )

        assertEquals(DEFAULT_FINAL_ANSWER_REMINDER_PROMPT, resolved)
        assertTrue(resolved.contains("沿用当前用户正在使用的语言"))
        assertTrue(resolved.contains("不得把内部角色标签"))
        assertFalse(resolved.startsWith("Continue the same assistant turn"))
    }

    @Test
    fun `blank prompt falls back but a custom prompt is preserved`() {
        assertEquals(DEFAULT_FINAL_ANSWER_REMINDER_PROMPT, resolveFinalAnswerReminderPrompt("  "))

        val custom = "请只给出最后结论，并继续称呼我为斯啾伊。"
        assertEquals(custom, resolveFinalAnswerReminderPrompt(custom))
    }
}
