package me.rerere.rikkahub.data.authority.policy

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanCursor
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanResult
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RoomPolicyGrantAuthoritySourceTest {
    @Test
    fun `list returns only validated exact granted heads with matching receipts`() = runBlocking {
        val exact = grant(POLICY_A)
        val revoked = grant(POLICY_B, state = "REVOKED", version = 2L)
        val missingReceipt = grant(POLICY_C)
        val foreignStream = grant(POLICY_D, stream = OTHER_STREAM)
        val store = FakeReadStore(
            rows = listOf(exact, revoked, missingReceipt, foreignStream),
            receipts = listOf(exact.toRevisionEntity(), revoked.toRevisionEntity()),
        )
        val source = RoomPolicyGrantAuthoritySource(store)

        val result = source.listExactGranted(SCOPE, CONSUMER, STREAM, limit = 20)

        assertEquals(listOf(POLICY_A), result.map { it.policyId })
        // The authority source asks for one extra row so it can fail closed rather than
        // truncate grants before the later relevance pass.
        assertEquals(21, store.requestedLimit)
        assertEquals(STREAM, store.requestedStream)
        assertEquals(SCOPE.storageId, store.requestedScopeId)
    }

    @Test
    fun `list fails closed instead of truncating before relevance`() = runBlocking {
        val heads = listOf(grant(POLICY_A), grant(POLICY_B), grant(POLICY_C))
        val store = FakeReadStore(heads, heads.map { it.toRevisionEntity() })
        val source = RoomPolicyGrantAuthoritySource(store)

        val result = source.listExactGranted(SCOPE, CONSUMER, STREAM, limit = 2)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `revalidation binds grant id state version and the complete exact tuple`() = runBlocking {
        val head = grant(POLICY_A)
        val store = FakeReadStore(listOf(head), listOf(head.toRevisionEntity()))
        val source = RoomPolicyGrantAuthoritySource(store)
        val snapshot = source.listExactGranted(SCOPE, CONSUMER, STREAM, 1).single()

        assertTrue(source.revalidateExact(snapshot))

        store.heads[head.grantId] = head.copy(
            policyRevision = 2L,
            artifactSha256 = SHA_B,
            stateVersion = 2L,
            reasonCode = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE.name,
            updatedAtMs = 20L,
        )
        assertFalse(source.revalidateExact(snapshot))
    }

    @Test
    fun `missing or mismatched receipt makes revalidation inert`() = runBlocking {
        val head = grant(POLICY_A)
        val store = FakeReadStore(listOf(head), listOf(head.toRevisionEntity()))
        val source = RoomPolicyGrantAuthoritySource(store)
        val snapshot = source.listExactGranted(SCOPE, CONSUMER, STREAM, 1).single()

        store.receipts.clear()
        assertFalse(source.revalidateExact(snapshot))

        store.receipts[head.grantId to head.stateVersion] = head.toRevisionEntity().copy(
            reasonCode = PolicyGrantReason.USER_RESTORED_POLICY_REVISION.name,
        )
        assertFalse(source.revalidateExact(snapshot))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbounded grant list is rejected before storage`() = runBlocking {
        RoomPolicyGrantAuthoritySource(FakeReadStore(emptyList(), emptyList()))
            .listExactGranted(SCOPE, CONSUMER, STREAM, limit = 1_025)
        Unit
    }

    @Test
    fun `global scan returns granted and revoked exact current heads with keyset cursor`() =
        runBlocking {
            val granted = grant(POLICY_A).copy(updatedAtMs = 20L)
            val revoked = grant(POLICY_B, state = "REVOKED", version = 2L)
                .copy(updatedAtMs = 20L, revokedAtMs = 20L)
            val later = grant(POLICY_C).copy(updatedAtMs = 30L)
            val rows = listOf(granted, revoked, later).sortedWith(
                compareBy<LearningPolicyGrantEntity> { it.updatedAtMs }.thenBy { it.grantId },
            )
            val store = FakeReadStore(rows, rows.map { it.toRevisionEntity() })
            val source = RoomPolicyGrantAuthoritySource(store)

            val first = source.listCurrentPage(after = null, limit = 2)

            assertTrue(first is PolicyGrantAuthorityScanResult.Ready)
            val firstPage = (first as PolicyGrantAuthorityScanResult.Ready).page
            assertEquals(rows.take(2).map { it.policyId }, firstPage.snapshots.map { it.policyId })
            assertEquals(2, firstPage.scannedHeadCount)
            assertEquals(0, firstPage.rejectedHeadCount)
            assertFalse(firstPage.endReached)
            assertEquals(
                PolicyGrantAuthorityScanCursor(
                    rows[1].updatedAtMs,
                    rows[1].grantId,
                ),
                firstPage.nextCursor,
            )
            assertTrue(firstPage.snapshots.any { it.state.name == "REVOKED" })

            val second = source.listCurrentPage(firstPage.nextCursor, limit = 2)
                as PolicyGrantAuthorityScanResult.Ready
            assertEquals(listOf(rows[2].policyId), second.page.snapshots.map { it.policyId })
            assertEquals(1, second.page.scannedHeadCount)
            assertTrue(second.page.endReached)
            assertEquals(null, second.page.nextCursor)
            assertEquals(firstPage.nextCursor, store.requestedGlobalCursors[1])
        }

    @Test
    fun `global scan advances over malformed head but never projects it`() = runBlocking {
        val valid = grant(POLICY_A).copy(updatedAtMs = 20L)
        val malformed = grant(POLICY_B).copy(updatedAtMs = 21L)
        val store = FakeReadStore(
            rows = listOf(valid, malformed),
            receipts = listOf(valid.toRevisionEntity()),
        )

        val result = RoomPolicyGrantAuthoritySource(store)
            .listCurrentPage(after = null, limit = 2) as PolicyGrantAuthorityScanResult.Ready

        assertEquals(listOf(POLICY_A), result.page.snapshots.map { it.policyId })
        assertEquals(2, result.page.scannedHeadCount)
        assertEquals(1, result.page.rejectedHeadCount)
        assertFalse(result.page.endReached)
        assertEquals(malformed.grantId, result.page.nextCursor?.afterGrantId)
    }

    @Test
    fun `global scan rejects a head advanced after the keyset page read`() = runBlocking {
        val paged = grant(POLICY_A).copy(updatedAtMs = 20L)
        val store = FakeReadStore(
            rows = listOf(paged),
            receipts = listOf(paged.toRevisionEntity()),
        )
        store.heads[paged.grantId] = paged.copy(
            policyRevision = 2L,
            artifactSha256 = SHA_B,
            stateVersion = 2L,
            reasonCode = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE.name,
            updatedAtMs = 21L,
        )

        val result = RoomPolicyGrantAuthoritySource(store)
            .listCurrentPage(after = null, limit = 2) as PolicyGrantAuthorityScanResult.Ready

        assertEquals(emptyList<String>(), result.page.snapshots.map { it.policyId })
        assertEquals(1, result.page.scannedHeadCount)
        assertEquals(1, result.page.rejectedHeadCount)
        assertTrue(result.page.endReached)
    }

    @Test
    fun `global scan fails closed on storage overrun or non keyset ordering`() = runBlocking {
        val first = grant(POLICY_A).copy(updatedAtMs = 20L)
        val second = grant(POLICY_B).copy(updatedAtMs = 20L)
        val overrun = FakeReadStore(
            rows = listOf(first, second),
            receipts = listOf(first.toRevisionEntity(), second.toRevisionEntity()),
            ignoreGlobalLimit = true,
        )
        assertEquals(
            PolicyGrantAuthorityScanResult.Unavailable,
            RoomPolicyGrantAuthoritySource(overrun).listCurrentPage(null, 1),
        )

        val reversed = listOf(first, second).sortedByDescending { it.grantId }
        val unordered = FakeReadStore(
            rows = reversed,
            receipts = reversed.map { it.toRevisionEntity() },
            preserveGlobalInputOrder = true,
        )
        assertEquals(
            PolicyGrantAuthorityScanResult.Unavailable,
            RoomPolicyGrantAuthoritySource(unordered).listCurrentPage(null, 2),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `global scan rejects an unbounded page before storage`() = runBlocking {
        RoomPolicyGrantAuthoritySource(FakeReadStore(emptyList(), emptyList()))
            .listCurrentPage(after = null, limit = 201)
        Unit
    }

    private class FakeReadStore(
        private val rows: List<LearningPolicyGrantEntity>,
        receipts: List<LearningPolicyGrantRevisionEntity>,
        private val ignoreGlobalLimit: Boolean = false,
        private val preserveGlobalInputOrder: Boolean = false,
    ) : PolicyGrantAuthorityReadStore {
        val heads = rows.associateByTo(linkedMapOf()) { it.grantId }
        val receipts = receipts.associateByTo(linkedMapOf()) { it.grantId to it.stateVersion }
        var requestedLimit: Int? = null
        var requestedStream: String? = null
        var requestedScopeId: String? = null
        val requestedGlobalCursors = mutableListOf<PolicyGrantAuthorityScanCursor>()

        override suspend fun listScopePage(
            sourceStreamId: String,
            scopeKind: String,
            scopeId: String,
            consumingAssistantId: String,
            afterUpdatedAtMs: Long,
            afterGrantId: String,
            limit: Int,
        ): List<LearningPolicyGrantEntity> {
            requestedLimit = limit
            requestedStream = sourceStreamId
            requestedScopeId = scopeId
            return rows.take(limit)
        }

        override suspend fun listCurrentPage(
            afterUpdatedAtMs: Long,
            afterGrantId: String,
            limit: Int,
        ): List<LearningPolicyGrantEntity> {
            val cursor = PolicyGrantAuthorityScanCursor(afterUpdatedAtMs, afterGrantId)
            requestedGlobalCursors += cursor
            val ordered = if (preserveGlobalInputOrder) rows else rows.sortedWith(
                compareBy<LearningPolicyGrantEntity> { it.updatedAtMs }.thenBy { it.grantId },
            )
            val page = ordered.filter { row ->
                row.updatedAtMs > afterUpdatedAtMs ||
                    (row.updatedAtMs == afterUpdatedAtMs && row.grantId > afterGrantId)
            }
            return if (ignoreGlobalLimit) page else page.take(limit)
        }

        override suspend fun findHead(grantId: String): LearningPolicyGrantEntity? = heads[grantId]

        override suspend fun findRevision(
            grantId: String,
            stateVersion: Long,
        ): LearningPolicyGrantRevisionEntity? = receipts[grantId to stateVersion]
    }
}

private fun grant(
    policyId: String,
    stream: String = STREAM,
    state: String = "GRANTED",
    version: Long = 1L,
): LearningPolicyGrantEntity {
    val revoked = state == "REVOKED"
    val grantedAt = 10L
    val updatedAt = if (revoked) 20L else grantedAt
    return LearningPolicyGrantEntity(
        grantId = policyGrantId(stream, SCOPE, CONSUMER, policyId),
        sourceStreamId = stream,
        policyId = policyId,
        policyRevision = 1L,
        artifactSha256 = SHA_A,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        consumingAssistantId = CONSUMER.toString(),
        actor = "USER_REVIEW",
        state = state,
        stateVersion = version,
        grantedAtMs = grantedAt,
        revokedAtMs = updatedAt.takeIf { revoked },
        reasonCode = if (revoked) {
            PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE.name
        } else {
            PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE.name
        },
        createdAtMs = grantedAt,
        updatedAtMs = updatedAt,
    )
}

private val SCOPE = LearningScope.Assistant(Uuid.parse("40000000-0000-0000-0000-000000000004"))
private val CONSUMER = SCOPE.assistantId
private const val STREAM = "50000000-0000-0000-0000-000000000005"
private const val OTHER_STREAM = "60000000-0000-0000-0000-000000000006"
private const val POLICY_A = "policy-a"
private const val POLICY_B = "policy-b"
private const val POLICY_C = "policy-c"
private const val POLICY_D = "policy-d"
private val SHA_A = "a".repeat(64)
private val SHA_B = "b".repeat(64)
