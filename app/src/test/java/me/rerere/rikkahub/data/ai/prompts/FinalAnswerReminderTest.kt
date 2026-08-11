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
    fun `legacy chinese name forcing default is upgraded`() {
        val legacy = DEFAULT_FINAL_ANSWER_REMINDER_PROMPT
            .replace(
                "必须沿用当前用户正在使用的语言。只有在自然直接称呼用户时才使用本对话已有的称呼；\n称呼不得单独作为回答、不得重复，也不得代替对用户请求的实际回答。不得把内部角色标签\nUSER、user、ASSISTANT、assistant 或它们的残缺形式（例如 urse）当作用户姓名或称呼。",
                "必须沿用当前用户正在使用的语言以及本对话已有的称呼。不得把内部角色标签 USER、user、\nASSISTANT、assistant 或它们的残缺形式（例如 urse）当作用户姓名或称呼。",
            )

        assertEquals(DEFAULT_FINAL_ANSWER_REMINDER_PROMPT, resolveFinalAnswerReminderPrompt(legacy))
    }

    @Test
    fun `blank prompt falls back but a custom prompt is preserved`() {
        assertEquals(DEFAULT_FINAL_ANSWER_REMINDER_PROMPT, resolveFinalAnswerReminderPrompt("  "))

        val custom = "请只给出最后结论，并继续称呼我为示例昵称。"
        assertEquals(custom, resolveFinalAnswerReminderPrompt(custom))
    }
}
