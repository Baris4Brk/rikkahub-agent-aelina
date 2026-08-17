package me.rerere.rikkahub.learning.grant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyGrantRebindCatchUpTest {
    @Test
    fun `bounded pages project granted and revoked then wrap to the first page`() = runBlocking {
        val granted = snapshot(POLICY_A, PolicyGrantAuthorityState.GRANTED)
        val revoked = snapshot(POLICY_B, PolicyGrantAuthorityState.REVOKED)
        val later = snapshot(POLICY_C, PolicyGrantAuthorityState.GRANTED)
            .copy(updatedAtEpochMs = 30L)
        val cursor = PolicyGrantAuthorityScanCursor(20L, revoked.grantId)
        val authority = FakeAuthoritySource(
            pages = mapOf(
                null to readyPage(
                    snapshots = listOf(granted, revoked),
                    nextCursor = cursor,
                    endReached = false,
                ),
                cursor to readyPage(
                    snapshots = listOf(later),
                    nextCursor = null,
                    endReached = true,
                ),
            ),
        )
        val projected = mutableListOf<PolicyGrantAuthoritySnapshot>()
        val catchUp = PolicyGrantRebindCatchUp(authority)
        val projector = PolicyGrantLifecycleProjector { head ->
            projected += head
            PolicyGrantLifecycleProjectionResult.Applied(head.policyId, 7L)
        }

        val first = catchUp.catchUp(STREAM, 1L, { true }, projector)
            as PolicyGrantRebindCatchUpResult.Completed
        val second = catchUp.catchUp(STREAM, 1L, { true }, projector)
            as PolicyGrantRebindCatchUpResult.Completed
        catchUp.catchUp(STREAM, 1L, { true }, projector)

        assertEquals(listOf(null, cursor, null), authority.requestedAfter)
        assertEquals(listOf(granted, revoked, later, granted, revoked), projected)
        assertTrue(projected.any { it.state == PolicyGrantAuthorityState.REVOKED })
        assertTrue(first.morePages)
        assertTrue(first.didWork)
        assertTrue(first.workMayRemain)
        assertFalse(second.morePages)
        assertFalse(second.workMayRemain)
    }

    @Test
    fun `rejected head and foreign stream do not block the rest of a complete page`() = runBlocking {
        val exact = snapshot(POLICY_A, PolicyGrantAuthorityState.GRANTED)
        val foreign = snapshot(
            POLICY_B,
            PolicyGrantAuthorityState.REVOKED,
            stream = OTHER_STREAM,
        )
        val authority = FakeAuthoritySource(
            pages = mapOf(
                null to readyPage(
                    snapshots = listOf(exact, foreign),
                    nextCursor = null,
                    endReached = true,
                    rejected = 2,
                ),
            ),
        )
        val projected = mutableListOf<String>()

        val result = PolicyGrantRebindCatchUp(authority).catchUp(
            expectedStreamId = STREAM,
            expectedReplayGeneration = 1L,
            isRuntimeCurrent = { true },
            projector = PolicyGrantLifecycleProjector { head ->
                projected += head.policyId
                PolicyGrantLifecycleProjectionResult.AlreadySatisfied(head.policyId, 4L)
            },
        ) as PolicyGrantRebindCatchUpResult.Completed

        assertEquals(listOf(POLICY_A), projected)
        assertEquals(4, result.scannedHeadCount)
        assertEquals(2, result.rejectedHeadCount)
        assertEquals(1, result.skippedOtherStreamHeadCount)
        assertEquals(1, result.projectedHeadCount)
        assertEquals(1, result.alreadySatisfiedHeadCount)
    }

    @Test
    fun `cancellation before page completion replays the whole page after restart`() = runBlocking {
        val first = snapshot(POLICY_A, PolicyGrantAuthorityState.GRANTED)
        val second = snapshot(POLICY_B, PolicyGrantAuthorityState.REVOKED)
        val authority = FakeAuthoritySource(
            pages = mapOf(
                null to readyPage(listOf(first, second), null, endReached = true),
            ),
        )
        val catchUp = PolicyGrantRebindCatchUp(authority)
        val calls = mutableListOf<String>()
        var cancelSecond = true
        val projector = PolicyGrantLifecycleProjector { head ->
            calls += head.policyId
            if (head.policyId == POLICY_B && cancelSecond) {
                cancelSecond = false
                throw CancellationException("process death")
            }
            PolicyGrantLifecycleProjectionResult.AlreadySatisfied(head.policyId, 8L)
        }

        try {
            catchUp.catchUp(STREAM, 1L, { true }, projector)
            fail("Cancellation must escape")
        } catch (_: CancellationException) {
            // Expected: cursor is committed only after the whole page.
        }
        val replay = catchUp.catchUp(STREAM, 1L, { true }, projector)

        assertTrue(replay is PolicyGrantRebindCatchUpResult.Completed)
        assertEquals(listOf(null, null), authority.requestedAfter)
        assertEquals(listOf(POLICY_A, POLICY_B, POLICY_A, POLICY_B), calls)
    }

    @Test
    fun `runtime fence and unavailable authority preserve the current cursor`() = runBlocking {
        val head = snapshot(POLICY_A, PolicyGrantAuthorityState.GRANTED)
        val cursor = PolicyGrantAuthorityScanCursor(20L, head.grantId)
        val authority = FakeAuthoritySource(
            pages = mapOf(
                null to readyPage(listOf(head), cursor, endReached = false),
                cursor to PolicyGrantAuthorityScanResult.Unavailable,
            ),
        )
        val catchUp = PolicyGrantRebindCatchUp(authority)
        val projector = PolicyGrantLifecycleProjector {
            PolicyGrantLifecycleProjectionResult.AlreadySatisfied(it.policyId, 1L)
        }

        catchUp.catchUp(STREAM, 1L, { true }, projector)
        assertEquals(
            PolicyGrantRebindCatchUpResult.Retry,
            catchUp.catchUp(STREAM, 1L, { true }, projector),
        )
        assertEquals(
            PolicyGrantRebindCatchUpResult.Retry,
            catchUp.catchUp(STREAM, 1L, { false }, projector),
        )
        assertEquals(listOf(null, cursor), authority.requestedAfter)
    }

    @Test
    fun `derived replay generation change restarts the grant scan`() = runBlocking {
        val head = snapshot(POLICY_A, PolicyGrantAuthorityState.GRANTED)
        val cursor = PolicyGrantAuthorityScanCursor(20L, head.grantId)
        val authority = FakeAuthoritySource(
            pages = mapOf(
                null to readyPage(listOf(head), cursor, endReached = false),
            ),
        )
        val catchUp = PolicyGrantRebindCatchUp(authority)
        val projector = PolicyGrantLifecycleProjector {
            PolicyGrantLifecycleProjectionResult.AlreadySatisfied(it.policyId, 1L)
        }

        catchUp.catchUp(STREAM, 1L, { true }, projector)
        catchUp.catchUp(STREAM, 2L, { true }, projector)

        assertEquals(listOf(null, null), authority.requestedAfter)
    }

    @Test
    fun `only retryable projection failures request immediate maintenance`() = runBlocking {
        val missing = snapshot(POLICY_A, PolicyGrantAuthorityState.GRANTED)
        val conflict = snapshot(POLICY_B, PolicyGrantAuthorityState.REVOKED)
        val authority = FakeAuthoritySource(
            pages = mapOf(
                null to readyPage(listOf(missing, conflict), null, endReached = true),
            ),
        )

        val result = PolicyGrantRebindCatchUp(authority).catchUp(
            STREAM,
            1L,
            { true },
            PolicyGrantLifecycleProjector { head ->
                PolicyGrantLifecycleProjectionResult.Pending(
                    if (head.policyId == POLICY_A) {
                        PolicyGrantLifecyclePendingReason.POLICY_MISSING
                    } else {
                        PolicyGrantLifecyclePendingReason.LIFECYCLE_CONFLICT
                    },
                )
            },
        ) as PolicyGrantRebindCatchUpResult.Completed

        assertEquals(2, result.pendingHeadCount)
        assertEquals(1, result.retryablePendingHeadCount)
        assertTrue(result.workMayRemain)
        assertFalse(result.didWork)
    }

    @Test
    fun `checkpoint gate requires exact complete and caught-up authority stream`() {
        val ready = checkpoint()

        assertEquals(
            STREAM,
            exactCompletePolicyGrantRebindStreamOrNull(ready, STREAM, 10L),
        )
        assertNull(exactCompletePolicyGrantRebindStreamOrNull(null, STREAM, 10L))
        assertNull(exactCompletePolicyGrantRebindStreamOrNull(ready, OTHER_STREAM, 10L))
        assertNull(
            exactCompletePolicyGrantRebindStreamOrNull(
                ready.copy(bootstrapState = LearningBootstrapState.RUNNING.name),
                STREAM,
                10L,
            ),
        )
        assertNull(
            exactCompletePolicyGrantRebindStreamOrNull(
                ready.copy(bootstrapHeadSeq = null),
                STREAM,
                10L,
            ),
        )
        assertNull(exactCompletePolicyGrantRebindStreamOrNull(ready, STREAM, 11L))
    }
}

