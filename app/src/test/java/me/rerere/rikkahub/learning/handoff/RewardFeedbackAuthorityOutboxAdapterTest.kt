package me.rerere.rikkahub.learning.handoff

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.authority.reward.RewardDimension
import me.rerere.rikkahub.data.authority.reward.RewardFeedbackAuthorityEvent
import me.rerere.rikkahub.data.authority.reward.RewardFeedbackSourceState
import me.rerere.rikkahub.data.authority.reward.RewardSignalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardFeedbackAuthorityOutboxAdapterTest {
    @Test
    fun `active feedback becomes canonical content-free schema v3`() {
        val draft = event().toLearningOutboxDraft(STREAM)
        val row = draft.toEntity().copy(seq = 2)

        assertEquals("USER_FEEDBACK_RECORDED", row.eventType)
        assertEquals(3, row.eventSchemaVersion)
        assertEquals("USER", row.rewardDimension)
        assertEquals("EXPLICIT_USER_FEEDBACK", row.rewardSignalKind)
        assertEquals(1_000, row.rewardValueMilli)
        assertEquals("ACTIVE", row.sourceState)
        assertNull(row.executionVerificationState)
        assertTrue(row.eventId.startsWith("learning-event-v3:"))

        val decoded = LearningOutboxRowDecoder.decode(row)
        assertTrue(decoded is LearningOutboxDecodeResult.Valid)
        val handoff = (decoded as LearningOutboxDecodeResult.Valid).event
        assertEquals("USER", handoff.rewardDimensionCode)
        assertEquals(1_000, handoff.toInboxEntity(20, 0).rewardValueMilli)
    }

    @Test
    fun `tombstone preserves identity but removes reward value`() {
        val row = event().copy(
            sourceState = RewardFeedbackSourceState.TOMBSTONED,
            sourceRevision = 2,
            previousSourceRevision = 1,
            valueMilli = null,
        ).toLearningOutboxDraft(STREAM).toEntity().copy(seq = 3)

        assertNull(row.rewardValueMilli)
        assertEquals("TOMBSTONED", row.sourceState)
        assertTrue(LearningOutboxRowDecoder.decode(row) is LearningOutboxDecodeResult.Valid)
    }

    @Test
    fun `consent gates new feedback but never its replacement or tombstone`() {
        val initial = event()
        assertFalse(shouldProjectRewardFeedbackAuthorityEvent(initial, captureAllowed = false))

        val replacement = initial.copy(
            sourceRevision = 2,
            previousSourceRevision = 1,
        )
        assertTrue(shouldProjectRewardFeedbackAuthorityEvent(replacement, captureAllowed = false))

        val tombstone = replacement.copy(
            sourceState = RewardFeedbackSourceState.TOMBSTONED,
            sourceRevision = 3,
            previousSourceRevision = 2,
            valueMilli = null,
        )
        assertTrue(shouldProjectRewardFeedbackAuthorityEvent(tombstone, captureAllowed = false))
    }

    private fun event() = RewardFeedbackAuthorityEvent(
        feedbackId = "reward-feedback-v1:${"a".repeat(64)}",
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000010",
        conversationId = "conversation-1",
        conversationSourceRevision = 5,
        commandId = "command-1",
        commandRevision = 4,
        lineageId = "lineage-1",
        branchAnchorMessageId = "message-user-1",
        branchAnchorMessageRevision = 1,
        targetAssistantMessageId = "message-assistant-1",
        targetAssistantMessageRevision = 1,
        dimension = RewardDimension.USER,
        signalKind = RewardSignalKind.EXPLICIT_USER_FEEDBACK,
        valueMilli = 1_000,
        sourceState = RewardFeedbackSourceState.ACTIVE,
        sourceRevision = 1,
        previousSourceRevision = null,
        occurredAtMs = 10,
    )

    private companion object {
        val STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
    }
}
