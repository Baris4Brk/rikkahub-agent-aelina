package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StringUtilsBase64Test {
    @Test
    fun `encoded quick capture draft round trips`() {
        val draft = "悬浮窗草稿？with punctuation & emoji 😀"

        assertEquals(draft, draft.base64Encode().base64DecodeOrOriginal())
    }

    @Test
    fun `legacy raw quick capture draft is preserved`() {
        val legacyDraft = "?未编码的悬浮窗草稿"

        assertEquals(legacyDraft, legacyDraft.base64DecodeOrOriginal())
    }

    @Test
    fun `empty draft remains empty`() {
        assertEquals("", "".base64DecodeOrOriginal())
    }
}
