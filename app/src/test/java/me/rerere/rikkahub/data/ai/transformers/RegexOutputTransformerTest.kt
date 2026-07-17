package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.replaceRegexes
import org.junit.Assert.assertEquals
import org.junit.Test

class RegexOutputTransformerTest {
    @Test
    fun `persistent regex is applied once to formal output`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = "secret",
                    replaceString = "[redacted]",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )

        assertEquals(
            "[redacted]",
            "secret".replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false),
        )
    }

    @Test
    fun `visual-only regex does not alter persisted output`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = "secret",
                    replaceString = "[hidden]",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                ),
            ),
        )

        assertEquals(
            "secret",
            "secret".replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false),
        )
    }

    @Test
    fun `non-idempotent replacements are expected to run only once per message`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = "a",
                    replaceString = "aa",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )

        val once = "a".replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false)
        assertEquals("aa", once)
        // A second application would be "aaaa"; the latest-message pipeline must not do that
        // to an already persisted history item.
        assertEquals("aaaa", once.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false))
    }

    @Test
    fun `invalid pattern and missing replacement group preserve the current text`() {
        val invalidPattern = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = "[",
                    replaceString = "hidden",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )
        val invalidGroup = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = "(answer)",
                    replaceString = "\$2",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )

        assertEquals(
            "answer",
            "answer".replaceRegexes(invalidPattern, AssistantAffectScope.ASSISTANT),
        )
        assertEquals(
            "answer",
            "answer".replaceRegexes(invalidGroup, AssistantAffectScope.ASSISTANT),
        )
    }
}