private class FakeAuthoritySource(
    private val pages: Map<PolicyGrantAuthorityScanCursor?, PolicyGrantAuthorityScanResult>,
) : PolicyGrantAuthoritySource {
    val requestedAfter = mutableListOf<PolicyGrantAuthorityScanCursor?>()

    override suspend fun listExactGranted(
        scope: LearningScope,
        consumingAssistantId: Uuid,
        sourceStreamId: String,
        limit: Int,
    ): List<PolicyGrantAuthoritySnapshot> = emptyList()

    override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean = false

    override suspend fun listCurrentPage(
        after: PolicyGrantAuthorityScanCursor?,
        limit: Int,
    ): PolicyGrantAuthorityScanResult {
        requestedAfter += after
        return pages[after] ?: PolicyGrantAuthorityScanResult.Unavailable
    }
}

private fun readyPage(
    snapshots: List<PolicyGrantAuthoritySnapshot>,
    nextCursor: PolicyGrantAuthorityScanCursor?,
    endReached: Boolean,
    rejected: Int = 0,
): PolicyGrantAuthorityScanResult = PolicyGrantAuthorityScanResult.Ready(
    PolicyGrantAuthorityScanPage(
        snapshots = snapshots,
        nextCursor = nextCursor,
        scannedHeadCount = snapshots.size + rejected,
        rejectedHeadCount = rejected,
        endReached = endReached,
    ),
)

