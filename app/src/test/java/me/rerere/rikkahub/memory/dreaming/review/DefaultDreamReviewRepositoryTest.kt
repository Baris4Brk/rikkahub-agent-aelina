package me.rerere.rikkahub.memory.dreaming.review

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.dreaming.DreamingTestFixtures
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDreamReviewRepositoryTest {
    @Test
    fun `correction uses trusted scope and origin then returns rebuild pending after phase two conflict`() =
        runBlocking {
            val target = target()
            val store = FakeStore().apply {
                validation = DreamReviewReadResult.Found(
                    DreamValidatedCorrectionTarget(target, capturedOriginAssistantId = "trusted-assistant"),
                )
                markResult = DreamReviewStoreMutationResult.Conflict(DreamReviewConflict.MEMORY_EPOCH)
            }
            val authority = FakeAuthority(DreamAuthorityCorrectionResult.Applied(42, 3, 9))
            val repository = repository(store, authority)

            val result = repository.correct(
                DreamCorrectionDraft(
                    target = target,
                    title = "Corrected",
                    content = "Correct authority content",
                    kind = MemoryKind.OTHER,
                ),
            )

            assertEquals(DreamCorrectionResult.AuthorityAppliedRebuildPending(42, 3), result)
            val request = requireNotNull(authority.lastRequest)
            assertEquals(target.fence.scopeId, request.scopeId)
            assertEquals("trusted-assistant", request.capturedOriginAssistantId)
            assertEquals(MemoryApprovalSource.USER_REVIEWED, request.approvalSource)
            assertEquals(DREAM_CORRECTION_SOURCE_TYPE, request.sourceType)
            assertEquals(1f, request.confidence)
            assertEquals(request.mutationId, store.lastMarkCommand?.mutationId)
            assertEquals(9L, store.lastMarkCommand?.expectedAuthorityMemoryEpoch)
        }

    @Test
    fun `authority conflict never performs snapshot-only correction`() = runBlocking {
        val target = target()
        val store = FakeStore().apply {
            validation = DreamReviewReadResult.Found(DreamValidatedCorrectionTarget(target, null))
        }
        val repository = repository(store, FakeAuthority(DreamAuthorityCorrectionResult.Conflict))

        val result = repository.correct(DreamCorrectionDraft(target, null, "corrected"))

        assertEquals(DreamCorrectionResult.Conflict(null), result)
        assertEquals(null, store.lastMarkCommand)
    }

    @Test
    fun `stale preflight stops before authority mutation`() = runBlocking {
        val store = FakeStore().apply {
            validation = DreamReviewReadResult.Conflict(DreamReviewConflict.DREAM_REVISION)
        }
        val authority = FakeAuthority(DreamAuthorityCorrectionResult.Applied(42, 3, 9))
        val result = repository(store, authority).correct(
            DreamCorrectionDraft(target(), null, "corrected"),
        )

        assertEquals(DreamCorrectionResult.Conflict(DreamReviewConflict.DREAM_REVISION), result)
        assertEquals(null, authority.lastRequest)
        assertEquals(null, store.lastMarkCommand)
    }

    @Test
    fun `an extra authority epoch cannot be relaxed into a derived correction`() = runBlocking {
        val target = target()
        val store = FakeStore().apply {
            validation = DreamReviewReadResult.Found(DreamValidatedCorrectionTarget(target, null))
        }
        val result = repository(
            store,
            FakeAuthority(DreamAuthorityCorrectionResult.Applied(42, 3, 10)),
        ).correct(DreamCorrectionDraft(target, null, "corrected"))

        assertEquals(DreamCorrectionResult.AuthorityAppliedRebuildPending(42, 3), result)
        assertEquals(null, store.lastMarkCommand)
    }

    @Test
    fun `evidence reveal is always capped at five hundred characters`() = runBlocking {
        val store = FakeStore()
        val reference = evidenceReference()

        repository(store, FakeAuthority(DreamAuthorityCorrectionResult.Conflict)).revealEvidence(reference)

        assertEquals(DREAM_EVIDENCE_EXCERPT_MAX_CHARS, store.lastEvidenceMaxChars)
        assertEquals(reference, store.lastEvidenceReference)
    }

    @Test
    fun `reject and clear use distinct generated mutation ids and one frozen clock each`() = runBlocking {
        val ids = ArrayDeque(
            listOf(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ),
        )
        val store = FakeStore()
        val repository = DefaultDreamReviewRepository(
            store = store,
            authority = FakeAuthority(DreamAuthorityCorrectionResult.Conflict),
            nowMs = { 123L },
            mutationIdGenerator = { ids.removeFirst() },
        )

        repository.reject(target())
        repository.clearDerived(target().fence)

        assertEquals(123L, store.lastRejectCommand?.nowEpochMs)
        assertEquals(123L, store.lastClearCommand?.nowEpochMs)
        assertFalse(store.lastRejectCommand?.mutationId == store.lastClearCommand?.mutationId)
        assertTrue(ids.isEmpty())
    }

    private fun repository(store: FakeStore, authority: FakeAuthority) =
        DefaultDreamReviewRepository(
            store = store,
            authority = authority,
            nowMs = { 123L },
            mutationIdGenerator = { "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" },
        )

    private fun target() = DreamClaimMutationTarget(
        fence = DreamReviewFence(
            scopeId = DreamingTestFixtures.scope,
            expectedMemoryEpoch = 8,
            expectedLastAppliedMemoryEpoch = 8,
            expectedDreamRevision = 4,
            expectedActiveSnapshotId = DreamingTestFixtures.SNAPSHOT_ID,
        ),
        claimId = DreamingTestFixtures.CLAIM_ID,
        expectedClaimRevision = 1,
    )

    private fun evidenceReference() = DreamEvidenceReference(
        scopeId = DreamingTestFixtures.scope,
        claimId = DreamingTestFixtures.CLAIM_ID,
        claimRevision = 1,
        memoryId = "42",
        memoryRevision = 2,
        expectedSemanticHash = me.rerere.rikkahub.memory.dreaming.model.DreamSha256("1".repeat(64)),
        expectedSourceManifestHash = me.rerere.rikkahub.memory.dreaming.model.DreamSha256("2".repeat(64)),
        supportType = me.rerere.rikkahub.memory.dreaming.model.DreamSupportType.SUPPORTS,
    )

    private class FakeAuthority(
        private val result: DreamAuthorityCorrectionResult,
    ) : DreamAuthorityCorrectionPort {
        var lastRequest: DreamAuthorityCorrectionRequest? = null

        override suspend fun create(request: DreamAuthorityCorrectionRequest): DreamAuthorityCorrectionResult {
            lastRequest = request
            return result
        }
    }

    private class FakeStore : DreamReviewStore {
        var validation: DreamReviewReadResult<DreamValidatedCorrectionTarget> = DreamReviewReadResult.NotFound
        var markResult: DreamReviewStoreMutationResult = DreamReviewStoreMutationResult.InvalidState
        var lastMarkCommand: DreamMarkCorrectedCommand? = null
        var lastRejectCommand: DreamRejectCommand? = null
        var lastClearCommand: DreamClearDerivedCommand? = null
        var lastEvidenceReference: DreamEvidenceReference? = null
        var lastEvidenceMaxChars: Int? = null

        override fun observeProjection(scopeId: DreamScopeId): Flow<DreamReviewProjection> = emptyFlow()

        override suspend fun readClaim(
            target: DreamClaimMutationTarget,
        ): DreamReviewReadResult<DreamClaimDetail> = DreamReviewReadResult.NotFound

        override suspend fun readEvidence(
            reference: DreamEvidenceReference,
            maxChars: Int,
        ): DreamEvidenceRevealResult {
            lastEvidenceReference = reference
            lastEvidenceMaxChars = maxChars
            return DreamEvidenceRevealResult.NotFound
        }

        override suspend fun validateTarget(
            target: DreamClaimMutationTarget,
        ): DreamReviewReadResult<DreamValidatedCorrectionTarget> = validation

        override suspend fun reject(command: DreamRejectCommand): DreamReviewStoreMutationResult {
            lastRejectCommand = command
            return DreamReviewStoreMutationResult.InvalidState
        }

        override suspend fun markCorrected(command: DreamMarkCorrectedCommand): DreamReviewStoreMutationResult {
            lastMarkCommand = command
            return markResult
        }

        override suspend fun clearDerived(command: DreamClearDerivedCommand): DreamReviewStoreMutationResult {
            lastClearCommand = command
            return DreamReviewStoreMutationResult.AlreadyClear
        }
    }
}
