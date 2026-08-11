package me.rerere.rikkahub.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCandidatePolicyTest {
    @Test
    fun `duplicate detector handles normalized exact text and bilingual near matches`() {
        val detector = MemoryDuplicateDetector()

        assertEquals(
            MemoryDuplicateAssessment.EXACT,
            detector.assess(
                candidate = "  Prefers   Sugar-Free LATTE  ",
                existing = listOf("prefers sugar-free latte"),
            ),
        )
        assertEquals(
            MemoryDuplicateAssessment.NEAR,
            detector.assess(
                candidate = "用户长期偏好无糖拿铁咖啡",
                existing = listOf("用户偏好喝无糖拿铁咖啡"),
            ),
        )
    }

    @Test
    fun `only a safe high confidence create is auto applied in safe new mode`() {
        val policy = MemoryCandidatePolicy()
        val safeCreate = proposal(MemoryCandidateAction.CREATE, confidence = 0.94f)
        val safeUpdate = proposal(
            action = MemoryCandidateAction.UPDATE,
            confidence = 0.99f,
            targetIds = listOf(7),
            expectedRevisions = listOf(3),
        )

        assertEquals(
            MemoryCandidateDisposition.AUTO_APPLY,
            policy.decide(safeCreate, MemoryAutoSaveMode.SAFE_NEW_ONLY),
        )
        assertEquals(
            MemoryCandidateDisposition.REVIEW,
            policy.decide(safeUpdate, MemoryAutoSaveMode.SAFE_NEW_ONLY),
        )
    }

    @Test
    fun `batch safe-new selection accepts only clean high confidence creates`() {
        val policy = MemoryCandidatePolicy()
        val safeCreate = proposal(MemoryCandidateAction.CREATE, confidence = 0.94f)

        assertTrue(policy.isSafeNewCreate(safeCreate))
        assertFalse(policy.isSafeNewCreate(safeCreate.copy(confidence = 0.89f)))
        assertFalse(policy.isSafeNewCreate(safeCreate.copy(truthStatus = MemoryTruthStatus.PROVISIONAL)))
        assertFalse(policy.isSafeNewCreate(safeCreate.copy(truthStatus = MemoryTruthStatus.DISPUTED)))
        assertFalse(policy.isSafeNewCreate(safeCreate.copy(truthStatus = MemoryTruthStatus.SUPERSEDED)))
        assertFalse(
            policy.isSafeNewCreate(
                safeCreate.copy(
                    action = MemoryCandidateAction.UPDATE,
                    targetIds = listOf(7),
                    expectedRevisions = listOf(3),
                ),
            ),
        )
        assertFalse(policy.isSafeNewCreate(safeCreate.copy(content = "api_key=abcdefghi")))
    }

    @Test
    fun `near duplicates carry a persisted review flag for batch safety`() {
        val policy = MemoryCandidatePolicy()

        assertEquals(
            setOf(MemoryRiskFlag.NEAR_DUPLICATE),
            policy.reviewFlagsFor(
                proposal(MemoryCandidateAction.CREATE, confidence = 0.94f),
                MemoryDuplicateAssessment.NEAR,
            ),
        )
    }

    @Test
    fun `confirmed important user or shared episodes may auto apply but theories never do`() {
        val policy = MemoryCandidatePolicy()
        val episode = proposal(MemoryCandidateAction.CREATE, 0.95f).copy(
            kind = MemoryKind.EPISODE,
            attribution = MemoryAttribution.SHARED,
            truthStatus = MemoryTruthStatus.CONFIRMED,
            importance = 0.7f,
        )

        assertEquals(MemoryCandidateDisposition.AUTO_APPLY, policy.decide(episode, MemoryAutoSaveMode.SAFE_NEW_ONLY))
        assertEquals(MemoryCandidateDisposition.REVIEW, policy.decide(episode.copy(importance = 0.59f), MemoryAutoSaveMode.SAFE_NEW_ONLY))
        assertEquals(MemoryCandidateDisposition.REVIEW, policy.decide(episode.copy(attribution = MemoryAttribution.ASSISTANT), MemoryAutoSaveMode.SAFE_NEW_ONLY))
        assertEquals(MemoryCandidateDisposition.REVIEW, policy.decide(episode.copy(kind = MemoryKind.THEORY), MemoryAutoSaveMode.SAFE_NEW_ONLY))
    }

    private fun proposal(
        action: MemoryCandidateAction,
        confidence: Float,
        targetIds: List<Int> = emptyList(),
        expectedRevisions: List<Int> = emptyList(),
    ) = MemoryProposal(
        action = action,
        targetIds = targetIds,
        expectedRevisions = expectedRevisions,
        title = "Coffee preference",
        content = "The user consistently prefers sugar-free latte.",
        kind = MemoryKind.PREFERENCE,
        tags = listOf("coffee"),
        importance = 0.7f,
        confidence = confidence,
        evidenceMessageIds = listOf("message-1"),
        reason = "Durable preference",
    )
}
