package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.memory.MemoryAttribution
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryQueryRecord
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryNarrativeNamesTest {
    @Test
    fun `memory attribution and participants use configured names rather than internal roles`() {
        val names = Assistant(
            name = "角色乙",
            memoryNarrativeUserName = "角色甲",
        ).memoryNarrativeNames(
            defaultSelfName = "你",
            defaultCompanionName = "对话对象",
            sharedNameFormat = "%1\$s 与 %2\$s",
        )

        assertEquals("角色甲", names.attributionName(MemoryAttribution.USER.name))
        assertEquals("角色乙", names.attributionName(MemoryAttribution.ASSISTANT.name))
        assertEquals("角色甲 与 角色乙", names.attributionName(MemoryAttribution.SHARED.name))
        val participants = names.participantsName(
            JsonInstant.encodeToString(listOf("USER", "ASSISTANT")),
        ).orEmpty()
        assertEquals("角色甲 · 角色乙", participants)
        assertFalse(participants.contains("USER"))
        assertFalse(participants.contains("ASSISTANT"))
        assertEquals(
            null,
            names.participantsName(JsonInstant.encodeToString(listOf("用户", "助手", "forged"))),
        )
    }

    @Test
    fun `global records resolve names from their originating conversation partner`() {
        val first = Assistant(
            name = "角色乙",
            memoryNarrativeUserName = "角色甲",
        )
        val second = Assistant(
            name = "另一位",
            memoryNarrativeUserName = "另一个名字",
        )

        val names = listOf(first, second).memoryNarrativeNamesFor(
            originAssistantId = first.id.toString(),
            fallbackAssistant = second,
            defaultSelfName = "你",
            defaultCompanionName = "对话对象",
            sharedNameFormat = "%1\$s 与 %2\$s",
        )

        assertEquals("角色甲", names.selfName)
        assertEquals("角色乙", names.companionName)
    }

    @Test
    fun `deleted global origin uses neutral names instead of another conversation partner`() {
        val visibleAssistant = Assistant(
            name = "另一位",
            memoryNarrativeUserName = "另一个名字",
        )

        val names = listOf(visibleAssistant).memoryNarrativeNamesFor(
            originAssistantId = "deleted-assistant",
            fallbackAssistant = visibleAssistant,
            defaultSelfName = "你",
            defaultCompanionName = "对话对象",
        )

        assertEquals("你", names.selfName)
        assertEquals("对话对象", names.companionName)
    }

    @Test
    fun `recall test uses the originating names for a global memory`() {
        val first = Assistant(
            name = "角色乙",
            memoryNarrativeUserName = "角色甲",
        )
        val second = Assistant(
            name = "另一位",
            memoryNarrativeUserName = "另一个名字",
        )
        val result = MemoryQueryRecord(
            id = 1,
            title = "用户与助手的项目",
            content = "用户和助手一起完成了记忆库修复。",
            kind = MemoryKind.EPISODE,
            tags = listOf("用户", "助手"),
            sourceType = "AUTO",
            updatedAtMs = 0,
            importance = 0.8f,
            score = 1.0,
            matchedTerms = listOf("用户", "助手"),
            reason = "用户与助手都确认了结果",
            originAssistantId = first.id.toString(),
        )

        val readable = result.readableFor { originAssistantId ->
            listOf(first, second).memoryNarrativeNamesFor(
                originAssistantId = originAssistantId,
                fallbackAssistant = second,
                defaultSelfName = "你",
                defaultCompanionName = "对话对象",
            )
        }

        assertEquals("角色甲与角色乙的项目", readable.title)
        assertEquals("角色甲和角色乙一起完成了记忆库修复。", readable.content)
        assertEquals(listOf("角色甲", "角色乙"), readable.tags)
        assertEquals(listOf("角色甲", "角色乙"), readable.matchedTerms)
        assertEquals("角色甲与角色乙都确认了结果", readable.reason)
    }
}
