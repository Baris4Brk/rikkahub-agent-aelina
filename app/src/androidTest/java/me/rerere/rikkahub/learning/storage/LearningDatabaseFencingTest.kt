package me.rerere.rikkahub.learning.storage

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.jobs.LearningJobClaimResult
import me.rerere.rikkahub.learning.jobs.LearningJobClock
import me.rerere.rikkahub.learning.jobs.LearningJobCoordinator
import me.rerere.rikkahub.learning.jobs.LearningLostLeaseException
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowResetReceipt
import me.rerere.rikkahub.learning.privacy.DurableScopeLearnedWorkflowEraseReceipt
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowEraseBatchReceipt
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningDatabaseFencingTest {
    private lateinit var database: LearningDatabase

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LearningDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun p2V6Schema_containsP1ClosureAndContentFreeExposureTables() {
        migrationHelper.createDatabase("learning-v6-fencing-schema", 6).use { v6 ->
            val names = buildSet {
                v6.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name LIKE 'learning_%'",
                ).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertEquals(
                setOf(
                    "learning_inbox_events",
                    "learning_stream_checkpoints",
                    "learning_jobs",
                    "learning_episodes",
                    "learning_trace_features",
                    "learning_episode_lessons",
                    "learning_reward_windows",
                    "learning_source_validity",
                    "learning_policies",
                    "learning_provider_config_cohorts",
                    "learning_provider_job_manifests",
                    "learning_provider_attempts",
                    "learning_reward_signals",
                    "learning_policy_exposures",
                    "learning_policy_exposure_items",
                ),
                names,
            )
        }
    }

    @Test
    fun reclaimedLease_fencesOldWorkerFinish() = runBlocking {
        database.jobDao().insertIgnore(job(id = "job-source"))
        val clock = MutableLearningJobClock(100)
        val firstCoordinator = coordinator(PROCESS_A, clock)
        val first = firstCoordinator.claimNext(Uuid.parse(WORKER_A), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed
        clock.nowMs = 201
        val secondCoordinator = coordinator(PROCESS_B, clock)
        val second = secondCoordinator.claimNext(Uuid.parse(WORKER_B), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed

        clock.nowMs = 202
        val staleCommit = runCatching {
            firstCoordinator.failPermanently(
                first.lease,
                LearningJobFailureCode.SOURCE_STALE,
            )
        }
        assertTrue(staleCommit.exceptionOrNull() is LearningLostLeaseException)
        assertEquals(LearningJobState.RUNNING.name, database.jobDao().findById("job-source")?.state)

        secondCoordinator.failPermanently(
            second.lease,
            LearningJobFailureCode.SOURCE_STALE,
        )
        assertEquals(
            LearningJobState.DEAD_LETTER.name,
            database.jobDao().findById("job-source")?.state,
        )
    }

    @Test
    fun clockRollback_isReportedAndDoesNotClaim() = runBlocking {
        database.jobDao().insertIgnore(job(id = "future-job", updatedAtMs = 500))
        val clock = MutableLearningJobClock(100)
        val result = coordinator(PROCESS_A, clock)
            .claimNext(Uuid.parse(WORKER_A), leaseDurationMs = 100)
        assertEquals(
            LearningJobClaimResult.ClockRollback("future-job"),
            result,
        )
        assertNull(database.jobDao().findById("future-job")?.leaseProcessSessionId)
    }

    @Test
    fun reconciliationCursorCasFencesStreamGenerationExpectedBlobAndClock() = runBlocking {
        database.checkpointDao().insert(
            checkpoint(streamId = STREAM_A, replayGeneration = 2).copy(
                bootstrapState = LearningBootstrapState.COMPLETE.name,
                updatedAtMs = 10,
            ),
        )
        val first = "{\"phase\":\"COMMAND\",\"after\":1}"
        val second = "{\"phase\":\"EXECUTION\",\"after\":2}"

        assertNull(database.checkpointDao().findReconciliationCursor(STREAM_A, 2))
        assertEquals(
            0,
            database.checkpointDao().compareAndSetReconciliationCursor(
                STREAM_A,
                3,
                expectedCursorJson = null,
                newCursorJson = first,
                updatedAtMs = 11,
            ),
        )
        assertEquals(
            1,
            database.checkpointDao().compareAndSetReconciliationCursor(
                STREAM_A,
                2,
                expectedCursorJson = null,
                newCursorJson = first,
                updatedAtMs = 11,
            ),
        )
        assertEquals(first, database.checkpointDao().findReconciliationCursor(STREAM_A, 2))
        assertEquals(
            0,
            database.checkpointDao().compareAndSetReconciliationCursor(
                STREAM_A,
                2,
                expectedCursorJson = null,
                newCursorJson = second,
                updatedAtMs = 12,
            ),
        )
        assertEquals(
            0,
            database.checkpointDao().compareAndSetReconciliationCursor(
                STREAM_A,
                2,
                expectedCursorJson = first,
                newCursorJson = second,
                updatedAtMs = 10,
            ),
        )
        assertEquals(
            1,
            database.checkpointDao().clearReconciliationCursor(
                STREAM_A,
                2,
                expectedCursorJson = first,
                updatedAtMs = 12,
            ),
        )
        assertNull(database.checkpointDao().findReconciliationCursor(STREAM_A, 2))
    }

    @Test
    fun resettingDerivedState_removesFutureTimelineRows() = runBlocking {
        database.jobDao().insertIgnore(job(id = "old-job"))
        database.inboxDao().insertIgnore(inbox(id = "old-event"))
        database.checkpointDao().insert(checkpoint(streamId = STREAM_A, replayGeneration = 2))

        val reset = derivedStateResetter().reset(
            streamId = kotlin.uuid.Uuid.parse(STREAM_B),
            observedHeadSeq = 9,
            reason = LearningStreamResetReason.RESTORE,
            frozenNowMs = 1_000,
        )

        assertNull(database.jobDao().findById("old-job"))
        assertNull(database.inboxDao().find(STREAM_A, "old-event"))
        assertEquals(STREAM_B, reset.streamId)
        assertEquals(3L, reset.replayGeneration)
        assertNotNull(database.checkpointDao().find(STREAM_B))
    }

    @Test
    fun resettingWithoutCheckpoint_advancesPastLiveJobAndInboxGeneration() = runBlocking {
        database.jobDao().insertIgnore(job(id = "orphan-job", replayGeneration = 7))
        database.inboxDao().insertIgnore(inbox(id = "orphan-event", replayGeneration = 8))

        val reset = derivedStateResetter().reset(
            streamId = kotlin.uuid.Uuid.parse(STREAM_B),
            observedHeadSeq = 1,
            reason = LearningStreamResetReason.CORRUPTION,
            frozenNowMs = 1_000,
        )

        assertEquals(9L, reset.replayGeneration)
        assertNull(database.jobDao().findById("orphan-job"))
        assertNull(database.inboxDao().find(STREAM_A, "orphan-event"))
    }

    @Test
    fun resetDeletesExposureChildrenBeforeRestrictedEpisodeAndAdvancesGeneration() = runBlocking {
        val episode = episode(replayGeneration = 9)
        val exposure = exposure(episodeId = episode.id, replayGeneration = 9)
        database.episodeDao().insertEpisodeIgnore(episode)
        database.policyExposureDao().insertExposure(exposure)
        database.policyExposureDao().insertItem(exposureItem(exposure.id))

        val reset = derivedStateResetter().reset(
            streamId = Uuid.parse(STREAM_B),
            observedHeadSeq = 1,
            reason = LearningStreamResetReason.RESTORE,
            frozenNowMs = 1_000,
        )

        assertEquals(10L, reset.replayGeneration)
        assertNull(database.policyExposureDao().findExposure(exposure.id))
        assertTrue(database.policyExposureDao().listItems(exposure.id, 8).isEmpty())
        assertNull(database.episodeDao().findEpisode(episode.id))
    }

    @Test
    fun exposureSnapshotCasAcceptsExactlyOneExpectedStateVersion() = runBlocking {
        val episode = episode(replayGeneration = 0)
        val exposure = exposure(episodeId = episode.id, replayGeneration = 0)
        database.episodeDao().insertEpisodeIgnore(episode)
        database.policyExposureDao().insertExposure(exposure)

        val applied = database.policyExposureDao().updateSnapshotIfCurrent(
            id = exposure.id,
            expectedStateVersion = 0,
            furthestState = LearningPolicyExposureState.COMPILED.name,
            retrievedAtMs = 1,
            compiledAtMs = 2,
            injectedAtMs = null,
            hostDispatchedAtMs = null,
            firstProgressAtMs = null,
            responseFinishedAtMs = null,
            outcomeLinkedAtMs = null,
            terminalOutcome = null,
            terminalAtMs = null,
            outcomeSourceType = null,
            outcomeSourceId = null,
            outcomeSourceRevision = null,
            attributionState = LearningPolicyExposureAttributionState.UNKNOWN.name,
            updatedAtMs = 2,
        )
        val stale = database.policyExposureDao().updateSnapshotIfCurrent(
            id = exposure.id,
            expectedStateVersion = 0,
            furthestState = LearningPolicyExposureState.COMPILED.name,
            retrievedAtMs = 1,
            compiledAtMs = 2,
            injectedAtMs = null,
            hostDispatchedAtMs = null,
            firstProgressAtMs = null,
            responseFinishedAtMs = null,
            outcomeLinkedAtMs = null,
            terminalOutcome = null,
            terminalAtMs = null,
            outcomeSourceType = null,
            outcomeSourceId = null,
            outcomeSourceRevision = null,
            attributionState = LearningPolicyExposureAttributionState.UNKNOWN.name,
            updatedAtMs = 2,
        )

        assertEquals(1, applied)
        assertEquals(0, stale)
        val persisted = requireNotNull(database.policyExposureDao().findExposure(exposure.id))
        assertEquals(1L, persisted.stateVersion)
        assertEquals(LearningPolicyExposureState.COMPILED.name, persisted.furthestState)
        assertEquals(2L, persisted.compiledAtMs)
    }

    private fun job(
        id: String,
        updatedAtMs: Long = 0,
        replayGeneration: Long = 0,
    ) = LearningJobEntity(
        id = id,
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "dedupe-$id",
        streamId = STREAM_A,
        sourceEventId = "event-$id",
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000003",
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = 2,
        notBeforeMs = 0,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = 0,
        updatedAtMs = updatedAtMs,
        finishedAtMs = null,
        replayGeneration = replayGeneration,
    )

    private fun inbox(id: String, replayGeneration: Long = 2) = LearningInboxEventEntity(
        streamId = STREAM_A,
        eventId = id,
        outboxSeq = 2,
        eventTypeCode = "FUTURE_EVENT",
        eventSchemaVersion = 1,
        terminalState = null,
        decodeState = "UNKNOWN_NO_JOB",
        interpretationVersion = 1,
        sourceType = "COMMAND",
        sourceId = "source",
        sourceRevision = 1,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000003",
        conversationId = null,
        commandId = null,
        lineageId = null,
        parentCommandId = null,
        branchAnchorMessageId = null,
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = null,
        occurredAtMs = 0,
        createdAtMs = 0,
        ingestedAtMs = 0,
        replayGeneration = replayGeneration,
    )

    private fun checkpoint(streamId: String, replayGeneration: Long) =
        LearningStreamCheckpointEntity(
            streamId = streamId,
            lastContiguousSeq = 0,
            lastSeenHeadSeq = 0,
            replayGeneration = replayGeneration,
            resetReason = LearningStreamResetReason.NEW_STREAM.name,
            bootstrapState = LearningBootstrapState.REQUIRED.name,
            bootstrapHeadSeq = 0,
            coverageStartMs = null,
            commandCoverageStartMs = null,
            executionCoverageStartMs = null,
            updatedAtMs = 0,
        )

    private fun episode(replayGeneration: Long) = LearningEpisodeEntity(
        id = "episode-v1:${"a".repeat(64)}",
        streamId = STREAM_A,
        replayGeneration = replayGeneration,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000003",
        conversationId = "conversation-v1",
        conversationRevision = 1,
        rootCommandId = "command-v1",
        rootCommandRevision = 1,
        finalCommandId = "command-v1",
        finalCommandRevision = 1,
        lineageId = "lineage-v1",
        branchAnchorMessageId = "message-v1",
        branchAnchorMessageRevision = 1,
        resultAssistantMessageId = "message-v2",
        resultAssistantMessageRevision = 1,
        generationRunId = "generation-v1",
        executionId = null,
        taskSignature = "task-signature-v1",
        status = StoredLearningEpisodeStatus.SUCCESS.name,
        boundaryReason = LearningEpisodeBoundaryReason.FINAL_SAVED.name,
        revision = 1,
        startedAtMs = 0,
        finalizedAtMs = 1,
        createdAtMs = 0,
        updatedAtMs = 1,
    )

    private fun exposure(
        episodeId: String,
        replayGeneration: Long,
    ) = LearningPolicyExposureEntity(
        id = "policy-exposure-v1:${"b".repeat(64)}",
        streamId = STREAM_A,
        replayGeneration = replayGeneration,
        episodeId = episodeId,
        logicalRunId = "00000000-0000-0000-0000-000000000005",
        attemptOrdinal = 1,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000003",
        taskSignature = "task-signature-v1",
        policySetDigest = "c".repeat(64),
        treatmentArm = "TREATMENT",
        modelIdentity = "model-v1",
        providerIdentity = "provider-v1",
        providerGeneration = 1,
        toolsetFingerprint = "d".repeat(64),
        contextCompilerAbi = "recall-compiler-v1",
        stateVersion = 0,
        furthestState = LearningPolicyExposureState.RETRIEVED.name,
        retrievedAtMs = 1,
        compiledAtMs = null,
        injectedAtMs = null,
        hostDispatchedAtMs = null,
        firstProgressAtMs = null,
        responseFinishedAtMs = null,
        outcomeLinkedAtMs = null,
        terminalOutcome = null,
        terminalAtMs = null,
        outcomeSourceType = null,
        outcomeSourceId = null,
        outcomeSourceRevision = null,
        attributionState = LearningPolicyExposureAttributionState.UNKNOWN.name,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun exposureItem(exposureId: String) = LearningPolicyExposureItemEntity(
        exposureId = exposureId,
        policyId = "policy-v1:${"e".repeat(64)}",
        policyRevision = 1,
        artifactSha256 = "f".repeat(64),
        applicabilityCohortDigest = "a".repeat(64),
        rank = 1,
        estimatedTokens = 32,
        dropReason = null,
        retrievedAtMs = 1,
        compiledAtMs = null,
        injectedAtMs = null,
    )

    private fun coordinator(
        processSessionId: String,
        clock: LearningJobClock,
    ) = LearningJobCoordinator(
        database = database,
        processSessionId = Uuid.parse(processSessionId),
        clock = clock,
        maxLeaseDurationMs = 100,
    )

    private fun derivedStateResetter() =
        me.rerere.rikkahub.learning.handoff.LearningDerivedStateResetter(
            database = database,
            learnedWorkflowErasePort = ExactScopeLearnedWorkflowErasePort { ids, _ ->
                ExactScopeLearnedWorkflowEraseBatchReceipt(
                    fencedCandidateIds = ids.size,
                    redactedWorkflowDefinitions = 0,
                    insertedFenceClaims = ids.size,
                )
            },
            durableLearnedWorkflowPrivacyPort = object : DurableLearnedWorkflowPrivacyPort {
                override suspend fun redactExactScope(
                    scope: LearningScope,
                    frozenNowMs: Long,
                ) = DurableScopeLearnedWorkflowEraseReceipt(0, 0, 0, 0)

                override suspend fun redactAllForDerivedReset(
                    frozenNowMs: Long,
                ) = DurableLearnedWorkflowResetReceipt(0, 0, complete = true)
            },
        )

    private class MutableLearningJobClock(var nowMs: Long) : LearningJobClock {
        override fun nowMs(): Long = nowMs
    }

    private companion object {
        const val STREAM_A = "00000000-0000-0000-0000-000000000001"
        const val STREAM_B = "00000000-0000-0000-0000-000000000002"
        const val PROCESS_A = "00000000-0000-0000-0000-000000000010"
        const val PROCESS_B = "00000000-0000-0000-0000-000000000020"
        const val WORKER_A = "00000000-0000-0000-0000-000000000011"
        const val WORKER_B = "00000000-0000-0000-0000-000000000021"
    }
}
