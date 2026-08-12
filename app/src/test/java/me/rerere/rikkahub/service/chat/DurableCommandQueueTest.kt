package me.rerere.rikkahub.service.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

    @Test
    fun `stable command failures persist actionable code and message`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao, nowMillis = { 2_000L })
        val id = "00000000-0000-0000-0000-000000000099"
        queue.submitDurable(entity(id = id, idempotencyKey = "idem-99"))
        assertTrue(queue.claim(id))

        assertTrue(
            queue.complete(
                id = id,
                state = DurableCommandState.FAILED,
                error = StableCommandException(
                    durableErrorCode = "FINAL_ANSWER_EOF",
                    durableErrorMessage = "The provider ended before returning a visible final answer.",
                ),
            ),
        )

        val stored = dao.findById(id)
        assertEquals("FINAL_ANSWER_EOF", stored?.lastErrorCode)
        assertEquals(
            "The provider ended before returning a visible final answer.",
            stored?.lastErrorMessage,
        )
    }

    @Test
    fun `scoped replay excludes other conversations and waiting barriers`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao, nowMillis = { 1_000L })
        val conversation = Uuid.parse("00000000-0000-0000-0000-000000000701")
        val otherConversation = Uuid.parse("00000000-0000-0000-0000-000000000702")
        dao.insert(entity("00000000-0000-0000-0000-000000000711", "r1", conversationId = conversation.toString()))
        dao.insert(entity("00000000-0000-0000-0000-000000000712", "r2", state = "INTERRUPTED", conversationId = conversation.toString()))
        dao.insert(entity("00000000-0000-0000-0000-000000000713", "r3", state = "WAITING_APPROVAL", conversationId = conversation.toString()))
        dao.insert(entity("00000000-0000-0000-0000-000000000714", "r4", conversationId = otherConversation.toString()))

        assertEquals(
            listOf("00000000-0000-0000-0000-000000000711", "00000000-0000-0000-0000-000000000712"),
            queue.scanReplayable(conversation).map { it.id },
        )
        assertEquals(
            listOf("00000000-0000-0000-0000-000000000713"),
            queue.scanWaiting(conversation).map { it.id },
        )
    }

    @Test
    fun `interrupted command reserves its dedupe key`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao)
        val conversation = "00000000-0000-0000-0000-000000000721"
        val interruptedId = "00000000-0000-0000-0000-000000000722"
        dao.insert(
            entity(
                id = interruptedId,
                idempotencyKey = "old",
                state = "INTERRUPTED",
                conversationId = conversation,
                dedupeKey = "same-turn",
            ),
        )

        assertEquals(
            DurableSubmitResult.DedupeHit(Uuid.parse(interruptedId)),
            queue.submitDurable(
                entity(
                    id = "00000000-0000-0000-0000-000000000723",
                    idempotencyKey = "new",
                    conversationId = conversation,
                    dedupeKey = "same-turn",
                ),
            ),
        )
    }

    @Test
    fun `single waiting lookup is exact rather than an active prefix`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val queue = DurableCommandQueue(dao)
        val conversation = Uuid.parse("00000000-0000-0000-0000-000000000731")
        repeat(17) { index ->
            dao.insert(
                entity(
                    id = "00000000-0000-0000-0000-${(740 + index).toString().padStart(12, '0')}",
                    idempotencyKey = "prefix-$index",
                    conversationId = conversation.toString(),
                    sequence = index.toLong(),
                ),
            )
        }
        val waiting = entity(
            id = "00000000-0000-0000-0000-000000000799",
            idempotencyKey = "waiting",
            state = "WAITING_APPROVAL",
            conversationId = conversation.toString(),
            sequence = 99L,
        )
        dao.insert(waiting)

        assertEquals(waiting.id, queue.findSingleWaitingForConversation(conversation)?.id)
        dao.insert(
            entity(
                id = "00000000-0000-0000-0000-000000000798",
                idempotencyKey = "waiting-2",
                state = "WAITING_APPROVAL",
                conversationId = conversation.toString(),
                sequence = 100L,
            ),
        )
        assertTrue(queue.findSingleWaitingForConversation(conversation) == null)
    }

    @Test
    fun `approval resume admission is deterministic and inherits waiting authority`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val conversation = Uuid.parse("00000000-0000-0000-0000-000000000801")
        val waitingId = "00000000-0000-0000-0000-000000000802"
        val approvalId = "00000000-0000-0000-0000-000000000803"
        val assistantId = "00000000-0000-0000-0000-000000000804"
        val anchorId = "00000000-0000-0000-0000-000000000805"
        val waiting = entity(
            id = waitingId,
            idempotencyKey = waitingId,
            state = DurableCommandState.WAITING_APPROVAL.name,
            conversationId = conversation.toString(),
        ).copy(
            schemaVersion = 2,
            authoritySubjectId = "authority:exact",
            assistantIdSnapshot = assistantId,
            lineageId = waitingId,
            branchAnchorMessageId = anchorId,
            branchAnchorMessageRevision = 4L,
            conversationSourceRevision = 7L,
            stateVersion = 3L,
        )
        val approval = entity(
            id = approvalId,
            idempotencyKey = approvalId,
            state = DurableCommandState.RUNNING.name,
            conversationId = conversation.toString(),
        ).copy(
            schemaVersion = 2,
            authoritySubjectId = waiting.authoritySubjectId,
            assistantIdSnapshot = assistantId,
            lineageId = waitingId,
            parentCommandId = waitingId,
            branchAnchorMessageId = anchorId,
            branchAnchorMessageRevision = waiting.branchAnchorMessageRevision,
            conversationSourceRevision = waiting.conversationSourceRevision,
            stateVersion = 2L,
            type = "tool_approval",
            claimedBy = "00000000-0000-0000-0000-000000000806",
            leaseUntil = 10_000L,
        )
        dao.insert(waiting)
        dao.insert(approval)
        val transaction = CommandStateTransaction(dao)
        val queue = DurableCommandQueue(dao, commandStateTransaction = transaction)

        val first = queue.ensureApprovalResumeInCurrentTransaction(
            conversationId = conversation,
            approvalId = "approval:exact",
            resolutionRequestId = "request:exact",
            resolvedAtMs = 100L,
            approvalCommandId = Uuid.parse(approvalId),
            owningWaitingCommandId = waitingId,
        )
        val second = queue.ensureApprovalResumeInCurrentTransaction(
            conversationId = conversation,
            approvalId = "approval:exact",
            resolutionRequestId = "request:exact",
            resolvedAtMs = 100L,
            approvalCommandId = Uuid.parse(approvalId),
            owningWaitingCommandId = waitingId,
        )

        assertEquals(first.commandId, second.commandId)
        assertTrue(first.insertedCommand)
        assertFalse(second.insertedCommand)
        val resume = dao.findById(first.commandId)
        assertEquals("resume_after_approval", resume?.type)
        assertEquals(waitingId, resume?.parentCommandId)
        assertEquals(waiting.authoritySubjectId, resume?.authoritySubjectId)
        assertEquals(waiting.assistantIdSnapshot, resume?.assistantIdSnapshot)
        assertEquals(waiting.lineageId, resume?.lineageId)
        assertEquals(waiting.branchAnchorMessageId, resume?.branchAnchorMessageId)
        assertEquals(waiting.branchAnchorMessageRevision, resume?.branchAnchorMessageRevision)
        assertEquals(waiting.conversationSourceRevision, resume?.conversationSourceRevision)
        assertEquals(1L, resume?.stateVersion)
    }

    private fun entity(
        id: String,
        idempotencyKey: String,
        state: String = DurableCommandState.PENDING.name,
        lastErrorCode: String? = null,
        conversationId: String = "conversation-1",
        dedupeKey: String? = null,
        sequence: Long = 1L,
    ) = PendingChatCommandEntity(
        id = id,
        schemaVersion = 1,
        conversationId = conversationId,
        type = "send_message",
        payloadJson = "{\"content\":{\"parts\":[]}}",
        state = state,
        priority = 0,
        sequence = sequence,
        expectedTargetVersion = null,
        expectedBranchHeadMessageId = null,
        dedupeKey = dedupeKey,
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
    private val finishUnclaimedFailure: Throwable? = null,
    private val finishUnclaimedResultOverride: Int? = null,
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
    override fun observeById(id: String): Flow<PendingChatCommandEntity?> =
        flow.map { current -> current.firstOrNull { it.id == id } }
    override suspend fun rewritePendingCommand(id: String, type: String, payloadJson: String): Int {
        rewriteFailure?.let { throw it }
        val row = rows[id] ?: return 0
        if (row.state != "PENDING") return 0
        rows[id] = row.copy(
            type = type,
            payloadJson = payloadJson,
            stateVersion = row.stateVersion + 1,
        )
        publish()
        return 1
    }
    override suspend fun findByIdempotencyKey(key: String) = rows.values.firstOrNull { it.idempotencyKey == key }
    override suspend fun findPending(conversationId: String, now: Long, limit: Int) =
        rows.values.filter { it.conversationId == conversationId && it.state in setOf("PENDING", "INTERRUPTED") && (it.expiresAt == null || it.expiresAt > now) }
            .sortedWith(
                compareByDescending<PendingChatCommandEntity> { it.priority }
                    .thenBy { it.sequence }
                    .thenBy { it.id },
            ).take(limit)
    override suspend fun findPendingGlobally(now: Long, limit: Int) =
        rows.values.filter { it.state in setOf("PENDING", "INTERRUPTED") && (it.expiresAt == null || it.expiresAt > now) }
            .sortedWith(
                compareByDescending<PendingChatCommandEntity> { it.priority }
                    .thenBy { it.sequence }
                    .thenBy { it.id },
            ).take(limit)
    override suspend fun findActiveByDedupeKey(conversationId: String, dedupeKey: String) =
        rows.values
            .filter {
                it.conversationId == conversationId && it.dedupeKey == dedupeKey &&
                    it.state in ACTIVE_STATES
            }
            .minWithOrNull(compareBy<PendingChatCommandEntity> { it.sequence }.thenBy { it.id })
    override suspend fun findReplayableForConversation(conversationId: String, now: Long, limit: Int) =
        rows.values
            .filter {
                it.conversationId == conversationId && it.state in setOf("PENDING", "INTERRUPTED") &&
                    (it.expiresAt == null || it.expiresAt > now)
            }
            .sortedWith(
                compareByDescending<PendingChatCommandEntity> { it.priority }
                    .thenBy { it.sequence }
                    .thenBy { it.id },
            )
            .take(limit)
    override suspend fun findWaitingForConversation(conversationId: String, limit: Int) = rows.values
        .filter { it.conversationId == conversationId && it.state == "WAITING_APPROVAL" }
        .sortedWith(compareBy<PendingChatCommandEntity> { it.sequence }.thenBy { it.id })
        .take(limit)
    override suspend fun listWaitingForConversation(conversationId: String, limit: Int) = rows.values
        .filter { it.conversationId == conversationId && it.state == "WAITING_APPROVAL" }
        .sortedWith(compareBy<PendingChatCommandEntity> { it.sequence }.thenBy { it.id })
        .take(limit)
    override suspend fun countWaitingForConversation(conversationId: String): Int = rows.values
        .count { it.conversationId == conversationId && it.state == "WAITING_APPROVAL" }
    override suspend fun listWaitingByLineage(
        conversationId: String,
        lineageId: String,
        limit: Int,
    ) = rows.values
        .filter {
            it.conversationId == conversationId && it.lineageId == lineageId &&
                it.state == "WAITING_APPROVAL"
        }
        .sortedWith(compareBy<PendingChatCommandEntity> { it.sequence }.thenBy { it.id })
        .take(limit)
    override suspend fun countWaitingByLineage(conversationId: String, lineageId: String): Int =
        rows.values.count {
            it.conversationId == conversationId && it.lineageId == lineageId &&
                it.state == "WAITING_APPROVAL"
        }
    override suspend fun maxSequenceForConversation(conversationId: String): Long? = rows.values
        .filter { it.conversationId == conversationId }
        .maxOfOrNull { it.sequence }
    override fun observe(conversationId: String): Flow<List<PendingChatCommandEntity>> = flow
    override fun observePending(): Flow<List<PendingChatCommandEntity>> = flow
    override suspend fun listActive(): List<PendingChatCommandEntity> = rows.values.filter {
        it.state in setOf("PENDING", "INTERRUPTED", "RUNNING", "WAITING_APPROVAL")
    }

    override suspend fun claim(id: String, workerId: String, leaseUntil: Long, now: Long): Int {
        val row = rows[id] ?: return 0
        if (row.state !in setOf("PENDING", "INTERRUPTED") || (row.expiresAt != null && row.expiresAt <= now)) return 0
        rows[id] = row.copy(state = "RUNNING", claimedBy = workerId, leaseUntil = leaseUntil, startedAt = row.startedAt ?: now, attempt = row.attempt + 1, stateVersion = row.stateVersion + 1)
        publish()
        return 1
    }

    override suspend fun renewLease(id: String, workerId: String, leaseUntil: Long): Int {
        val row = rows[id] ?: return 0
        if (row.state != "RUNNING" || row.claimedBy != workerId) return 0
        rows[id] = row.copy(leaseUntil = leaseUntil, stateVersion = row.stateVersion + 1)
        publish()
        return 1
    }

    override suspend fun interruptExpired(now: Long, message: String): Int {
        var count = 0
        rows.forEach { (id, row) ->
            if (row.state == "RUNNING" && row.leaseUntil != null && row.leaseUntil < now) {
                rows[id] = row.copy(state = "INTERRUPTED", claimedBy = null, leaseUntil = null, lastErrorCode = "LEASE_EXPIRED", lastErrorMessage = message, stateVersion = row.stateVersion + 1)
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    override suspend fun finish(id: String, state: String, finishedAt: Long, expectedState: String, errorCode: String?, errorMessage: String?): Int {
        val row = rows[id] ?: return 0
        if (row.state != expectedState) return 0
        rows[id] = row.copy(state = state, finishedAt = finishedAt, claimedBy = null, leaseUntil = null, lastErrorCode = errorCode, lastErrorMessage = errorMessage, stateVersion = row.stateVersion + 1)
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
        ids.forEach { id ->
            val row = checkNotNull(rows[id])
            rows[id] = row.copy(
                state = "CANCELLED",
                finishedAt = 0L,
                claimedBy = null,
                leaseUntil = null,
                stateVersion = row.stateVersion + 1,
            )
        }
        publish()
        return ids.size
    }

    override suspend fun cancelByAuthoritySubject(
        subjectId: String,
        finishedAt: Long,
        code: String,
        message: String,
    ): Int = cancelRows(
        finishedAt = finishedAt,
        code = code,
        message = message,
    ) { it.authoritySubjectId == subjectId }

    override suspend fun cancelLegacyUnscopedForConversation(
        conversationId: String,
        finishedAt: Long,
        code: String,
        message: String,
    ): Int = cancelRows(
        finishedAt = finishedAt,
        code = code,
        message = message,
    ) { it.conversationId == conversationId && it.authoritySubjectId == null }

    private fun cancelRows(
        finishedAt: Long,
        code: String,
        message: String,
        predicate: (PendingChatCommandEntity) -> Boolean,
    ): Int {
        var count = 0
        rows.forEach { (id, row) ->
            if (predicate(row) && row.state in setOf("PENDING", "INTERRUPTED", "WAITING_APPROVAL", "RUNNING")) {
                rows[id] = row.copy(
                    state = "CANCELLED",
                    finishedAt = finishedAt,
                    claimedBy = null,
                    leaseUntil = null,
                    lastErrorCode = code,
                    lastErrorMessage = message,
                    stateVersion = row.stateVersion + 1,
                )
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    override suspend fun claimFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        leaseUntil: Long,
        now: Long,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.stateVersion != expectedVersion || row.state !in setOf("PENDING", "INTERRUPTED") ||
            row.assistantIdSnapshot == null || row.lineageId == null || row.branchAnchorMessageId == null ||
            (row.expiresAt != null && row.expiresAt <= now)
        ) return 0
        rows[id] = row.copy(
            state = "RUNNING",
            claimedBy = workerId,
            leaseUntil = leaseUntil,
            startedAt = row.startedAt ?: now,
            attempt = row.attempt + 1,
            stateVersion = row.stateVersion + 1,
        )
        publish()
        return 1
    }

    override suspend fun renewLeaseFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        leaseUntil: Long,
        now: Long,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.state != "RUNNING" || row.stateVersion != expectedVersion ||
            row.claimedBy != workerId || row.leaseUntil != expectedLeaseUntil || expectedLeaseUntil < now
        ) return 0
        rows[id] = row.copy(leaseUntil = leaseUntil, stateVersion = row.stateVersion + 1)
        publish()
        return 1
    }

    override suspend fun finishClaimedFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        nextState: String,
        finishedAt: Long,
        now: Long,
        errorCode: String?,
        errorMessage: String?,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.state != "RUNNING" || row.stateVersion != expectedVersion ||
            row.claimedBy != workerId || row.leaseUntil != expectedLeaseUntil || expectedLeaseUntil < now
        ) return 0
        rows[id] = row.copy(
            state = nextState,
            finishedAt = finishedAt,
            claimedBy = null,
            leaseUntil = null,
            lastErrorCode = errorCode,
            lastErrorMessage = errorMessage,
            stateVersion = row.stateVersion + 1,
        )
        publish()
        return 1
    }

    override suspend fun finishClaimedWithCompletionFenced(
        id: String,
        conversationId: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        nextState: String,
        finishedAt: Long,
        now: Long,
        errorCode: String?,
        conversationSourceRevision: Long?,
        completionKind: String,
        resultMessageId: String?,
        resultMessageRevision: Long?,
    ): Int {
        if (rows[id]?.conversationId != conversationId) return 0
        val changed = finishClaimedFenced(
            id, expectedVersion, workerId, expectedLeaseUntil, nextState,
            finishedAt, now, errorCode, null,
        )
        if (changed == 1) {
            rows[id] = rows.getValue(id).copy(
                conversationSourceRevision = conversationSourceRevision,
                completionKind = completionKind,
                resultAssistantMessageId = resultMessageId,
                resultAssistantMessageRevision = resultMessageRevision,
            )
            publish()
        }
        return changed
    }

    override suspend fun markWaitingApprovalFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        now: Long,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.state != "RUNNING" || row.stateVersion != expectedVersion ||
            row.claimedBy != workerId || row.leaseUntil != expectedLeaseUntil || expectedLeaseUntil < now
        ) return 0
        rows[id] = row.copy(
            state = "WAITING_APPROVAL",
            finishedAt = null,
            claimedBy = null,
            leaseUntil = null,
            lastErrorCode = null,
            lastErrorMessage = null,
            stateVersion = row.stateVersion + 1,
        )
        publish()
        return 1
    }

    override suspend fun markWaitingApprovalWithCompletionFenced(
        id: String,
        conversationId: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        now: Long,
        conversationSourceRevision: Long,
        completionKind: String,
        resultMessageId: String,
        resultMessageRevision: Long,
    ): Int {
        if (rows[id]?.conversationId != conversationId) return 0
        val changed = markWaitingApprovalFenced(
            id, expectedVersion, workerId, expectedLeaseUntil, now,
        )
        if (changed == 1) {
            rows[id] = rows.getValue(id).copy(
                conversationSourceRevision = conversationSourceRevision,
                completionKind = completionKind,
                resultAssistantMessageId = resultMessageId,
                resultAssistantMessageRevision = resultMessageRevision,
            )
            publish()
        }
        return changed
    }

    override suspend fun finishUnclaimedFenced(
        id: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        finishedAt: Long,
        errorCode: String?,
        errorMessage: String?,
    ): Int {
        finishUnclaimedFailure?.let { throw it }
        resolvePendingGate?.await()
        finishUnclaimedResultOverride?.let { return it }
        val row = rows[id] ?: return 0
        if (row.state != expectedState || row.stateVersion != expectedVersion) return 0
        rows[id] = row.copy(
            state = nextState,
            finishedAt = finishedAt,
            claimedBy = null,
            leaseUntil = null,
            lastErrorCode = errorCode,
            lastErrorMessage = errorMessage,
            stateVersion = row.stateVersion + 1,
        )
        publish()
        return 1
    }

    override suspend fun finishUnclaimedWithCompletionFenced(
        id: String,
        conversationId: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        finishedAt: Long,
        errorCode: String?,
        conversationSourceRevision: Long?,
        completionKind: String,
        resultMessageId: String?,
        resultMessageRevision: Long?,
    ): Int {
        if (rows[id]?.conversationId != conversationId) return 0
        val changed = finishUnclaimedFenced(
            id, expectedState, expectedVersion, nextState, finishedAt, errorCode, null,
        )
        if (changed == 1) {
            rows[id] = rows.getValue(id).copy(
                conversationSourceRevision = conversationSourceRevision,
                completionKind = completionKind,
                resultAssistantMessageId = resultMessageId,
                resultAssistantMessageRevision = resultMessageRevision,
            )
            publish()
        }
        return changed
    }

    override suspend fun interruptExpiredFenced(
        id: String,
        expectedVersion: Long,
        expectedLeaseUntil: Long,
        now: Long,
        message: String,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.state != "RUNNING" || row.stateVersion != expectedVersion ||
            row.leaseUntil != expectedLeaseUntil || expectedLeaseUntil >= now
        ) return 0
        rows[id] = row.copy(
            state = "INTERRUPTED",
            claimedBy = null,
            leaseUntil = null,
            lastErrorCode = "LEASE_EXPIRED",
            lastErrorMessage = message,
            stateVersion = row.stateVersion + 1,
        )
        publish()
        return 1
    }

    override suspend fun listExpiredRunning(now: Long, limit: Int) = rows.values
        .filter { it.state == "RUNNING" && it.leaseUntil != null && it.leaseUntil < now }
        .sortedBy { it.leaseUntil }
        .take(limit)

    override suspend fun listExpiredPending(now: Long, limit: Int) = rows.values
        .filter { it.state == "PENDING" && it.expiresAt != null && it.expiresAt <= now }
        .sortedWith(
            compareBy<PendingChatCommandEntity> { it.expiresAt }
                .thenBy { it.sequence }
                .thenBy { it.id },
        )
        .take(limit)

    override suspend fun listActiveForConversation(conversationId: String, limit: Int) = rows.values
        .filter { it.conversationId == conversationId && it.state in ACTIVE_STATES }
        .sortedBy { it.sequence }
        .take(limit)

    override suspend fun listActiveForAuthoritySubject(subjectId: String, limit: Int) = rows.values
        .filter { it.authoritySubjectId == subjectId && it.state in ACTIVE_STATES }
        .sortedBy { it.sequence }
        .take(limit)

    override suspend fun listLegacyUnscopedActiveForConversation(conversationId: String, limit: Int) =
        rows.values
            .filter {
                it.conversationId == conversationId && it.authoritySubjectId == null &&
                    it.state in ACTIVE_STATES
            }
            .sortedBy { it.sequence }
            .take(limit)

    private companion object {
        val ACTIVE_STATES = setOf("PENDING", "INTERRUPTED", "RUNNING", "WAITING_APPROVAL")
    }
}
