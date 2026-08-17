package me.rerere.rikkahub.data.authority.policy

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.grant.PolicyGrantConflict
import me.rerere.rikkahub.learning.grant.PolicyGrantFence
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewCommand
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewResult
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import kotlin.uuid.Uuid

class RoomPolicyGrantServiceTest {
    @After
    fun clearSecondUserAuthorityRegistry() {
        SecondUserAuthorityRegistry.install(null)
    }

    @Test
    fun `initial review atomically writes deterministic head and receipt`() = runBlocking {
        val store = FakeGrantStore()
        val service = RoomPolicyGrantService(store)

        val result = service.review(command())

        val applied = result as PolicyGrantReviewResult.Applied
        assertEquals(policyGrantId(STREAM, SCOPE, CONSUMER, POLICY), applied.snapshot.grantId)
        assertEquals("USER_REVIEW", store.heads.values.single().actor)
        assertEquals(1L, applied.snapshot.stateVersion)
        assertEquals(1, store.revisions.size)
        assertEquals(store.heads.values.single().toRevisionEntity(), store.revisions.values.single())
    }

    @Test
    fun `reserved second-user grant requires the exact currently active epoch`() = runBlocking {
        val conversationId = Uuid.parse("40000000-0000-0000-0000-000000000004")
        val current = SecondUserAdmissionSnapshot.create(
            assistantId = CONSUMER,
            conversationId = conversationId,
            authorityEpoch = 9L,
            origin = ToolCallOrigin.LocalChat,
        )
        SecondUserAuthorityRegistry.install(current)
        val store = FakeGrantStore()
        val service = RoomPolicyGrantService(store)

        val oldEpoch = service.review(
            command().copy(
                scope = LearningScope.AuthoritySubject(
                    SecondUserAdmissionSnapshot.subjectId(
                        CONSUMER,
                        conversationId,
                        authorityEpoch = 8L,
                    ),
                ),
            ),
        )
        val currentEpoch = service.review(
            command().copy(scope = LearningScope.AuthoritySubject(current.subjectId)),
        )

        assertEquals(
            PolicyGrantConflict.AUTHORITY_SUBJECT_INACTIVE,
            (oldEpoch as PolicyGrantReviewResult.Conflict).reason,
        )
        assertTrue(currentEpoch is PolicyGrantReviewResult.Applied)
        assertEquals(1, store.heads.size)
    }

    @Test
    fun `legacy second-user principal can never receive a new policy grant`() = runBlocking {
        val result = RoomPolicyGrantService(FakeGrantStore()).review(
            command().copy(
                scope = LearningScope.AuthoritySubject(
                    "local_second_user:$CONSUMER:40000000-0000-0000-0000-000000000004",
                ),
            ),
        )

        assertEquals(
            PolicyGrantConflict.AUTHORITY_SUBJECT_INACTIVE,
            (result as PolicyGrantReviewResult.Conflict).reason,
        )
    }

    @Test
    fun `same target snapshot is duplicate even after first CAS consumed`() = runBlocking {
        val store = FakeGrantStore()
        val service = RoomPolicyGrantService(store)
        val frozen = command(expected = 0L, now = 10L)
        service.review(frozen)

        val duplicate = service.review(frozen)

        assertTrue(duplicate is PolicyGrantReviewResult.Duplicate)
        assertEquals(1, store.revisions.size)
        assertEquals(1L, store.heads.values.single().stateVersion)
    }

    @Test
    fun `revoke and regrant remain contiguous and exact`() = runBlocking {
        val store = FakeGrantStore()
        val service = RoomPolicyGrantService(store)
        service.review(command(now = 10L))

        val revoked = service.review(
            command(
                fence = PolicyGrantFence.REVOKE,
                expected = 1L,
                now = 20L,
                reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
            ),
        ) as PolicyGrantReviewResult.Applied
        val regranted = service.review(command(expected = 2L, now = 30L))
            as PolicyGrantReviewResult.Applied

        assertEquals("REVOKED", store.revisions.getValue(revoked.snapshot.grantId to 2L).state)
        assertEquals(3L, regranted.snapshot.stateVersion)
        assertEquals(30L, regranted.snapshot.grantedAtEpochMs)
        assertEquals(null, regranted.snapshot.revokedAtEpochMs)
        assertEquals(listOf(1L, 2L, 3L), store.revisions.keys.map { it.second }.sorted())
    }

