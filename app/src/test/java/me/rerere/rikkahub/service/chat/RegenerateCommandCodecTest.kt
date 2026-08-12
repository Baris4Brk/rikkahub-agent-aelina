package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.memory.MemorySourceVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RegenerateCommandCodecTest {
    @Test
    fun `durable regenerate preserves normalized source baseline`() {
        val command = RegenerateCommand(
            targetMessageId = Uuid.parse("10000000-0000-0000-0000-000000000001"),
            expectedTargetVersion = 7L,
            expectedBranchHeadMessageId =
                Uuid.parse("10000000-0000-0000-0000-000000000002"),
            baselineAssistantScopeId = " assistant-scope ",
            baselineSelectedMessageIds = listOf(" message-b ", "message-a", "message-a", ""),
            baselineSelectedSourceVersions = listOf(
                MemorySourceVersion(" message-b ", "B".repeat(64)),
                MemorySourceVersion("message-a", "a".repeat(64)),
                MemorySourceVersion("message-a", "not-a-digest"),
            ),
        )

        val (type, payload) = CommandCodec.encodeDurable(command, CommandOrigin.APP_UI)
        val decoded = CommandCodec.decode(type, payload) as RegenerateCommand

        assertEquals("assistant-scope", decoded.baselineAssistantScopeId)
        assertEquals(listOf("message-a", "message-b"), decoded.baselineSelectedMessageIds)
        assertEquals(
            listOf(
                MemorySourceVersion("message-a", "a".repeat(64)),
                MemorySourceVersion("message-b", "b".repeat(64)),
            ),
            decoded.baselineSelectedSourceVersions,
        )
        assertEquals(CommandOrigin.APP_UI, CommandCodec.decodeDurableOrigin(payload))
    }

    @Test
    fun `interrupt regenerate preserves source baseline`() {
        val regeneration = RegenerateCommand(
            targetMessageId = Uuid.parse("20000000-0000-0000-0000-000000000001"),
            expectedTargetVersion = 8L,
            expectedBranchHeadMessageId =
                Uuid.parse("20000000-0000-0000-0000-000000000002"),
            baselineAssistantScopeId = "scope-2",
            baselineSelectedMessageIds = listOf("message-1", "message-2"),
            baselineSelectedSourceVersions = listOf(
                MemorySourceVersion("message-1", "1".repeat(64)),
                MemorySourceVersion("message-2", "2".repeat(64)),
            ),
        )

        val encoded = CommandCodec.encode(InterruptRegenerateCommand(regeneration))
        val decoded = CommandCodec.decode(encoded.first, encoded.second) as InterruptRegenerateCommand

        assertEquals(regeneration, decoded.regeneration)
    }

    @Test
    fun `legacy regenerate payload remains decodable without a baseline`() {
        val decoded = CommandCodec.decode(
            type = "regenerate",
            payload = """
                {
                  "targetMessageId":"30000000-0000-0000-0000-000000000001",
                  "expectedTargetVersion":9,
                  "expectedBranchHeadMessageId":"30000000-0000-0000-0000-000000000002",
                  "policy":"INTERRUPT_CURRENT"
                }
            """.trimIndent(),
        ) as RegenerateCommand

        assertNull(decoded.baselineAssistantScopeId)
        assertEquals(emptyList<String>(), decoded.baselineSelectedMessageIds)
        assertEquals(emptyList<MemorySourceVersion>(), decoded.baselineSelectedSourceVersions)
    }

    @Test
    fun `id-only durable baseline remains backward compatible without guessing a digest`() {
        val decoded = CommandCodec.decode(
            type = "regenerate",
            payload = """
                {
                  "targetMessageId":"40000000-0000-0000-0000-000000000001",
                  "expectedTargetVersion":10,
                  "expectedBranchHeadMessageId":"40000000-0000-0000-0000-000000000002",
                  "policy":"INTERRUPT_CURRENT",
                  "baselineAssistantScopeId":"assistant-a",
                  "baselineSelectedMessageIds":["message-a"]
                }
            """.trimIndent(),
        ) as RegenerateCommand

        assertEquals(listOf("message-a"), decoded.baselineSelectedMessageIds)
        assertTrue(decoded.baselineSelectedSourceVersions.isEmpty())
    }
}