private fun snapshot(
    policyId: String,
    state: PolicyGrantAuthorityState,
    stream: String = STREAM,
): PolicyGrantAuthoritySnapshot {
    val revokedAt = 20L.takeIf { state == PolicyGrantAuthorityState.REVOKED }
    return PolicyGrantAuthoritySnapshot(
        grantId = policyGrantId(stream, SCOPE, CONSUMER, policyId),
        sourceStreamId = stream,
        scope = SCOPE,
        consumingAssistantId = CONSUMER,
        policyId = policyId,
        contentRevision = 3L,
        artifactSha256 = SHA,
        state = state,
        stateVersion = if (revokedAt == null) 1L else 2L,
        grantedAtEpochMs = 10L,
        revokedAtEpochMs = revokedAt,
        reason = if (revokedAt == null) {
            PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE
        } else {
            PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE
        },
        createdAtEpochMs = 10L,
        updatedAtEpochMs = revokedAt ?: 10L,
    )
}

private fun checkpoint() = LearningStreamCheckpointEntity(
    streamId = STREAM,
    lastContiguousSeq = 10L,
    lastSeenHeadSeq = 10L,
    replayGeneration = 1L,
    resetReason = null,
    bootstrapState = LearningBootstrapState.COMPLETE.name,
    bootstrapHeadSeq = 8L,
    coverageStartMs = 1L,
    commandCoverageStartMs = 1L,
    executionCoverageStartMs = 1L,
    updatedAtMs = 10L,
)

private val SCOPE = LearningScope.Assistant(
    Uuid.parse("40000000-0000-0000-0000-000000000004"),
)
private val CONSUMER = SCOPE.assistantId
private const val STREAM = "50000000-0000-0000-0000-000000000005"
private const val OTHER_STREAM = "60000000-0000-0000-0000-000000000006"
private const val POLICY_A = "rebind-policy-a"
private const val POLICY_B = "rebind-policy-b"
private const val POLICY_C = "rebind-policy-c"
private val SHA = "a".repeat(64)
