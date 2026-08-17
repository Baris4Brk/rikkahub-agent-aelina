package me.rerere.rikkahub.learning.storage.curator

import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.curator.CuratorApplyPlan
import me.rerere.rikkahub.learning.curator.CuratorApplyResult
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorDeltaOperation
import me.rerere.rikkahub.learning.curator.CuratorEvidenceRef
import me.rerere.rikkahub.learning.curator.CuratorFieldDiff
import me.rerere.rikkahub.learning.curator.CuratorPolicyDocument
import me.rerere.rikkahub.learning.curator.CuratorPolicyField
import me.rerere.rikkahub.learning.curator.CuratorPolicyHead
import me.rerere.rikkahub.learning.curator.CuratorPolicyState
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationRequest
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationResult
import me.rerere.rikkahub.learning.curator.CuratorSourceFence
import me.rerere.rikkahub.learning.curator.CuratorTargetDiff
import me.rerere.rikkahub.learning.curator.CuratorV1Canonicalizer
import me.rerere.rikkahub.learning.curator.DeterministicCuratorDeltaApplier
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-emulator only. Never run on the Honor AAK-AN00 primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomCuratorCrashRaceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
    fun applyingAndRollingBackPlans_surviveProcessDeathWithExactRollbackFences() = runBlocking {
        val candidate = updateCandidate()
        val plan = ready(candidate)
        val approved = candidate.toProposedEntity(provenance(), 10L).copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            updatedAtMs = 11L,
        )
        val applying = approved.withApplyPlan(plan, 12L)
        insertEntity("curator_delta_candidates", applying)

        database.close()
        database = openDatabase()
        val afterApplyCrash = requireNotNull(database.curatorDeltaDao().find(candidate.candidateId))
        assertEquals(CuratorDeltaStoredState.APPLYING.name, afterApplyCrash.state)
        assertEquals(plan, afterApplyCrash.decodeApplyPlanOrNull())

        // A rolling-back crash retains the same immutable plan and its expected applied heads;
        // restart must never re-plan from current mutable Policy rows.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE curator_delta_candidates SET state = ?, state_version = ?, updated_at_ms = ? WHERE id = ?",
            arrayOf<Any?>(
                CuratorDeltaStoredState.ROLLING_BACK.name,
                4L,
                13L,
                candidate.candidateId,
            ),
        )
        database.close()
        database = openDatabase()
        val afterRollbackCrash = requireNotNull(
            database.curatorDeltaDao().find(candidate.candidateId),
        )
        assertEquals(CuratorDeltaStoredState.ROLLING_BACK.name, afterRollbackCrash.state)
        assertEquals(
            plan.rollback.expectedAppliedHeads,
            afterRollbackCrash.decodeApplyPlanOrNull()!!.rollback.expectedAppliedHeads,
        )
        assertNotNull(RoomCuratorApplyRuntimeStore(database))
        val operationNames = RoomCuratorApplyRuntimeStore::class.java.methods.map { it.name }.toSet()
        assertTrue("applyApproved" in operationNames)
        assertTrue("rollbackApplied" in operationNames)
    }

    @Test
    fun twoRoomReviewWriters_onOneCandidateHaveOneCasWinnerAndOneExactReplay() = runBlocking {
        val candidate = updateCandidate()
        val proposed = candidate.toProposedEntity(provenance(), 20L)
        insertEntity("curator_delta_candidates", proposed)
        insertEntity(
            "curator_delta_revisions",
            proposed.toRevisionEntity(
                CuratorDeltaRevisionReason.CREATED,
                CuratorDeltaRevisionActor.CURATOR_MODEL,
            ),
        )
        val storeA = RoomCuratorReviewRuntimeStore(database)
        val storeB = RoomCuratorReviewRuntimeStore(database)
        val request = CuratorReviewMutationRequest(
            candidateId = candidate.candidateId,
            scope = SCOPE,
            expectedOperation = CuratorDeltaOperation.UPDATE_CANDIDATE,
            expectedState = CuratorDeltaStoredState.PROPOSED.name,
            expectedStateVersion = proposed.stateVersion,
            expectedCandidateSha256 = proposed.candidateSha256,
            expectedUpdatedAtMs = proposed.updatedAtMs,
            committedAtMs = 21L,
        )

        val results = coroutineScope {
            listOf(
                async { storeA.approve(request) },
                async { storeB.approve(request) },
            ).awaitAll()
        }

        assertEquals(1, results.count { it is CuratorReviewMutationResult.Applied })
        assertEquals(1, results.count { it is CuratorReviewMutationResult.Duplicate })
        val durable = requireNotNull(database.curatorDeltaDao().find(candidate.candidateId))
        assertEquals(CuratorDeltaStoredState.APPROVED.name, durable.state)
        assertEquals(proposed.stateVersion + 1L, durable.stateVersion)
        assertEquals(
            1,
            database.curatorDeltaDao().listRevisionPage(candidate.candidateId, Long.MAX_VALUE, 100)
                .count { it.state == CuratorDeltaStoredState.APPROVED.name },
        )
    }

    private fun openDatabase(): LearningDatabase = Room.databaseBuilder(
        context,
        LearningDatabase::class.java,
        DB_NAME,
    ).build()

    private fun insertEntity(table: String, entity: Any) {
        val values = ContentValues()
        entity.javaClass.declaredFields
            .filterNot { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                    field.name.startsWith("$") || field.isSynthetic
            }
            .forEach { field ->
                field.isAccessible = true
                values.putRoomValue(field.name.camelToSnake(), field.get(entity))
            }
        val rowId = database.openHelper.writableDatabase.insert(table, 0, values)
        check(rowId != -1L) { "Failed to seed $table crash snapshot" }
    }

    private fun updateCandidate(): CuratorDeltaCandidate.Update {
        val source = sourceHead()
        return CuratorDeltaCandidate.Update(
            candidateId = "candidate-room-crash",
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

    private fun ready(candidate: CuratorDeltaCandidate): CuratorApplyPlan {
        val result = DeterministicCuratorDeltaApplier().plan(
            candidate,
            { id -> sourceHead().takeIf { it.policyId == id } },
            { id -> EVIDENCE.singleOrNull { it.evidenceId == id } },
        )
        check(result is CuratorApplyResult.Ready)
        return result.plan
    }

    private fun sourceHead() = CuratorPolicyHead(
        "policy-room-source",
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

    private fun provenance() = CuratorDeltaProvenance("b".repeat(64), "c".repeat(64))
}

private fun ContentValues.putRoomValue(column: String, value: Any?) {
    when (value) {
        null -> putNull(column)
        is String -> put(column, value)
        is Long -> put(column, value)
        is Int -> put(column, value)
        is Boolean -> put(column, value)
        is ByteArray -> put(column, value)
        is Double -> put(column, value)
        is Float -> put(column, value)
        else -> error("Unsupported Room seed value for $column: ${value::class.java.name}")
    }
}

private fun String.camelToSnake(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .lowercase()

private const val DB_NAME = "p5-curator-crash-race.db"
private val SCOPE = LearningScope.Assistant(
    Uuid.parse("11111111-1111-4111-8111-111111111111"),
)
private val EVIDENCE = listOf(
    CuratorEvidenceRef("evidence-room-1", SCOPE, 2L, "e".repeat(64)),
)
