package me.rerere.rikkahub.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryNarrativePolicyTest {
    private val identity = MemoryNarrativeIdentity(
        selfName = "角色甲",
        companionName = "角色乙",
    )

    @Test
    fun `readable provider output uses configured names and canonical participants`() {
        val proposal = MemoryProposal(
            action = MemoryCandidateAction.CREATE,
            title = "用户与ASSISTANT的决定",
            content = "USER和助手一起完成了修复。",
            kind = MemoryKind.EPISODE,
            attribution = MemoryAttribution.SHARED,
            participants = listOf("用户", "forged participant"),
            tags = listOf("用户偏好", "assistant-view"),
            outcome = "the user confirmed the result",
            evidenceMessageIds = listOf("T1"),
            reason = "assistant observed a durable change",
        )

        val normalized = MemoryNarrativePolicy().normalize(proposal, identity)

        assertEquals("角色甲与角色乙的决定", normalized.title)
        assertEquals("角色甲和角色乙一起完成了修复。", normalized.content)
        assertEquals("角色甲 confirmed the result", normalized.outcome)
        assertEquals("角色乙 observed a durable change", normalized.reason)
        assertEquals(listOf("USER", "ASSISTANT"), normalized.participants)
        assertEquals(listOf("角色甲偏好", "角色乙-view"), normalized.tags)
        listOf(normalized.title, normalized.content, normalized.outcome.orEmpty(), normalized.reason)
            .forEach { text ->
                assertFalse(text.contains("用户"))
                assertFalse(text.contains("助手"))
                assertFalse(text.contains("USER"))
                assertFalse(text.contains("ASSISTANT"))
            }
    }

    @Test
    fun `validator rejects non canonical participant tokens`() {
        val proposal = MemoryProposal(
            proposalKey = "p1",
            action = MemoryCandidateAction.CREATE,
            title = "Durable preference",
            content = "A valid durable preference.",
            kind = MemoryKind.PREFERENCE,
            participants = listOf("用户"),
            confidence = 0.9f,
            evidenceMessageIds = listOf("T1"),
        )

        val result = MemoryProposalValidator().validate(
            envelope = MemoryExtractionEnvelope(version = 2, proposals = listOf(proposal)),
            context = MemoryProposalValidationContext(
                allowedEvidenceMessageIds = setOf("T1"),
                visibleExistingMemories = emptyMap(),
            ),
        )

        assertEquals(emptyList<MemoryProposal>(), result.accepted)
        assertEquals(
            MemoryProposalRejectionCode.INVALID_NARRATIVE_METADATA,
            result.rejected.single().code,
        )
    }

    @Test
    fun `traditional Chinese role labels also use configured names`() {
        val readable = normalizeMemoryNarrativeText(
            "用戶和助理一起确认了结果。",
            identity,
        )

        assertEquals("角色甲和角色乙一起确认了结果。", readable)
    }
}
