package me.rerere.rikkahub.learning.storage.curator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.curator.CuratorApplyResult
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorEvidenceRef
import me.rerere.rikkahub.learning.curator.CuratorFieldDiff
import me.rerere.rikkahub.learning.curator.CuratorPolicyDocument
import me.rerere.rikkahub.learning.curator.CuratorPolicyField
import me.rerere.rikkahub.learning.curator.CuratorPolicyHead
import me.rerere.rikkahub.learning.curator.CuratorPolicyState
import me.rerere.rikkahub.learning.curator.CuratorReviewConflict
import me.rerere.rikkahub.learning.curator.CuratorReviewListItem
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationResult
import me.rerere.rikkahub.learning.curator.CuratorRetentionArchiveCursor
import me.rerere.rikkahub.learning.curator.CuratorRetentionArchiveRequest
import me.rerere.rikkahub.learning.curator.CuratorSourceFence
import me.rerere.rikkahub.learning.curator.CuratorTargetDiff
import me.rerere.rikkahub.learning.curator.CuratorV1Canonicalizer
import me.rerere.rikkahub.learning.curator.DeterministicCuratorDeltaApplier
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-emulator only. Never run on the Honor AAK-AN00 primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomCuratorRetentionBoundaryTest {
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
    fun retentionPagesAt128_commitsOneCasRevision_andNeverArchivesProtectedStates() = runBlocking {
        val eligibleIds = (0 until ELIGIBLE_COUNT).map { index ->
            "candidate-retention-eligible-${index.toString().padStart(3, '0')}"
        }
        eligibleIds.forEach { candidateId ->
            val proposed = updateCandidate(candidateId).toProposedEntity(PROVENANCE, OLD_AT_MS)
            assertEquals(
                CuratorDeltaInsertResult.Inserted,
                database.curatorDeltaDao().insertProposed(proposed),
            )
        }
        PROTECTED_STATES.forEach { (candidateId, state) ->
            seedProtected(candidateId, state)
        }

        val storeA = RoomCuratorReviewRuntimeStore(database)
        val storeB = RoomCuratorReviewRuntimeStore(database)
        val firstPage = storeA.listRetentionArchivable(
            cutoffMs = CUTOFF_MS,
            after = CuratorRetentionArchiveCursor(),
            limit = RETENTION_PAGE_LIMIT,
        )
        assertEquals(RETENTION_PAGE_LIMIT, firstPage.size)
        assertEquals(eligibleIds.take(RETENTION_PAGE_LIMIT), firstPage.map { it.candidateId })

        val firstPageTail = firstPage.last()
        val secondPage = storeA.listRetentionArchivable(
            cutoffMs = CUTOFF_MS,
            after = CuratorRetentionArchiveCursor(
                updatedAtMs = firstPageTail.updatedAtMs,
                candidateId = firstPageTail.candidateId,
            ),
            limit = RETENTION_PAGE_LIMIT,
        )
        assertEquals(listOf(eligibleIds.last()), secondPage.map { it.candidateId })
        val boundedRows = firstPage + secondPage
        assertEquals(ELIGIBLE_COUNT, boundedRows.size)
        assertEquals(eligibleIds, boundedRows.map { it.candidateId })
        assertTrue(boundedRows.none { it.candidateId in PROTECTED_STATES.keys })

        val oversizedFailure = runCatching {
            storeA.listRetentionArchivable(
                cutoffMs = CUTOFF_MS,
                after = CuratorRetentionArchiveCursor(),
                limit = RETENTION_PAGE_LIMIT + 1,
            )
        }.exceptionOrNull()
        assertTrue(oversizedFailure is IllegalArgumentException)

        val firstRequest = boundedRows.first().toRetentionRequest(ARCHIVE_AT_MS)
        val raceResults = coroutineScope {
            listOf(
                async { storeA.archiveRetention(firstRequest) },
                async { storeB.archiveRetention(firstRequest) },
            ).awaitAll()
        }
        assertEquals(1, raceResults.count { it is CuratorReviewMutationResult.Applied })
        assertEquals(1, raceResults.count { it is CuratorReviewMutationResult.Duplicate })

        val firstHead = requireNotNull(database.curatorDeltaDao().find(firstRequest.candidateId))
        assertEquals(CuratorDeltaStoredState.ARCHIVED.name, firstHead.state)
        assertEquals(firstRequest.expectedStateVersion + 1L, firstHead.stateVersion)
        val firstRetentionRevision = requireNotNull(
            database.curatorDeltaDao().findRevision(firstHead.id, firstHead.stateVersion),
        )
        assertEquals(CuratorDeltaRevisionReason.ARCHIVED.name, firstRetentionRevision.reasonCode)
        assertEquals(CuratorDeltaRevisionActor.RETENTION.name, firstRetentionRevision.actor)

        val staleFenceRow = boundedRows[1]
        assertEquals(
            CuratorReviewMutationResult.Conflict(CuratorReviewConflict.FENCE_CONFLICT),
            storeA.archiveRetention(
                staleFenceRow.toRetentionRequest(ARCHIVE_AT_MS + 1L).copy(
                    expectedCandidateSha256 = "0".repeat(64),
                ),
            ),
        )
        assertEquals(
            1L,
            database.curatorDeltaDao().find(staleFenceRow.candidateId)?.stateVersion,
        )
        assertNull(database.curatorDeltaDao().findRevision(staleFenceRow.candidateId, 2L))

        boundedRows.drop(1).forEachIndexed { index, row ->
            assertTrue(
                storeA.archiveRetention(
                    row.toRetentionRequest(ARCHIVE_AT_MS + index + 1L),
                ) is CuratorReviewMutationResult.Applied,
            )
        }

        reopen()
        eligibleIds.forEach { candidateId ->
            val head = requireNotNull(database.curatorDeltaDao().find(candidateId))
            assertEquals(CuratorDeltaStoredState.ARCHIVED.name, head.state)
            assertEquals(2L, head.stateVersion)
            val revision = requireNotNull(
                database.curatorDeltaDao().findRevision(candidateId, head.stateVersion),
            )
            assertEquals(CuratorDeltaRevisionActor.RETENTION.name, revision.actor)
        }
        PROTECTED_STATES.forEach { (candidateId, expectedState) ->
            val head = requireNotNull(database.curatorDeltaDao().find(candidateId))
            assertEquals(expectedState.name, head.state)
            assertFalse(
                database.curatorDeltaDao()
                    .listRevisionPage(candidateId, Long.MAX_VALUE, 100)
                    .any { it.actor == CuratorDeltaRevisionActor.RETENTION.name },
            )
        }
    }

    private suspend fun seedProtected(
        candidateId: String,
        expectedState: CuratorDeltaStoredState,
    ) {
        val timeline = protectedTimeline(updateCandidate(candidateId))
        val terminalIndex = timeline.indexOfFirst { it.entity.state == expectedState.name }
        check(terminalIndex >= 0)
        val durableTimeline = timeline.take(terminalIndex + 1)
        assertTrue(
            database.curatorDeltaDao().insertCandidateIgnore(durableTimeline.last().entity) != -1L,
        )
        durableTimeline.forEach { point ->
            database.curatorDeltaDao().insertRevision(
                point.entity.toRevisionEntity(point.reason, point.actor),
            )
        }
    }

    private fun protectedTimeline(candidate: CuratorDeltaCandidate): List<TimelinePoint> {
        val proposed = candidate.toProposedEntity(PROVENANCE, OLD_AT_MS)
        val approved = proposed.copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            updatedAtMs = OLD_AT_MS + 1L,
        )
        val applying = approved.withApplyPlan(ready(candidate), OLD_AT_MS + 2L)
        val applied = applying.copy(
            stateVersion = 4L,
            state = CuratorDeltaStoredState.APPLIED.name,
            updatedAtMs = OLD_AT_MS + 3L,
        )
        val rollingBack = applied.copy(
            stateVersion = 5L,
            state = CuratorDeltaStoredState.ROLLING_BACK.name,
            updatedAtMs = OLD_AT_MS + 4L,
        )
        val rolledBack = rollingBack.copy(
            stateVersion = 6L,
            state = CuratorDeltaStoredState.ROLLED_BACK.name,
            updatedAtMs = OLD_AT_MS + 5L,
        )
        return listOf(
            TimelinePoint(
                proposed,
                CuratorDeltaRevisionReason.CREATED,
                CuratorDeltaRevisionActor.CURATOR_MODEL,
            ),
            TimelinePoint(
                approved,
                CuratorDeltaRevisionReason.USER_APPROVED,
                CuratorDeltaRevisionActor.USER,
            ),
            TimelinePoint(
                applying,
                CuratorDeltaRevisionReason.APPLY_STARTED,
                CuratorDeltaRevisionActor.APPLY_ENGINE,
            ),
            TimelinePoint(
                applied,
                CuratorDeltaRevisionReason.APPLY_COMMITTED,
                CuratorDeltaRevisionActor.APPLY_ENGINE,
            ),
            TimelinePoint(
                rollingBack,
                CuratorDeltaRevisionReason.ROLLBACK_STARTED,
                CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
            ),
            TimelinePoint(
                rolledBack,
                CuratorDeltaRevisionReason.ROLLBACK_COMMITTED,
                CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
            ),
        )
    }

    private fun updateCandidate(candidateId: String): CuratorDeltaCandidate.Update {
        val source = sourceHead()
        return CuratorDeltaCandidate.Update(
            candidateId = candidateId,
            source = CuratorSourceFence(
                source.policyId,
                source.scope,
                source.revision,
                source.artifactSha256,
            ),
            evidence = EVIDENCE,
            diffs = listOf(
                CuratorTargetDiff(
                    source.policyId,
                    listOf(
                        CuratorFieldDiff(
                            CuratorPolicyField.PROCEDURE,
                            CuratorV1Canonicalizer.fieldSha256(
                                CuratorPolicyField.PROCEDURE,
                                source.document.procedure,
                            ),
                            "bounded-after",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun ready(candidate: CuratorDeltaCandidate) = when (
        val result = DeterministicCuratorDeltaApplier().plan(
            candidate,
            { id -> sourceHead().takeIf { it.policyId == id } },
            { id -> EVIDENCE.singleOrNull { it.evidenceId == id } },
        )
    ) {
        is CuratorApplyResult.Ready -> result.plan
        else -> error("Protected Curator fixture did not produce an apply plan")
    }

    private fun sourceHead() = CuratorPolicyHead(
        "policy-retention-source",
        SCOPE,
        4L,
        CuratorPolicyState.REVIEWED,
        CuratorPolicyDocument(
            trigger = "trigger-before",
            procedure = "procedure-before",
            verification = "verification-before",
            boundary = "boundary-before",
            failureMode = "failure-before",
            applicableToolSchemaSha256 = listOf("a".repeat(64)),
        ),
    )

    private fun CuratorReviewListItem.toRetentionRequest(archivedAtMs: Long) =
        CuratorRetentionArchiveRequest(
            candidateId = candidateId,
            expectedState = state,
            expectedStateVersion = stateVersion,
            expectedCandidateSha256 = candidateSha256,
            expectedUpdatedAtMs = updatedAtMs,
            archivedAtMs = archivedAtMs,
        )

    private fun reopen() {
        database.close()
        database = openDatabase()
    }

    private fun openDatabase(): LearningDatabase = Room.databaseBuilder(
        context,
        LearningDatabase::class.java,
        DB_NAME,
    ).build()
}

private data class TimelinePoint(
    val entity: CuratorDeltaCandidateEntity,
    val reason: CuratorDeltaRevisionReason,
    val actor: CuratorDeltaRevisionActor,
)

private const val DB_NAME = "p5-curator-retention-boundary.db"
private const val ELIGIBLE_COUNT = 129
private const val RETENTION_PAGE_LIMIT = 128
private const val OLD_AT_MS = 10L
private const val CUTOFF_MS = 100L
private const val ARCHIVE_AT_MS = 1_000L
private val SCOPE = LearningScope.Assistant(
    Uuid.parse("73000000-0000-4000-8000-000000000001"),
)
private val EVIDENCE = listOf(
    CuratorEvidenceRef("evidence-retention-room", SCOPE, 2L, "e".repeat(64)),
)
private val PROVENANCE = CuratorDeltaProvenance("b".repeat(64), "c".repeat(64))
private val PROTECTED_STATES = linkedMapOf(
    "candidate-retention-protected-approved" to CuratorDeltaStoredState.APPROVED,
    "candidate-retention-protected-applying" to CuratorDeltaStoredState.APPLYING,
    "candidate-retention-protected-applied" to CuratorDeltaStoredState.APPLIED,
    "candidate-retention-protected-rolled-back" to CuratorDeltaStoredState.ROLLED_BACK,
)
