package me.rerere.rikkahub.assistant

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SecondUserLearningAuthorityRevocationTest {
    private val assistantId = Uuid.parse("10000000-0000-0000-0000-000000000001")
    private val conversationId = Uuid.parse("20000000-0000-0000-0000-000000000002")
    private val fence = SecondUserLearningAuthorityRevocationFence(
        assistantId = assistantId,
        conversationId = conversationId,
        authorityEpoch = 7L,
        frozenNowMs = 900L,
    )

    @Test
    fun `fence deterministically owns only current epoch and its legacy principal`() {
        assertEquals(
            "local-second-user:v1:$assistantId:$conversationId:7",
            fence.authoritySubjectId,
        )
        assertEquals(
            listOf(
                fence.authoritySubjectId,
                "local_second_user:$assistantId:$conversationId",
            ),
            fence.exactAuthoritySubjectIds,
        )
        assertTrue(!fence.ownsExactSubject(
            SecondUserAdmissionSnapshot.subjectId(assistantId, conversationId, 8L),
        ))
    }

    @Test
    fun `saga pages both exact subjects and drains derived batches before completing`() =
        runBlocking {
            val grants = RecordingGrantPort(
                mutableMapOf(
                    fence.authoritySubjectId to listOf(
                        receipt(fence.authoritySubjectId, "policy-a"),
                        receipt(fence.authoritySubjectId, "policy-b"),
                    ),
                    fence.legacyAuthoritySubjectId to listOf(
                        receipt(fence.legacyAuthoritySubjectId, "policy-c"),
                    ),
                ),
            )
            val derived = RecordingDerivedPort(batchesBeforeComplete = 2)

            val result = SecondUserLearningAuthorityRevocationSaga(grants, derived).resume(fence)

            val completed = result as SecondUserLearningAuthorityRevocationResult.Completed
            assertEquals(3, completed.summary.scannedGrantHeads)
            assertEquals(3, completed.summary.revokedGrantHeads)
            assertEquals(4, completed.summary.policiesMadeStale)
            assertEquals(2, completed.summary.workflowCandidatesMadeStale)
            assertEquals(fence.exactAuthoritySubjectIds, grants.subjectsSeen)
            assertEquals(
                listOf(
                    fence.authoritySubjectId,
                    fence.authoritySubjectId,
                    fence.legacyAuthoritySubjectId,
                    fence.legacyAuthoritySubjectId,
                ),
                derived.subjectsSeen,
            )
        }

    @Test
    fun `unavailable derived projection remains pending and replay starts from immutable head`() =
        runBlocking {
            val receipts = mutableMapOf(
                fence.authoritySubjectId to listOf(receipt(fence.authoritySubjectId, "policy-a")),
            )
            val grants = RecordingGrantPort(receipts)
            var unavailable = true
            val derived = SecondUserDerivedAuthorityInvalidationPort {
                if (unavailable) SecondUserDerivedAuthorityInvalidationResult.Unavailable
                else SecondUserDerivedAuthorityInvalidationResult.Ready(
                    SecondUserDerivedAuthorityInvalidationBatch(1, 1, complete = true),
                )
            }
            val saga = SecondUserLearningAuthorityRevocationSaga(grants, derived)

            assertEquals(SecondUserLearningAuthorityRevocationResult.Pending, saga.resume(fence))
            unavailable = false
            val replay = saga.resume(fence)

            assertTrue(replay is SecondUserLearningAuthorityRevocationResult.Completed)
            // The already-revoked AppDatabase head remains part of the replay page.
            assertEquals(2, grants.subjectsSeen.count { it == fence.authoritySubjectId })
        }

    @Test
    fun `a non-progressing incomplete adapter fails closed instead of spinning`() = runBlocking {
        val result = SecondUserLearningAuthorityRevocationSaga(
            grants = RecordingGrantPort(mutableMapOf()),
            derived = SecondUserDerivedAuthorityInvalidationPort {
                SecondUserDerivedAuthorityInvalidationResult.Ready(
                    SecondUserDerivedAuthorityInvalidationBatch(0, 0, complete = false),
                )
            },
        ).resume(fence)

        assertEquals(SecondUserLearningAuthorityRevocationResult.Pending, result)
    }

    @Test
    fun `page cursor cannot be forged outside exact subject`() {
        val foreign = SecondUserAdmissionSnapshot.subjectId(
            assistantId,
            conversationId,
            authorityEpoch = 8L,
        )
        val failure = runCatching {
            SecondUserPolicyGrantRevocationPageRequest(fence, foreign)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(
            SecondUserPolicyGrantRevocationPage(
                receipts = emptyList(),
                nextCursor = null,
                revokedInTransaction = 0,
            ).nextCursor,
        )
    }

    private fun receipt(subjectId: String, policyId: String): PolicyGrantAuthoritySnapshot {
        val scope = LearningScope.AuthoritySubject(subjectId)
        return PolicyGrantAuthoritySnapshot(
            grantId = policyGrantId(STREAM_ID, scope, assistantId, policyId),
            sourceStreamId = STREAM_ID,
            scope = scope,
            consumingAssistantId = assistantId,
            policyId = policyId,
            contentRevision = 1L,
            artifactSha256 = "a".repeat(64),
            state = PolicyGrantAuthorityState.REVOKED,
            stateVersion = 2L,
            grantedAtEpochMs = 100L,
            revokedAtEpochMs = 900L,
            reason = PolicyGrantReason.SECOND_USER_AUTHORITY_REVOKED,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 900L,
        )
    }

    private class RecordingGrantPort(
        private val rows: MutableMap<String, List<PolicyGrantAuthoritySnapshot>>,
    ) : SecondUserPolicyGrantRevocationPort {
        val subjectsSeen = mutableListOf<String>()

        override suspend fun revokeExactPage(
            request: SecondUserPolicyGrantRevocationPageRequest,
        ): SecondUserPolicyGrantRevocationPageResult {
            subjectsSeen += request.authoritySubjectId
            val page = rows[request.authoritySubjectId].orEmpty()
                .filter { it.grantId > request.cursor.afterGrantId }
                .sortedBy { it.grantId }
                .take(request.limit)
            return SecondUserPolicyGrantRevocationPageResult.Ready(
                SecondUserPolicyGrantRevocationPage(
                    receipts = page,
                    nextCursor = null,
                    revokedInTransaction = page.size,
                ),
            )
        }
    }

    private class RecordingDerivedPort(
        private val batchesBeforeComplete: Int,
    ) : SecondUserDerivedAuthorityInvalidationPort {
        val subjectsSeen = mutableListOf<String>()
        private val counts = mutableMapOf<String, Int>()

        override suspend fun invalidateExactAuthorityBatch(
            request: SecondUserDerivedAuthorityInvalidationRequest,
        ): SecondUserDerivedAuthorityInvalidationResult {
            subjectsSeen += request.authoritySubjectId
            val count = counts.getOrDefault(request.authoritySubjectId, 0) + 1
            counts[request.authoritySubjectId] = count
            return SecondUserDerivedAuthorityInvalidationResult.Ready(
                SecondUserDerivedAuthorityInvalidationBatch(
                    policiesMadeStale = 1,
                    workflowCandidatesMadeStale = if (count == 1) 1 else 0,
                    complete = count >= batchesBeforeComplete,
                ),
            )
        }
    }

    private companion object {
        const val STREAM_ID = "30000000-0000-0000-0000-000000000003"
    }
}
