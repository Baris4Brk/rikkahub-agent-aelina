package me.rerere.rikkahub.service.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class DurableCommandQueueTest {
    @Test
    fun `idempotency is durable even when wakeup is lost`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao, workerId = "worker-1", nowMillis = { 1000L })
        val command = entity(id = "00000000-0000-0000-0000-000000000001", idempotencyKey = "idem-1")

        assertEquals(DurableSubmitResult.Inserted(Uuid.parse("00000000-0000-0000-0000-000000000001")), queue.submitDurable(command, wakeUp = false))
        assertEquals(DurableSubmitResult.AlreadyExists(Uuid.parse("00000000-0000-0000-0000-000000000001")), queue.submitDurable(command, wakeUp = true))
        assertEquals(1, queue.scanPending().size)
    }

    @Test
    fun `successful Room row ids above one are accepted`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao)
        val firstId = "00000000-0000-0000-0000-000000000010"
        val secondId = "00000000-0000-0000-0000-000000000011"

        assertEquals(
            DurableSubmitResult.Inserted(Uuid.parse(firstId)),
            queue.submitDurable(entity(id = firstId, idempotencyKey = "idem-10")),
        )
        assertEquals(
            DurableSubmitResult.Inserted(Uuid.parse(secondId)),
            queue.submitDurable(entity(id = secondId, idempotencyKey = "idem-11")),
        )
        assertEquals(2, dao.allRows().size)
    }

    @Test
    fun `only one worker can claim and lease expiry enables takeover`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val first = DurableCommandQueue(dao, workerId = "worker-1", nowMillis = { 1000L })
        val second = DurableCommandQueue(dao, workerId = "worker-2", nowMillis = { 1000L })
        first.submitDurable(entity(id = "00000000-0000-0000-0000-000000000002", idempotencyKey = "idem-2"))

        assertTrue(first.claim("00000000-0000-0000-0000-000000000002", now = 1000L, lease = 30.seconds))
        assertFalse(second.claim("00000000-0000-0000-0000-000000000002", now = 1001L, lease = 30.seconds))
        assertEquals(1, dao.interruptExpired(31_001L))
        assertTrue(second.claim("00000000-0000-0000-0000-000000000002", now = 31_002L, lease = 30.seconds))
    }

    @Test
    fun `side effect unknown requires manual confirmation`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao)
        val decision = queue.decideRecovery(
            entity(
                id = "cmd-3",
                idempotencyKey = "idem-3",
                state = DurableCommandState.INTERRUPTED.name,
                lastErrorCode = "SIDE_EFFECT_UNKNOWN",
            )
        )
        assertEquals(RecoveryAction.MANUAL_CONFIRMATION, decision.action)
    }

    private fun entity(
        id: String,
        idempotencyKey: String,
        state: String = DurableCommandState.PENDING.name,
        lastErrorCode: String? = null,
    ) = PendingChatCommandEntity(
        id = id,
        schemaVersion = 1,
        conversationId = "conversation-1",
        type = "send_message",
        payloadJson = "{\"content\":{\"parts\":[]}}",
        state = state,
        priority = 0,
        sequence = 1,
        expectedTargetVersion = null,
        expectedBranchHeadMessageId = null,
        dedupeKey = null,
        idempotencyKey = idempotencyKey,
        attempt = 0,
        claimedBy = null,
        leaseUntil = null,
        createdAt = 0L,
        startedAt = null,
        finishedAt = null,
        expiresAt = null,
        lastErrorCode = lastErrorCode,
        lastErrorMessage = null,
    )
}

