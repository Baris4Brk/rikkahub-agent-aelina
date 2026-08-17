package me.rerere.rikkahub.data.authority.policy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.assistant.SecondUserLearningAuthorityRevocationFence
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationPageRequest
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationPageResult
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class SecondUserPolicyGrantRevocationRoomTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactHeadAndAuditRevisionCommitTogetherAndReplayIsIdempotent() = runBlocking {
        val fence = SecondUserLearningAuthorityRevocationFence(
            assistantId = ASSISTANT_ID,
            conversationId = CONVERSATION_ID,
            authorityEpoch = 4L,
            frozenNowMs = 200L,
        )
        val exact = grant(fence.authoritySubjectId, "policy-exact")
        val foreignEpoch = grant(
            me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot.subjectId(
                ASSISTANT_ID,
                CONVERSATION_ID,
                5L,
            ),
            "policy-foreign",
        )
        database.learningPolicyGrantDao().apply {
            insertHead(exact)
            insertRevision(exact.toRevisionEntity())
            insertHead(foreignEpoch)
            insertRevision(foreignEpoch.toRevisionEntity())
        }
        val port = RoomSecondUserPolicyGrantRevocationPort(database)

        val first = port.revokeExactPage(
            SecondUserPolicyGrantRevocationPageRequest(fence, fence.authoritySubjectId),
        ) as SecondUserPolicyGrantRevocationPageResult.Ready
        val replay = port.revokeExactPage(
            SecondUserPolicyGrantRevocationPageRequest(fence, fence.authoritySubjectId),
        ) as SecondUserPolicyGrantRevocationPageResult.Ready

        assertEquals(1, first.page.revokedInTransaction)
        assertEquals(0, replay.page.revokedInTransaction)
        assertEquals(first.page.receipts, replay.page.receipts)
        assertNull(first.page.nextCursor)
        val revoked = requireNotNull(database.learningPolicyGrantDao().findHead(exact.grantId))
        assertEquals("AUTHORITY_REVOCATION", revoked.actor)
        assertEquals(PolicyGrantAuthorityState.REVOKED.name, revoked.state)
        assertEquals(2L, revoked.stateVersion)
        assertEquals(PolicyGrantReason.SECOND_USER_AUTHORITY_REVOKED.name, revoked.reasonCode)
        assertEquals(revoked.toRevisionEntity(), database.learningPolicyGrantDao().findRevision(
            revoked.grantId,
            2L,
        ))
        assertEquals(
            PolicyGrantAuthorityState.GRANTED.name,
            database.learningPolicyGrantDao().findHead(foreignEpoch.grantId)?.state,
        )
    }

    private fun grant(subjectId: String, policyId: String): LearningPolicyGrantEntity {
        val scope = LearningScope.AuthoritySubject(subjectId)
        return LearningPolicyGrantEntity(
            grantId = policyGrantId(STREAM_ID, scope, ASSISTANT_ID, policyId),
            sourceStreamId = STREAM_ID,
            policyId = policyId,
            policyRevision = 1L,
            artifactSha256 = "a".repeat(64),
            scopeKind = scope.kind.name,
            scopeId = scope.storageId,
            consumingAssistantId = ASSISTANT_ID.toString(),
            actor = "USER_REVIEW",
            state = PolicyGrantAuthorityState.GRANTED.name,
            stateVersion = 1L,
            grantedAtMs = 100L,
            revokedAtMs = null,
            reasonCode = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE.name,
            createdAtMs = 100L,
            updatedAtMs = 100L,
        )
    }

    private companion object {
        val ASSISTANT_ID: Uuid = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val CONVERSATION_ID: Uuid = Uuid.parse("20000000-0000-0000-0000-000000000002")
        const val STREAM_ID = "30000000-0000-0000-0000-000000000003"
    }
}