    @Test
    fun `update requires a complete revision artifact identity change`() = runBlocking {
        val store = FakeGrantStore()
        val service = RoomPolicyGrantService(store)
        service.review(command(now = 10L))

        val splitIdentity = service.review(
            command(
                fence = PolicyGrantFence.UPDATE_EXACT_POLICY,
                revision = 1L,
                artifact = SHA_B,
                expected = 1L,
                now = 20L,
                reason = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE,
            ),
        ) as PolicyGrantReviewResult.Conflict
        val updated = service.review(
            command(
                fence = PolicyGrantFence.UPDATE_EXACT_POLICY,
                revision = 2L,
                artifact = SHA_B,
                expected = 1L,
                now = 20L,
                reason = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE,
            ),
        ) as PolicyGrantReviewResult.Applied

        assertEquals(PolicyGrantConflict.POLICY_REVISION_IDENTITY_MISMATCH, splitIdentity.reason)
        assertEquals(2L, updated.snapshot.contentRevision)
        assertEquals(SHA_B, updated.snapshot.artifactSha256)
        assertEquals(2, store.revisions.size)
    }

    @Test
    fun `stale CAS clock rollback and missing audit all fail closed`() = runBlocking {
        val store = FakeGrantStore()
        val service = RoomPolicyGrantService(store)
        service.review(command(now = 10L))

        val stale = service.review(
            command(
                fence = PolicyGrantFence.UPDATE_EXACT_POLICY,
                revision = 2L,
                artifact = SHA_B,
                expected = 0L,
                now = 20L,
                reason = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE,
            ),
        ) as PolicyGrantReviewResult.Conflict
        val rollback = service.review(
            command(
                fence = PolicyGrantFence.UPDATE_EXACT_POLICY,
                revision = 2L,
                artifact = SHA_B,
                expected = 1L,
                now = 9L,
                reason = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE,
            ),
        ) as PolicyGrantReviewResult.Conflict
        store.revisions.clear()
        val missingAudit = service.review(
            command(
                fence = PolicyGrantFence.REVOKE,
                expected = 1L,
                now = 20L,
                reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
            ),
        ) as PolicyGrantReviewResult.Conflict

        assertEquals(PolicyGrantConflict.STALE_STATE_VERSION, stale.reason)
        assertEquals(PolicyGrantConflict.CLOCK_ROLLBACK, rollback.reason)
        assertEquals(PolicyGrantConflict.AUDIT_REVISION_MISSING, missingAudit.reason)
        assertEquals("GRANTED", store.heads.values.single().state)
    }

    @Test
    fun `state version overflow is rejected without wrapping`() = runBlocking {
        val head = entity(stateVersion = Long.MAX_VALUE, now = 10L)
        val store = FakeGrantStore(head)
        val service = RoomPolicyGrantService(store)

        val result = service.review(
            command(
                fence = PolicyGrantFence.UPDATE_EXACT_POLICY,
                revision = 2L,
                artifact = SHA_B,
                expected = Long.MAX_VALUE,
                now = 20L,
                reason = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE,
            ),
        ) as PolicyGrantReviewResult.Conflict

        assertEquals(PolicyGrantConflict.STATE_VERSION_OVERFLOW, result.reason)
        assertEquals(Long.MAX_VALUE, store.heads.values.single().stateVersion)
    }

    @Test
    fun `receipt failure rolls back the head transaction`() = runBlocking {
        val store = FakeGrantStore().apply { failNextRevision = true }
        val service = RoomPolicyGrantService(store)

        val result = service.review(command()) as PolicyGrantReviewResult.Conflict

        assertEquals(PolicyGrantConflict.STORAGE_FAILURE, result.reason)
        assertTrue(store.heads.isEmpty())
        assertTrue(store.revisions.isEmpty())
    }

    @Test
    fun `grant id collision with another exact tuple is inert`() = runBlocking {
        val expectedId = policyGrantId(STREAM, SCOPE, CONSUMER, POLICY)
        val foreign = entity(
            grantId = expectedId,
            stream = OTHER_STREAM,
            now = 10L,
        )
        val store = FakeGrantStore(foreign)
        val service = RoomPolicyGrantService(store)

        val result = service.review(command(expected = 1L, now = 20L))
            as PolicyGrantReviewResult.Conflict

        assertEquals(PolicyGrantConflict.IDENTITY_MISMATCH, result.reason)
        assertEquals(1, store.revisions.size)
        assertEquals(OTHER_STREAM, store.heads.getValue(expectedId).sourceStreamId)
    }