internal class FakePendingChatCommandDao(
    private val rewriteFailure: Throwable? = null,
    private val resolvePendingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : PendingChatCommandDao {
    private val rows = ConcurrentHashMap<String, PendingChatCommandEntity>()
    private val flow = MutableStateFlow<List<PendingChatCommandEntity>>(emptyList())
    private val nextRowId = java.util.concurrent.atomic.AtomicLong(0)

    fun row(id: Uuid): PendingChatCommandEntity? = rows[id.toString()]
    fun allRows(): List<PendingChatCommandEntity> = rows.values.sortedBy { it.sequence }

    private fun publish() { flow.value = rows.values.sortedBy { it.sequence } }

    override suspend fun insert(command: PendingChatCommandEntity): Long {
        if (rows.putIfAbsent(command.id, command) != null) return -1L
        publish()
        return nextRowId.incrementAndGet()
    }

    override suspend fun findById(id: String) = rows[id]
    override suspend fun rewritePendingCommand(id: String, type: String, payloadJson: String): Int {
        rewriteFailure?.let { throw it }
        val row = rows[id] ?: return 0
        if (row.state != "PENDING") return 0
        rows[id] = row.copy(type = type, payloadJson = payloadJson)
        publish()
        return 1
    }
    override suspend fun findByIdempotencyKey(key: String) = rows.values.firstOrNull { it.idempotencyKey == key }
    override suspend fun findPending(conversationId: String, now: Long, limit: Int) =
        rows.values.filter { it.conversationId == conversationId && it.state in setOf("PENDING", "INTERRUPTED") && (it.expiresAt == null || it.expiresAt > now) }
            .sortedWith(compareByDescending<PendingChatCommandEntity> { it.priority }.thenBy { it.sequence }).take(limit)
    override suspend fun findPendingGlobally(now: Long, limit: Int) =
        rows.values.filter { it.state in setOf("PENDING", "INTERRUPTED") && (it.expiresAt == null || it.expiresAt > now) }
            .sortedWith(compareByDescending<PendingChatCommandEntity> { it.priority }.thenBy { it.sequence }).take(limit)
    override suspend fun findActiveByDedupeKey(conversationId: String, dedupeKey: String) = null
    override fun observe(conversationId: String): Flow<List<PendingChatCommandEntity>> = flow
    override fun observePending(): Flow<List<PendingChatCommandEntity>> = flow

    override suspend fun claim(id: String, workerId: String, leaseUntil: Long, now: Long): Int {
        val row = rows[id] ?: return 0
        if (row.state !in setOf("PENDING", "INTERRUPTED") || (row.expiresAt != null && row.expiresAt <= now)) return 0
        rows[id] = row.copy(state = "RUNNING", claimedBy = workerId, leaseUntil = leaseUntil, startedAt = row.startedAt ?: now, attempt = row.attempt + 1)
        publish()
        return 1
    }

    override suspend fun renewLease(id: String, workerId: String, leaseUntil: Long): Int {
        val row = rows[id] ?: return 0
        if (row.state != "RUNNING" || row.claimedBy != workerId) return 0
        rows[id] = row.copy(leaseUntil = leaseUntil)
        publish()
        return 1
    }

    override suspend fun interruptExpired(now: Long, message: String): Int {
        var count = 0
        rows.forEach { (id, row) ->
            if (row.state == "RUNNING" && row.leaseUntil != null && row.leaseUntil < now) {
                rows[id] = row.copy(state = "INTERRUPTED", claimedBy = null, leaseUntil = null, lastErrorCode = "LEASE_EXPIRED", lastErrorMessage = message)
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    override suspend fun finish(id: String, state: String, finishedAt: Long, expectedState: String, errorCode: String?, errorMessage: String?): Int {
        val row = rows[id] ?: return 0
        if (row.state != expectedState) return 0
        rows[id] = row.copy(state = state, finishedAt = finishedAt, claimedBy = null, leaseUntil = null, lastErrorCode = errorCode, lastErrorMessage = errorMessage)
        publish()
        return 1
    }

    override suspend fun resolvePending(id: String, state: String, finishedAt: Long, errorCode: String?, errorMessage: String?): Int {
        resolvePendingGate?.await()
        return finish(
            id,
            state,
            finishedAt,
            expectedState = rows[id]?.state ?: "missing",
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    override suspend fun countActive(conversationId: String) = rows.values.count { it.conversationId == conversationId && it.state in setOf("PENDING", "INTERRUPTED", "RUNNING", "WAITING_APPROVAL") }
    override suspend fun clearPending(conversationId: String): Int {
        val ids = rows.values.filter { it.conversationId == conversationId && it.state == "PENDING" }.map { it.id }
        ids.forEach(rows::remove)
        publish()
        return ids.size
    }
}
