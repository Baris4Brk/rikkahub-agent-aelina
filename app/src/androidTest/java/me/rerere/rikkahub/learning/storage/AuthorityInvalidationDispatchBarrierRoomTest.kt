package me.rerere.rikkahub.learning.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-emulator only. Never run on the Honor AAK-AN00 primary phone. */
@RunWith(AndroidJUnit4::class)
class AuthorityInvalidationDispatchBarrierRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LearningDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        database = Room.databaseBuilder(context, LearningDatabase::class.java, DB_NAME).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun everyNonDoneInvalidationStateBlocks_thenDoneRestoresAndExactStreamReplayScopeWins() =
        runBlocking {
            val currentStates = listOf(
                LearningJobState.PENDING,
                LearningJobState.RETRY,
                LearningJobState.RUNNING,
                LearningJobState.DEAD_LETTER,
            )
            currentStates.forEachIndexed { index, state ->
                assertTrue(
                    database.jobDao().insertIgnore(
                        invalidationJob(
                            id = "barrier-source-$index",
                            state = state,
                            streamId = STREAM_ID,
                            replayGeneration = CURRENT_REPLAY,
                        ),
                    ) != -1L,
                )
            }
            database.jobDao().insertIgnore(
                invalidationJob(
                    id = "barrier-other-stream",
                    state = LearningJobState.PENDING,
                    streamId = OTHER_STREAM_ID,
                    replayGeneration = CURRENT_REPLAY,
                ),
            )
            database.jobDao().insertIgnore(
                invalidationJob(
                    id = "barrier-other-replay",
                    state = LearningJobState.PENDING,
                    streamId = STREAM_ID,
                    replayGeneration = CURRENT_REPLAY + 1L,
                ),
            )

            currentStates.indices.forEach { index ->
                assertEquals(1, barrierCount())
                markDone("barrier-source-$index", 100L + index)
            }
            assertEquals(0, barrierCount())

            val initialFeedback = feedbackEvent(
                eventId = "feedback-initial-event",
                sourceRevision = 1L,
                previousSourceRevision = null,
            )
            val replacementFeedback = feedbackEvent(
                eventId = "feedback-replacement-event",
                sourceRevision = 2L,
                previousSourceRevision = 1L,
            )
            assertTrue(database.inboxDao().insertIgnore(initialFeedback) != -1L)
            assertTrue(database.inboxDao().insertIgnore(replacementFeedback) != -1L)
            assertTrue(
                database.jobDao().insertIgnore(
                    rewardAuthorityJob("barrier-feedback-initial", initialFeedback),
                ) != -1L,
            )
            assertEquals(0, barrierCount())
            assertTrue(
                database.jobDao().insertIgnore(
                    rewardAuthorityJob("barrier-feedback-replacement", replacementFeedback),
                ) != -1L,
            )
            assertEquals(1, barrierCount())

            markDone("barrier-feedback-replacement", 200L)
            assertEquals(
                0,
                barrierCount(),
            ) // Initial positive feedback remains pending but is not an invalidation barrier.
        }

    private suspend fun barrierCount(): Int =
        database.jobDao().countNonDoneAuthorityInvalidationBarrier(
            STREAM_ID.toString(),
            CURRENT_REPLAY,
        )

    private fun markDone(id: String, finishedAtMs: Long) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE learning_jobs SET state = 'DONE', lease_process_session_id = NULL, " +
                "lease_worker_id = NULL, lease_until_ms = NULL, last_error_code = NULL, " +
                "updated_at_ms = ?, finished_at_ms = ? WHERE id = ?",
            arrayOf<Any?>(finishedAtMs, finishedAtMs, id),
        )
    }

    private fun invalidationJob(
        id: String,
        state: LearningJobState,
        streamId: Uuid,
        replayGeneration: Long,
    ): LearningJobEntity = job(
        id = id,
        jobType = LearningJobType.INVALIDATE_SOURCE_V1,
        sourceEventId = "source-event-$id",
        state = state,
        streamId = streamId,
        replayGeneration = replayGeneration,
        sourceSchemaIdentity = "learning-source-invalidation-event-v2",
        outputSchemaIdentity = "learning-source-validity-output-v1",
    )

    private fun rewardAuthorityJob(
        id: String,
        event: LearningInboxEventEntity,
    ): LearningJobEntity = job(
        id = id,
        jobType = LearningJobType.APPLY_REWARD_AUTHORITY_V1,
        sourceEventId = event.eventId,
        state = LearningJobState.PENDING,
        streamId = Uuid.parse(event.streamId),
        replayGeneration = event.replayGeneration,
        sourceSchemaIdentity = "learning-user-feedback-event-v3",
        outputSchemaIdentity = "reward-authority-output-v1",
    )

    private fun job(
        id: String,
        jobType: LearningJobType,
        sourceEventId: String,
        state: LearningJobState,
        streamId: Uuid,
        replayGeneration: Long,
        sourceSchemaIdentity: String,
        outputSchemaIdentity: String,
    ): LearningJobEntity {
        val running = state == LearningJobState.RUNNING
        val terminal = state == LearningJobState.DEAD_LETTER
        val retry = state == LearningJobState.RETRY
        val updatedAtMs = 20L
        return LearningJobEntity(
            id = id,
            jobType = jobType.name,
            jobSchemaVersion = 1,
            dedupeKey = "dedupe-$id",
            streamId = streamId.toString(),
            sourceEventId = sourceEventId,
            scopeKind = SCOPE.kind.name,
            scopeId = SCOPE.storageId,
            state = state.name,
            priority = 0,
            attempts = if (state == LearningJobState.PENDING) 0 else 1,
            maxAttempts = 5,
            notBeforeMs = if (retry) 30L else 10L,
            leaseProcessSessionId = PROCESS_ID.toString().takeIf { running },
            leaseWorkerId = WORKER_ID.toString().takeIf { running },
            leaseGeneration = if (running) 1L else 0L,
            leaseUntilMs = 100L.takeIf { running },
            lastErrorCode = LearningJobErrorCode.WAITING_CONFIGURATION.name
                .takeIf { retry } ?: LearningJobErrorCode.SOURCE_STALE.name.takeIf { terminal },
            createdAtMs = 10L,
            updatedAtMs = updatedAtMs,
            finishedAtMs = updatedAtMs.takeIf { terminal },
            replayGeneration = replayGeneration,
            algorithmIdentity = if (jobType == LearningJobType.INVALIDATE_SOURCE_V1) {
                "source-invalidation-v1"
            } else {
                "reward-authority-fold-v1"
            },
            promptIdentity = "no-provider-prompt-v1",
            providerKindIdentity = "none",
            modelIdentity = "no-provider-model-v1",
            providerIdentity = "no-provider-v1",
            providerConfigurationIdentity = "no-provider-configuration-v1",
            providerConfigGeneration = 0L,
            sourceSchemaIdentity = sourceSchemaIdentity,
            toolsetIdentity = "authority-event-only-v1",
            outputSchemaIdentity = outputSchemaIdentity,
        )
    }

    private fun feedbackEvent(
        eventId: String,
        sourceRevision: Long,
        previousSourceRevision: Long?,
    ) = LearningInboxEventEntity(
        streamId = STREAM_ID.toString(),
        eventId = eventId,
        outboxSeq = sourceRevision,
        eventTypeCode = "USER_FEEDBACK_RECORDED",
        eventSchemaVersion = 3,
        terminalState = null,
        decodeState = LearningEventDecodeState.KNOWN.name,
        interpretationVersion = 1,
        sourceType = "USER_FEEDBACK",
        sourceId = "feedback-authority-source",
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        sourceState = "ACTIVE",
        missingRevisionReason = null,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = null,
        conversationSourceRevision = null,
        commandId = null,
        lineageId = null,
        parentCommandId = null,
        branchAnchorMessageId = null,
        branchAnchorMessageRevision = null,
        completionKind = null,
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        toolName = null,
        toolSchemaFingerprint = null,
        messageId = null,
        messageRevision = null,
        rewardDimension = "USER",
        rewardSignalKind = "EXPLICIT_USER_FEEDBACK",
        rewardValueMilli = 1_000,
        executionVerificationState = null,
        occurredAtMs = 10L,
        createdAtMs = 10L,
        ingestedAtMs = 10L,
        replayGeneration = CURRENT_REPLAY,
    )
}

private const val DB_NAME = "p5-authority-invalidation-dispatch-barrier.db"
private const val CURRENT_REPLAY = 3L
private val STREAM_ID = Uuid.parse("74000000-0000-4000-8000-000000000001")
private val OTHER_STREAM_ID = Uuid.parse("74000000-0000-4000-8000-000000000002")
private val PROCESS_ID = Uuid.parse("74000000-0000-4000-8000-000000000003")
private val WORKER_ID = Uuid.parse("74000000-0000-4000-8000-000000000004")
private val SCOPE = LearningScope.Assistant(
    Uuid.parse("74000000-0000-4000-8000-000000000005"),
)
