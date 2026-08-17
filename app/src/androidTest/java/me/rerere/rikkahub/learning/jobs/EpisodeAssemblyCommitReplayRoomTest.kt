package me.rerere.rikkahub.learning.jobs

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.episode.EpisodeAssembler
import me.rerere.rikkahub.learning.episode.EpisodeAssemblyJobOutput
import me.rerere.rikkahub.learning.episode.EpisodeAuthorityAnchor
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-emulator only. Never run on the Honor AAK-AN00 primary phone. */
@RunWith(AndroidJUnit4::class)
class EpisodeAssemblyCommitReplayRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LearningDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun downstreamCrashRollsBackEpisode_thenCloseReopenAndExactReplayStaySingle() = runBlocking {
        val source = rootAdmission()
        assertTrue(database.inboxDao().insertIgnore(source) != -1L)
        val input = executionInput()
        val output = episodeOutput()
        val crashAfterEpisodeWrite = EpisodeAssemblyJobOutputCommitter(
            object : P1DerivedJobEnqueuer by NoOpP1DerivedJobEnqueuer {
                override suspend fun afterEpisodeCommitted(
                    database: LearningDatabase,
                    input: LearningJobExecutionInputV1,
                    event: LearningInboxEventEntity,
                    episode: LearningEpisodeEntity,
                ) {
                    throw SimulatedProcessDeath()
                }
            },
        )

        val failure = runCatching {
            database.withTransaction {
                crashAfterEpisodeWrite.persistInOpenTransaction(database, input, output)
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is SimulatedProcessDeath)

        reopen()
        assertNull(database.episodeDao().findEpisode(output.snapshot.authority.episodeId.value))
        assertEquals(0L, database.episodeDao().countEpisodes())

        val committer = EpisodeAssemblyJobOutputCommitter(NoOpP1DerivedJobEnqueuer)
        database.withTransaction {
            committer.persistInOpenTransaction(database, input, output)
        }
        reopen()
        val firstDurable = requireNotNull(
            database.episodeDao().findEpisode(output.snapshot.authority.episodeId.value),
        )
        assertEquals(1L, database.episodeDao().countEpisodes())
        assertEquals(1L, firstDurable.revision)

        database.withTransaction {
            committer.persistInOpenTransaction(database, input, output)
        }
        reopen()
        assertEquals(
            firstDurable,
            database.episodeDao().findEpisode(output.snapshot.authority.episodeId.value),
        )
        assertEquals(1L, database.episodeDao().countEpisodes())
    }

    private fun reopen() {
        database.close()
        database = openDatabase()
    }

    private fun openDatabase(): LearningDatabase = Room.databaseBuilder(
        context,
        LearningDatabase::class.java,
        DB_NAME,
    ).build()

    private fun executionInput() = LearningJobExecutionInputV1(
        jobId = "episode-room-job",
        sourceEventId = EVENT_ID,
        streamId = STREAM_ID.toString(),
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        replayGeneration = 0L,
        createdAtMs = 10L,
        attempt = 1,
        stableProviderIdempotencyKey = "provider-free:episode-room-job",
        executionSpec = LearningJobExecutionSpecs.forNewP0Job(
            LearningJobType.ASSEMBLE_EPISODE_SHADOW,
        ),
    )

    private fun episodeOutput() = EpisodeAssemblyJobOutput.Snapshot(
        snapshot = EpisodeAssembler.admit(
            authority = authority(),
            taskSignature = TaskSignatureV1.create(
                LearningTaskClass.INFORMATION,
                LearningLanguageClass.CHINESE,
                LearningModalityClass.TEXT_ONLY,
                emptySet(),
            ),
            occurredAtMs = 10L,
        ),
        duplicate = false,
        traceFeatures = emptyList(),
        sourceIntegrityByRef = emptyMap(),
    )

    private fun authority() = EpisodeAuthorityAnchor(
        streamId = STREAM_ID,
        scope = SCOPE,
        conversationId = CONVERSATION_ID,
        commandId = ROOT_COMMAND_ID,
        lineageId = ROOT_COMMAND_ID,
        branchAnchorMessageId = BRANCH_MESSAGE_ID,
        branchAnchorMessageRevision = 1L,
        parentCommandId = null,
        resultAssistantMessageId = null,
        resultAssistantMessageRevision = null,
    )

    private fun rootAdmission() = LearningInboxEventEntity(
        streamId = STREAM_ID.toString(),
        eventId = EVENT_ID,
        outboxSeq = 1L,
        eventTypeCode = "COMMAND_ADMITTED",
        eventSchemaVersion = 2,
        terminalState = null,
        decodeState = LearningEventDecodeState.KNOWN.name,
        interpretationVersion = 1,
        sourceType = "COMMAND",
        sourceId = ROOT_COMMAND_ID.toString(),
        sourceRevision = 1L,
        previousSourceRevision = null,
        sourceState = null,
        missingRevisionReason = null,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = CONVERSATION_ID.toString(),
        conversationSourceRevision = 1L,
        commandId = ROOT_COMMAND_ID.toString(),
        lineageId = ROOT_COMMAND_ID.toString(),
        parentCommandId = null,
        branchAnchorMessageId = BRANCH_MESSAGE_ID.toString(),
        branchAnchorMessageRevision = 1L,
        completionKind = null,
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        toolName = null,
        toolSchemaFingerprint = null,
        messageId = null,
        messageRevision = null,
        rewardDimension = null,
        rewardSignalKind = null,
        rewardValueMilli = null,
        executionVerificationState = null,
        occurredAtMs = 10L,
        createdAtMs = 10L,
        ingestedAtMs = 10L,
        replayGeneration = 0L,
    )
}

private class SimulatedProcessDeath : RuntimeException()

private const val DB_NAME = "p5-episode-commit-replay.db"
private const val EVENT_ID = "learning-event-v1:episode-room-root"
private val STREAM_ID = Uuid.parse("71000000-0000-4000-8000-000000000001")
private val ASSISTANT_ID = Uuid.parse("71000000-0000-4000-8000-000000000002")
private val CONVERSATION_ID = Uuid.parse("71000000-0000-4000-8000-000000000003")
private val ROOT_COMMAND_ID = Uuid.parse("71000000-0000-4000-8000-000000000004")
private val BRANCH_MESSAGE_ID = Uuid.parse("71000000-0000-4000-8000-000000000005")
private val SCOPE = LearningScope.Assistant(ASSISTANT_ID)