    private class FakeGrantStore(seed: LearningPolicyGrantEntity? = null) :
        PolicyGrantTransactionStore,
        PolicyGrantTransaction {
        val heads = linkedMapOf<String, LearningPolicyGrantEntity>()
        val revisions = linkedMapOf<Pair<String, Long>, LearningPolicyGrantRevisionEntity>()
        var failNextRevision = false

        init {
            seed?.let {
                heads[it.grantId] = it
                revisions[it.grantId to it.stateVersion] = it.toRevisionEntity()
            }
        }

        override suspend fun <T> inTransaction(
            block: suspend PolicyGrantTransaction.() -> T,
        ): T {
            val beforeHeads = LinkedHashMap(heads)
            val beforeRevisions = LinkedHashMap(revisions)
            return try {
                block(this)
            } catch (failure: Throwable) {
                heads.clear()
                heads.putAll(beforeHeads)
                revisions.clear()
                revisions.putAll(beforeRevisions)
                throw failure
            }
        }

        override suspend fun findHead(grantId: String): LearningPolicyGrantEntity? = heads[grantId]

        override suspend fun insertHead(entity: LearningPolicyGrantEntity) {
            check(heads.putIfAbsent(entity.grantId, entity) == null)
        }

        override suspend fun updateHeadFenced(
            previous: LearningPolicyGrantEntity,
            next: LearningPolicyGrantEntity,
        ): Boolean {
            if (heads[previous.grantId] != previous) return false
            heads[previous.grantId] = next
            return true
        }

        override suspend fun insertRevision(entity: LearningPolicyGrantRevisionEntity) {
            if (failNextRevision) {
                failNextRevision = false
                error("synthetic journal failure")
            }
            check(revisions.putIfAbsent(entity.grantId to entity.stateVersion, entity) == null)
        }

        override suspend fun findRevision(
            grantId: String,
            stateVersion: Long,
        ): LearningPolicyGrantRevisionEntity? = revisions[grantId to stateVersion]
    }
}

private fun command(
    fence: PolicyGrantFence = PolicyGrantFence.GRANT,
    revision: Long = 1L,
    artifact: String = SHA_A,
    expected: Long = 0L,
    now: Long = 10L,
    reason: PolicyGrantReason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
): PolicyGrantReviewCommand = PolicyGrantReviewCommand(
    fence = fence,
    sourceStreamId = STREAM,
    scope = SCOPE,
    consumingAssistantId = CONSUMER,
    policyId = POLICY,
    contentRevision = revision,
    artifactSha256 = artifact,
    expectedGrantStateVersion = expected,
    frozenNowEpochMs = now,
    reason = reason,
)

private fun entity(
    grantId: String = policyGrantId(STREAM, SCOPE, CONSUMER, POLICY),
    stream: String = STREAM,
    stateVersion: Long = 1L,
    now: Long = 10L,
): LearningPolicyGrantEntity = LearningPolicyGrantEntity(
    grantId = grantId,
    sourceStreamId = stream,
    policyId = POLICY,
    policyRevision = 1L,
    artifactSha256 = SHA_A,
    scopeKind = SCOPE.kind.name,
    scopeId = SCOPE.storageId,
    consumingAssistantId = CONSUMER.toString(),
    actor = "USER_REVIEW",
    state = "GRANTED",
    stateVersion = stateVersion,
    grantedAtMs = now,
    revokedAtMs = null,
    reasonCode = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE.name,
    createdAtMs = now,
    updatedAtMs = now,
)

private val SCOPE = LearningScope.Assistant(Uuid.parse("10000000-0000-0000-0000-000000000001"))
private val CONSUMER = SCOPE.assistantId
private const val STREAM = "20000000-0000-0000-0000-000000000002"
private const val OTHER_STREAM = "30000000-0000-0000-0000-000000000003"
private const val POLICY = "policy-1"
private val SHA_A = "a".repeat(64)
private val SHA_B = "b".repeat(64)
