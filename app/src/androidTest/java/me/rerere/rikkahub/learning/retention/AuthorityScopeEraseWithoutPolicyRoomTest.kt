package me.rerere.rikkahub.learning.retention

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.P1_SHADOW_ADMISSION_GATE_ID
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningDerivedDataEraseStore
import me.rerere.rikkahub.learning.storage.LearningPolicyShadowObservationEntity
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowResetReceipt
import me.rerere.rikkahub.learning.privacy.DurableScopeLearnedWorkflowEraseReceipt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-device/emulator only; never execute on the primary phone. */
@RunWith(AndroidJUnit4::class)
class AuthorityScopeEraseWithoutPolicyRoomTest {
    private lateinit var database: LearningDatabase

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
    fun authorityScopeWithZeroPoliciesStillErasesItsIndependentDerivedRoots() = runBlocking {
        val erasedScope = LearningScope.AuthoritySubject("authority-subject-erased")
        val retainedScope = LearningScope.AuthoritySubject("authority-subject-retained")
        database.policyShadowObservationDao().insertObservationIgnore(
            observation("a", erasedScope, observedAtMs = 10L),
        )
        database.policyShadowObservationDao().insertObservationIgnore(
            observation("b", retainedScope, observedAtMs = 11L),
        )
        assertEquals(0L, countRows("learning_policies"))

        val receipt = LearningDerivedDataEraseStore(
            database = database,
            learnedWorkflowErasePort = ExactScopeLearnedWorkflowErasePort { _, _ ->
                error("zero-candidate scope must not call the AppDatabase erase port")
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
        ).eraseScope(
            scope = erasedScope,
            frozenNowMs = 1_000L,
        )

        assertEquals(0, receipt.policies)
        assertEquals(1, receipt.policyShadowObservations)
        assertEquals(0L, countRows(
            "learning_policy_shadow_observations",
            "scope_kind = 'AUTHORITY_SUBJECT' AND scope_id = 'authority-subject-erased'",
        ))
        assertEquals(1L, countRows(
            "learning_policy_shadow_observations",
            "scope_kind = 'AUTHORITY_SUBJECT' AND scope_id = 'authority-subject-retained'",
        ))
    }

    private fun observation(
        suffix: String,
        scope: LearningScope,
        observedAtMs: Long,
    ) = LearningPolicyShadowObservationEntity(
        requestIdentity = "policy-shadow-request-v1:" + suffix.repeat(64),
        scopeKind = scope.kind.name,
        scopeId = scope.storageId,
        taskSignature = "task-signature-v1:$suffix",
        gateIdentity = P1_SHADOW_ADMISSION_GATE_ID,
        queryTermCount = 1,
        exactCandidateCount = 0,
        lexicalCandidateCount = 0,
        selectedCount = 0,
        estimatedTokens = 0,
        latencyMicros = 1L,
        dropReasonCountsWire = "NONE",
        observedAtMs = observedAtMs,
    )

    private fun countRows(table: String, predicate: String = "1 = 1"): Long =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE $predicate")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
}
