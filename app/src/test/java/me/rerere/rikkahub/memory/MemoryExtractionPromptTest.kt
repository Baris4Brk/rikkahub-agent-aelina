package me.rerere.rikkahub.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionPromptTest {
    @Test
    fun `prompt publishes the complete version two narrative contract`() {
        listOf(
            "\"version\": 2",
            "\"proposals\"",
            "\"action\"",
            "\"targetIds\"",
            "\"expectedRevisions\"",
            "\"title\"",
            "\"content\"",
            "\"kind\"",
            "\"tags\"",
            "\"importance\"",
            "\"confidence\"",
            "\"expiresAtMs\"",
            "\"evidenceMessageIds\"",
            "\"reason\"",
            "\"proposalKey\"",
            "\"attribution\"",
            "\"truthStatus\"",
            "\"participants\"",
            "\"outcome\"",
            "\"relations\"",
            "\"evidenceMessageIds\": [\"T1\"]",
            "{\"version\":2,\"proposals\":[],\"relations\":[]}",
        ).forEach { requiredFragment ->
            assertTrue(
                "Missing extraction contract fragment: $requiredFragment",
                MEMORY_EXTRACTION_SYSTEM_PROMPT.contains(requiredFragment),
            )
        }
    }

    @Test
    fun `compacted conversation payload keeps local message ids out of the provider request`() {
        val payload = memoryExtractionPayload(
            MemoryExtractionRequest(
                scopeId = "assistant-scope",
                assistantId = "assistant-id",
                conversationId = "conversation-id",
                turns = listOf(
                    MemoryExtractionTurn(
                        userMessageId = "private-user-message-id",
                        assistantMessageId = "private-assistant-message-id",
                        userText = "Short chronological excerpt",
                        assistantText = "Short answer excerpt",
                        evidenceRef = "T1",
                    ),
                ),
                existingMemories = emptyList(),
                evidenceRefToMessageId = mapOf("T1" to "private-user-message-id"),
                isConversationContextCompacted = true,
                narrativeIdentity = MemoryNarrativeIdentity(
                    selfName = "啥子七",
                    companionName = "斯啾伊",
                ),
            ),
        )

        assertTrue(payload.contains("\"evidenceRef\":\"T1\""))
        assertTrue(payload.contains("\"conversationContextCompacted\":true"))
        assertTrue(payload.contains("\"displayNames\":{\"self\":\"啥子七\",\"companion\":\"斯啾伊\"}"))
        assertTrue(payload.contains("\"selfText\":\"Short chronological excerpt\""))
        assertTrue(payload.contains("\"companionText\":\"Short answer excerpt\""))
        assertFalse(payload.contains("\"userText\""))
        assertFalse(payload.contains("\"assistantText\""))
        assertFalse(payload.contains("private-user-message-id"))
        assertFalse(payload.contains("private-assistant-message-id"))
    }

    @Test
    fun `thirty long turns and relevant memory stay inside the extraction payload budget`() {
        val captures = (1..30).map { index ->
            MemoryCaptureRecord(
                id = "capture-$index",
                assistantId = "assistant-id",
                scopeId = "assistant-scope",
                conversationId = "conversation-id",
                userMessageId = "user-message-$index",
                assistantMessageId = "assistant-message-$index",
                origin = MemoryCaptureOrigin.APP_UI,
                autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
                userText = "u$index-" + "user detail ".repeat(300),
                assistantText = "a$index-" + "assistant detail ".repeat(300),
                createdAtMs = index.toLong(),
            )
        }
        val prepared = MemoryExtractionInputComposer(MemoryContentGuard()).compose(captures)
        val existing = compactExistingMemoriesForExtraction(
            (1..4).map { index ->
                ExistingMemoryRecord(
                    id = index,
                    scopeId = "assistant-scope",
                    title = "Relevant memory $index",
                    content = "memory detail ".repeat(300),
                    revision = 1,
                    kind = MemoryKind.PROJECT_FACT,
                )
            },
        )
        val payload = memoryExtractionPayload(
            MemoryExtractionRequest(
                scopeId = "assistant-scope",
                assistantId = "assistant-id",
                conversationId = "conversation-id",
                turns = prepared.turns,
                existingMemories = existing,
                evidenceRefToMessageId = prepared.evidenceRefToMessageId,
                isConversationContextCompacted = prepared.isConversationContextCompacted,
            ),
        )

        assertTrue(prepared.isConversationContextCompacted)
        assertTrue(
            prepared.turns.sumOf { it.userText.length + it.assistantText.length } <=
                MAX_MEMORY_EXTRACTION_CONTEXT_CHARS,
        )
        assertTrue("Payload unexpectedly grew to ${payload.length} chars", payload.length <= 9_000)
    }
}
