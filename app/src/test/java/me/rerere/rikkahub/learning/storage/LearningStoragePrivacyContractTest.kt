package me.rerere.rikkahub.learning.storage

import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityRevisionEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.privacy.forbiddenLearningCorpus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LearningStoragePrivacyContractTest {
    @Test
    fun p0Rows_haveNoArbitraryPayloadOrRawContentField() {
        // This one content-free state-machine envelope is encoded/decoded exclusively by the
        // strict, canonical, 16 KiB-bounded LearningReconciliationCursorV1Codec. Keep the
        // exemption field-exact so a second generic JSON escape hatch still fails this contract.
        val boundedStructuredFields = setOf(
            "${LearningStreamCheckpointEntity::class.java.name}#reconciliationCursorV1Json",
            // Monotonic metadata only; this does not contain Policy content.
            "${LearningPolicyEntity::class.java.name}#contentRevision",
            // Fixed-size content digest only; raw message payload remains in the authority DB.
            "${LearningMessageSourceAuthorityEntity::class.java.name}#payloadIntegritySha256",
        )
        val forbiddenFragments = listOf(
            "payload",
            "json",
            "promptText",
            "rawPrompt",
            "messageText",
            "content",
            "reasoning",
            "toolArgs",
            "toolOutput",
            "uri",
            "path",
            "secret",
            "errorMessage",
        ).map(String::lowercase)
        val classes = listOf(
            LearningOutboxEntity::class.java,
            LearningConversationSourceAuthorityEntity::class.java,
            LearningMessageSourceAuthorityEntity::class.java,
            LearningPolicyGrantEntity::class.java,
            LearningPolicyGrantRevisionEntity::class.java,
            RewardFeedbackAuthorityEntity::class.java,
            RewardFeedbackAuthorityRevisionEntity::class.java,
            LearningInboxEventEntity::class.java,
            LearningStreamCheckpointEntity::class.java,
            LearningJobEntity::class.java,
            LearningEpisodeEntity::class.java,
            LearningTraceFeatureEntity::class.java,
            LearningEpisodeLessonEntity::class.java,
            LearningRewardWindowEntity::class.java,
            LearningSourceValidityEntity::class.java,
            LearningPolicyEntity::class.java,
            PolicyEvidenceEntity::class.java,
            PolicyRevisionEntity::class.java,
            PolicyLineageEntity::class.java,
            LearningPolicyExposureEntity::class.java,
            LearningPolicyExposureItemEntity::class.java,
        )
        classes.forEach { type ->
            type.declaredFields.forEach { field ->
                val name = field.name.lowercase()
                val qualifiedName = "${type.name}#${field.name}"
                assertFalse(
                    "${type.simpleName}.${field.name} is an unbounded/private-data escape hatch",
                    qualifiedName !in boundedStructuredFields && forbiddenFragments.any(name::contains),
                )
            }
        }
    }

    @Test
    fun p0Rows_redactStableIdentifiersFromDefaultLogProjection() {
        val rows = listOf(
            outbox(),
            inbox(),
            job(),
            checkpoint(),
            conversationSourceAuthority(),
            messageSourceAuthority(),
            policyGrant(),
            policyGrant().toRevisionEntity(),
            feedbackAuthority(),
            feedbackAuthority().toRevisionEntity(),
        )
        val privateValues = listOf(
            STREAM_ID,
            SCOPE_ID,
            "private-command",
            "private-event",
            "private-job",
            "private-dedupe",
            "private-conversation",
            "private-message",
            "private-policy",
            "private-grant",
            "private-feedback",
            "PRIVATE_SECRET",
        )

        rows.forEach { row ->
            val rendered = row.toString()
            privateValues.forEach { privateValue ->
                assertFalse("${row::class.java.simpleName} leaked an identifier", privateValue in rendered)
            }
        }
    }

    @Test
    fun p0Rows_rejectImpossibleBasicValuesAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { outbox().copy(seq = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            inbox().copy(ingestedAtMs = 0, createdAtMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job().copy(state = LearningJobState.RUNNING.name)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningJob().copy(leaseWorkerId = "worker-safe-looking-but-not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningJob().copy(leaseProcessSessionId = NIL_UUID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkpoint().copy(lastSeenHeadSeq = -1)
        }
    }

    @Test
    fun durableSummaryGuard_rejectsReleaseForbiddenCorpus() {
        forbiddenLearningCorpus().forEach { value ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                requireBoundedRedactedText(value, "release forbidden corpus")
            }
            assertFalse("storage error leaked forbidden input", failure.message.orEmpty().contains(value))
        }
    }

    @Test
    fun mainGrantAndFeedbackAuthorityErrorsNeverEchoForbiddenInput() {
        forbiddenLearningCorpus().forEach { value ->
            val grantFailure = assertThrows(IllegalArgumentException::class.java) {
                policyGrant().copy(reasonCode = value)
            }
            assertFalse("grant validation error leaked rejected input",
                grantFailure.message.orEmpty().contains(value))

            val feedbackFailure = assertThrows(IllegalArgumentException::class.java) {
                feedbackAuthority().copy(feedbackId = value)
            }
            assertFalse("feedback validation error leaked rejected input",
                feedbackFailure.message.orEmpty().contains(value))
        }
    }

    private fun outbox() = LearningOutboxEntity(
        seq = 1,
        streamId = STREAM_ID,
        eventId = "private-event",
        eventType = "PRIVATE_SECRET",
        eventSchemaVersion = 1,
        terminalState = null,
        sourceType = "COMMAND",
        sourceId = "private-command",
        sourceRevision = 1,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        commandId = "private-command",
        lineageId = "private-lineage",
        parentCommandId = null,
        branchAnchorMessageId = "private-anchor",
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = null,
        occurredAtMs = 1,
        createdAtMs = 1,
    )

    private fun inbox() = LearningInboxEventEntity(
        streamId = STREAM_ID,
        eventId = "private-event",
        outboxSeq = 1,
        eventTypeCode = "PRIVATE_SECRET",
        eventSchemaVersion = 1,
        terminalState = null,
        decodeState = "UNKNOWN_NO_JOB",
        interpretationVersion = 1,
        sourceType = "COMMAND",
        sourceId = "private-command",
        sourceRevision = 1,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        commandId = "private-command",
        lineageId = "private-lineage",
        parentCommandId = null,
        branchAnchorMessageId = "private-anchor",
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = null,
        occurredAtMs = 1,
        createdAtMs = 1,
        ingestedAtMs = 1,
        replayGeneration = 0,
    )

    private fun job() = LearningJobEntity(
        id = "private-job",
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "private-dedupe",
        streamId = STREAM_ID,
        sourceEventId = "private-event",
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = 2,
        notBeforeMs = 1,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = 1,
        updatedAtMs = 1,
        finishedAtMs = null,
        replayGeneration = 0,
    )

    private fun runningJob() = job().copy(
        state = LearningJobState.RUNNING.name,
        attempts = 1,
        leaseProcessSessionId = "00000000-0000-0000-0000-000000000010",
        leaseWorkerId = "00000000-0000-0000-0000-000000000011",
        leaseGeneration = 1,
        leaseUntilMs = 2,
    )

    private fun checkpoint() = LearningStreamCheckpointEntity(
        streamId = STREAM_ID,
        lastContiguousSeq = 0,
        lastSeenHeadSeq = 1,
        replayGeneration = 0,
        resetReason = LearningStreamResetReason.NEW_STREAM.name,
        bootstrapState = LearningBootstrapState.REQUIRED.name,
        bootstrapHeadSeq = 1,
        coverageStartMs = null,
        commandCoverageStartMs = null,
        executionCoverageStartMs = null,
        updatedAtMs = 1,
    )

    private fun conversationSourceAuthority() = LearningConversationSourceAuthorityEntity(
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        assistantIdSnapshot = SCOPE_ID,
        sourceRevision = 1,
        previousSourceRevision = null,
        sourceState = "ACTIVE",
        changeKind = "CREATED",
        branchHeadMessageId = "private-message",
        branchHeadMessageRevision = 1,
        occurredAtMs = 1,
        updatedAtMs = 1,
    )

    private fun messageSourceAuthority() = LearningMessageSourceAuthorityEntity(
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        messageId = "private-message",
        messageRole = "USER",
        sourceRevision = 1,
        previousSourceRevision = null,
        sourceState = "ACTIVE",
        changeKind = "CREATED",
        payloadIntegritySha256 = "a".repeat(64),
        occurredAtMs = 1,
        updatedAtMs = 1,
    )

    private fun policyGrant() = LearningPolicyGrantEntity(
        grantId = "private-grant",
        sourceStreamId = STREAM_ID,
        policyId = "private-policy",
        policyRevision = 1,
        artifactSha256 = "b".repeat(64),
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        consumingAssistantId = SCOPE_ID,
        actor = "USER_REVIEW",
        state = "GRANTED",
        stateVersion = 1,
        grantedAtMs = 1,
        revokedAtMs = null,
        reasonCode = "USER_APPROVED",
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun feedbackAuthority() = RewardFeedbackAuthorityEntity(
        feedbackId = "private-feedback",
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        conversationSourceRevision = 1,
        commandId = "private-command",
        commandRevision = 1,
        lineageId = "private-lineage",
        branchAnchorMessageId = "private-message",
        branchAnchorMessageRevision = 1,
        targetAssistantMessageId = "private-assistant-message",
        targetAssistantMessageRevision = 1,
        dimension = "USER",
        signalKind = "EXPLICIT_USER_FEEDBACK",
        valueMilli = 1_000,
        sourceState = "ACTIVE",
        sourceRevision = 1,
        previousSourceRevision = null,
        integritySha256 = "c".repeat(64),
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private companion object {
        const val STREAM_ID = "00000000-0000-0000-0000-000000000001"
        const val SCOPE_ID = "00000000-0000-0000-0000-000000000002"
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
    }
}
