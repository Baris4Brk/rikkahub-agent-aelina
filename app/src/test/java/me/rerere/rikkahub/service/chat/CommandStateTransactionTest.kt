package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class CommandStateTransactionTest {
    @Test
    fun `command codec never serializes authority lineage`() {
        val payload = CommandCodec.encodeDurable(
            SendMessageCommand(RawUserContent(emptyList())),
            CommandOrigin.APP_UI,
        ).second

        assertFalse(payload.contains("lineageId"))
        assertFalse(payload.contains("parentCommandId"))
        assertFalse(payload.contains("branchAnchorMessageId"))
        assertFalse(payload.contains("stateVersion"))
    }

    @Test
    fun `admission persists version one and emits content-free authority event once`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val events = mutableListOf<CommandAuthorityEvent>()
        val transaction = CommandStateTransaction(
            dao = dao,
            events = CommandAuthorityEventPort { event ->
                val inserted = events.none { it.kind == event.kind && it.commandId == event.commandId &&
                        it.stateVersion == event.stateVersion
                    }
                if (inserted) events.add(event)
                inserted
            },
            nowMs = { 10L },
        )
        val draft = authorityDraft()

        val inserted = transaction.admit(draft) as CommandAdmissionResult.Inserted
        assertEquals(1L, inserted.row.stateVersion)
        assertEquals(CommandAuthorityEventKind.ADMITTED, events.single().kind)
        assertEquals(CommandAdmissionResult.AlreadyExists(inserted.row), transaction.admit(draft))
        assertEquals(1, events.size)
    }

    @Test
    fun `learning wake runs after commit and only for a newly inserted outbox row`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        var inTransaction = false
        var wakeCount = 0
        val outboxKeys = mutableSetOf<String>()
        val runner = object : CommandTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                inTransaction = true
                return try {
                    block()
                } finally {
                    inTransaction = false
                }
            }
        }
        val transaction = CommandStateTransaction(
            dao = dao,
            transactions = runner,
            events = CommandAuthorityEventPort { event ->
                assertTrue(inTransaction)
                outboxKeys.add("${event.kind}:${event.commandId}:${event.stateVersion}")
            },
            learningPostCommitWake = {
                assertFalse(inTransaction)
                wakeCount++
            },
        )
        val draft = authorityDraft(commandId = "00000000-0000-0000-0000-000000000211")

        assertTrue(transaction.admit(draft) is CommandAdmissionResult.Inserted)
        assertEquals(1, wakeCount)
        assertTrue(transaction.admit(draft) is CommandAdmissionResult.AlreadyExists)
        assertEquals(1, wakeCount)
    }

    @Test
    fun `outer authority admission defers learning wake until explicit post commit`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        var wakeCount = 0
        val transaction = CommandStateTransaction(
            dao = dao,
            events = CommandAuthorityEventPort { true },
            learningPostCommitWake = { wakeCount++ },
        )

        val commit = transaction.admitInCurrentTransaction(
            authorityDraft(commandId = "00000000-0000-0000-0000-000000000212"),
        )
        assertTrue(commit.result is CommandAdmissionResult.Inserted)
        assertTrue(commit.insertedOutbox)
        assertEquals(0, wakeCount)

        transaction.dispatchExternalPostCommit(commit.insertedOutbox)
        assertEquals(1, wakeCount)
    }

    @Test
    fun `combined waiting CAS persists exact source and result authority`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val events = mutableListOf<CommandAuthorityEvent>()
        val transaction = CommandStateTransaction(
            dao = dao,
            events = CommandAuthorityEventPort { event ->
                events.add(event)
                true
            },
            nowMs = { 10L },
        )
        val draft = authorityDraft(
            commandId = "00000000-0000-0000-0000-000000000213",
        ).copy(branchAnchorMessageRevision = 3L)
        val admission = transaction.admitInCurrentTransaction(draft, 7L)
        assertTrue(admission.result is CommandAdmissionResult.Inserted)
        val claim = (transaction.claim(
            Uuid.parse(draft.id),
            Uuid.parse("00000000-0000-0000-0000-000000000214"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim
        val completion = CommandCompletionAuthority(
            kind = CommandCompletionKind.GENERATION_WAITING_APPROVAL,
            phase = CommandCompletionPhase.WAITING,
            commandState = DurableCommandState.WAITING_APPROVAL,
            resultMessage = CommandResultMessageAuthority(
                "00000000-0000-0000-0000-000000000215",
                5L,
            ),
        )

        val waiting = transaction.markWaitingApprovalInCurrentTransaction(claim, completion, 8L)

        assertTrue(waiting.result is CommandTransitionResult.Applied)
        val row = requireNotNull(dao.findById(draft.id))
        assertEquals(8L, row.conversationSourceRevision)
        assertEquals(completion.kind.name, row.completionKind)
        assertEquals(5L, row.resultAssistantMessageRevision)
        val event = events.last()
        assertEquals(8L, event.conversationSourceRevision)
        assertEquals(completion, event.completion)
        assertEquals(3L, event.lineage.branchAnchorMessageRevision)
    }

    @Test
    fun `final save failure is typed but cannot fabricate source or result authority`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val transaction = CommandStateTransaction(dao = dao, nowMs = { 10L })
        val draft = authorityDraft(
            commandId = "00000000-0000-0000-0000-000000000216",
        ).copy(branchAnchorMessageRevision = 3L)
        transaction.admitInCurrentTransaction(draft, 7L)
        val claim = (transaction.claim(
            Uuid.parse(draft.id),
            Uuid.parse("00000000-0000-0000-0000-000000000217"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim
        val completion = CommandCompletionAuthority(
            kind = CommandCompletionKind.FAILED_FINAL_SAVE,
            phase = CommandCompletionPhase.TERMINAL,
            commandState = DurableCommandState.FAILED,
            resultMessage = null,
        )

        val failed = transaction.finishClaimedInCurrentTransaction(
            claim = claim,
            completion = completion,
            conversationSourceRevision = null,
            errorCode = "FINAL_SAVE_FAILED",
        )

        assertTrue(failed.result is CommandTransitionResult.Applied)
        val row = requireNotNull(dao.findById(draft.id))
        assertEquals(CommandCompletionKind.FAILED_FINAL_SAVE.name, row.completionKind)
        assertNull(row.conversationSourceRevision)
        assertNull(row.resultAssistantMessageId)
        assertNull(row.resultAssistantMessageRevision)
    }

    @Test
    fun `failed authority transaction never wakes learning`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        var wakeCount = 0
        val runner = object : CommandTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                block()
                error("simulated_outer_rollback")
            }
        }
        val transaction = CommandStateTransaction(
            dao = dao,
            transactions = runner,
            events = CommandAuthorityEventPort { true },
            learningPostCommitWake = { wakeCount++ },
        )

        val failed = runCatching {
            transaction.admit(
                authorityDraft(commandId = "00000000-0000-0000-0000-000000000213"),
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(0, wakeCount)
    }

    @Test
    fun `renew invalidates old claim and stale claim cannot finish`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        var now = 100L
        val transaction = CommandStateTransaction(dao = dao, nowMs = { now })
        val row = (transaction.admit(authorityDraft()) as CommandAdmissionResult.Inserted).row
        val worker = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val claimed = transaction.claim(Uuid.parse(row.id), worker, 100.milliseconds)
            as CommandClaimResult.Claimed

        now = 120L
        val renewed = transaction.renew(claimed.claim, 200.milliseconds)
            as CommandTransitionResult.Renewed
        assertEquals(claimed.row.stateVersion + 1L, renewed.row.stateVersion)
        assertTrue(
            transaction.finishClaimed(claimed.claim, DurableCommandState.COMPLETED) is
                CommandTransitionResult.Conflict,
        )
        assertTrue(
            transaction.finishClaimed(renewed.claim, DurableCommandState.COMPLETED) is
                CommandTransitionResult.Applied,
        )
    }

    @Test
    fun `expired claim is recovered and late completion is fenced`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        var now = 1_000L
        val transaction = CommandStateTransaction(dao = dao, nowMs = { now })
        val row = (transaction.admit(authorityDraft()) as CommandAdmissionResult.Inserted).row
        val claimed = transaction.claim(
            Uuid.parse(row.id),
            Uuid.parse("00000000-0000-0000-0000-000000000102"),
            10.milliseconds,
        ) as CommandClaimResult.Claimed

        now = 1_011L
        assertEquals(1, transaction.recoverExpired())
        assertTrue(
            transaction.finishClaimed(claimed.claim, DurableCommandState.COMPLETED) is
                CommandTransitionResult.Conflict,
        )
        assertEquals(DurableCommandState.INTERRUPTED.name, dao.findById(row.id)?.state)
    }

    @Test
    fun `legacy row is fail closed and cannot be claimed`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val legacy = authorityDraft().copy(
            assistantIdSnapshot = null,
            lineageId = null,
            branchAnchorMessageId = null,
            stateVersion = 0L,
        )
        dao.insert(legacy)
        val result = CommandStateTransaction(dao).claim(
            Uuid.parse(legacy.id),
            Uuid.parse("00000000-0000-0000-0000-000000000103"),
        )
        assertTrue(result is CommandClaimResult.LegacyBlocked)
    }

    @Test
    fun `bulk cancellation uses per-row CAS and emits one terminal per row`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val events = mutableListOf<CommandAuthorityEvent>()
        val transaction = CommandStateTransaction(
            dao = dao,
            events = CommandAuthorityEventPort { event -> events.add(event) },
            nowMs = { 500L },
        )
        repeat(2) { index ->
            transaction.admit(
                authorityDraft(
                    commandId = "00000000-0000-0000-0000-00000000020${index + 1}",
                    anchorId = "00000000-0000-0000-0000-00000000030${index + 1}",
                    sequence = index.toLong(),
                ),
            )
        }

        assertEquals(
            2,
            transaction.cancelConversationPending(
                CONVERSATION_ID,
                code = "USER_CLEARED_QUEUE",
            ),
        )
        assertEquals(2, events.count { it.kind == CommandAuthorityEventKind.TERMINAL })
        assertTrue(dao.allRows().all { it.state == DurableCommandState.CANCELLED.name })
        assertTrue(dao.allRows().all { it.stateVersion == 2L })
    }

    @Test
    fun `child admission proves and inherits the persisted parent authority`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val transaction = CommandStateTransaction(dao)
        val rootId = "00000000-0000-0000-0000-000000000601"
        val anchorId = "00000000-0000-0000-0000-000000000602"
        assertTrue(
            transaction.admit(
                authorityDraft(
                    commandId = rootId,
                    anchorId = anchorId,
                    authoritySubjectId = "authority:v1",
                ),
            ) is CommandAdmissionResult.Inserted,
        )

        val child = authorityDraft(
            commandId = "00000000-0000-0000-0000-000000000603",
            anchorId = anchorId,
            lineageId = rootId,
            parentCommandId = rootId,
            authoritySubjectId = "authority:v1",
        )
        assertTrue(transaction.admit(child) is CommandAdmissionResult.Inserted)
        assertEquals(
            CommandAdmissionResult.Invalid("ADMISSION_PARENT_SCOPE_MISMATCH"),
            transaction.admit(
                child.copy(
                    id = "00000000-0000-0000-0000-000000000604",
                    idempotencyKey = "00000000-0000-0000-0000-000000000604",
                    authoritySubjectId = "authority:v2",
                ),
            ),
        )
        assertEquals(
            CommandAdmissionResult.Invalid("ADMISSION_ROOT_LINEAGE_INVALID"),
            transaction.admit(
                authorityDraft(
                    commandId = "00000000-0000-0000-0000-000000000605",
                    lineageId = rootId,
                ),
            ),
        )
    }

    @Test
    fun `waiting and terminal retries require exact transition identity`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val transaction = CommandStateTransaction(dao = dao, nowMs = { 100L })
        val waitingRow = (transaction.admit(
            authorityDraft(commandId = "00000000-0000-0000-0000-000000000611"),
        ) as CommandAdmissionResult.Inserted).row
        val waitingClaim = (transaction.claim(
            Uuid.parse(waitingRow.id),
            Uuid.parse("00000000-0000-0000-0000-000000000612"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim
        assertTrue(transaction.markWaitingApproval(waitingClaim) is CommandTransitionResult.Applied)
        assertTrue(transaction.markWaitingApproval(waitingClaim) is CommandTransitionResult.Duplicate)

        val terminalRow = (transaction.admit(
            authorityDraft(commandId = "00000000-0000-0000-0000-000000000613"),
        ) as CommandAdmissionResult.Inserted).row
        val terminalClaim = (transaction.claim(
            Uuid.parse(terminalRow.id),
            Uuid.parse("00000000-0000-0000-0000-000000000614"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim
        assertTrue(
            transaction.finishClaimed(
                terminalClaim,
                DurableCommandState.FAILED,
                "FIRST_FAILURE",
            ) is CommandTransitionResult.Applied,
        )
        assertTrue(
            transaction.finishClaimed(
                terminalClaim,
                DurableCommandState.FAILED,
                "FIRST_FAILURE",
            ) is CommandTransitionResult.Duplicate,
        )
        assertTrue(
            transaction.finishClaimed(
                terminalClaim,
                DurableCommandState.FAILED,
                "DIFFERENT_FAILURE",
            ) is CommandTransitionResult.Conflict,
        )
    }

    @Test
    fun `admission allocates durable sequence and ignores process sequence hints`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val transaction = CommandStateTransaction(dao)
        val firstDraft = authorityDraft(
            commandId = "00000000-0000-0000-0000-000000000621",
            sequence = 9_999L,
        )
        val secondDraft = authorityDraft(
            commandId = "00000000-0000-0000-0000-000000000622",
            sequence = -4L,
        )

        val first = transaction.admit(firstDraft) as CommandAdmissionResult.Inserted
        val second = transaction.admit(secondDraft) as CommandAdmissionResult.Inserted
        assertEquals(1L, first.row.sequence)
        assertEquals(2L, second.row.sequence)
        assertEquals(
            CommandAdmissionResult.AlreadyExists(first.row),
            transaction.admit(firstDraft.copy(sequence = Long.MAX_VALUE)),
        )

        val overflowDao = FakePendingChatCommandDao()
        val saturated = authorityDraft(
            commandId = "00000000-0000-0000-0000-000000000623",
            sequence = Long.MAX_VALUE,
        ).copy(stateVersion = 1L)
        overflowDao.insert(saturated)
        assertEquals(
            CommandAdmissionResult.Conflict("SEQUENCE_EXHAUSTED"),
            CommandStateTransaction(overflowDao).admit(
                authorityDraft(commandId = "00000000-0000-0000-0000-000000000624"),
            ),
        )
    }

    @Test
    fun `transaction serialized concurrent admissions receive unique sequences`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val transactionMutex = Mutex()
        val runner = object : CommandTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T =
                transactionMutex.withLock { block() }
        }
        val transaction = CommandStateTransaction(dao = dao, transactions = runner)

        val admitted = coroutineScope {
            (1..24).map { index ->
                async {
                    transaction.admit(
                        authorityDraft(
                            commandId = "00000000-0000-0000-0000-${(700 + index).toString().padStart(12, '0')}",
                            sequence = 1L,
                        ),
                    ) as CommandAdmissionResult.Inserted
                }
            }.awaitAll()
        }
        assertEquals((1L..24L).toList(), admitted.map { it.row.sequence }.sorted())
    }

    @Test
    fun `final resume terminalizes waiting lineage in the same authority operation`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        val events = mutableListOf<CommandAuthorityEvent>()
        val transaction = CommandStateTransaction(
            dao = dao,
            events = CommandAuthorityEventPort { events.add(it) },
            nowMs = { 500L },
        )
        val rootId = "00000000-0000-0000-0000-000000000631"
        val anchorId = "00000000-0000-0000-0000-000000000632"
        val root = (transaction.admit(
            authorityDraft(commandId = rootId, anchorId = anchorId),
        ) as CommandAdmissionResult.Inserted).row
        val rootClaim = (transaction.claim(
            Uuid.parse(root.id),
            Uuid.parse("00000000-0000-0000-0000-000000000633"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim
        assertTrue(transaction.markWaitingApproval(rootClaim) is CommandTransitionResult.Applied)

        val resume = (transaction.admit(
            authorityDraft(
                commandId = "00000000-0000-0000-0000-000000000634",
                anchorId = anchorId,
                lineageId = rootId,
                parentCommandId = rootId,
                type = "resume_after_approval",
            ),
        ) as CommandAdmissionResult.Inserted).row
        val resumeClaim = (transaction.claim(
            Uuid.parse(resume.id),
            Uuid.parse("00000000-0000-0000-0000-000000000635"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim

        val result = transaction.finishClaimedAndWaitingLineage(
            resumeClaim,
            DurableCommandState.COMPLETED,
        ) as CommandLineageFinishResult.Applied
        assertEquals(setOf(Uuid.parse(rootId), Uuid.parse(resume.id)), result.terminalizedCommandIds.toSet())
        assertEquals(DurableCommandState.COMPLETED.name, dao.findById(rootId)?.state)
        assertEquals(DurableCommandState.COMPLETED.name, dao.findById(resume.id)?.state)
        assertEquals(0, events.count { it.kind == CommandAuthorityEventKind.WAITING_APPROVAL })
        assertEquals(2, events.count { it.kind == CommandAuthorityEventKind.TERMINAL })
    }

    @Test
    fun `waiting cancellation blocks stale approval child and expired pending is reaped`() = runBlocking {
        val dao = FakePendingChatCommandDao()
        var now = 100L
        val transaction = CommandStateTransaction(dao = dao, nowMs = { now })
        val rootId = "00000000-0000-0000-0000-000000000641"
        val anchorId = "00000000-0000-0000-0000-000000000642"
        val root = (transaction.admit(
            authorityDraft(commandId = rootId, anchorId = anchorId),
        ) as CommandAdmissionResult.Inserted).row
        val claim = (transaction.claim(
            Uuid.parse(root.id),
            Uuid.parse("00000000-0000-0000-0000-000000000643"),
            100.milliseconds,
        ) as CommandClaimResult.Claimed).claim
        transaction.markWaitingApproval(claim)
        val cancelled = transaction.cancelWaitingForConversation(
            CONVERSATION_ID,
            "USER_INTERRUPTED",
        ) as CommandWaitingCancellationResult.Applied
        assertEquals(listOf(Uuid.parse(rootId)), cancelled.terminalizedCommandIds)
        assertEquals(
            CommandAdmissionResult.Invalid("ADMISSION_APPROVAL_PARENT_NOT_WAITING"),
            transaction.admit(
                authorityDraft(
                    commandId = "00000000-0000-0000-0000-000000000644",
                    anchorId = anchorId,
                    lineageId = rootId,
                    parentCommandId = rootId,
                    type = "tool_approval",
                ),
            ),
        )

        val expiredId = "00000000-0000-0000-0000-000000000645"
        transaction.admit(
            authorityDraft(commandId = expiredId, expiresAt = 150L),
        )
        now = 151L
        assertEquals(1, transaction.recoverExpired())
        assertEquals(DurableCommandState.CANCELLED.name, dao.findById(expiredId)?.state)
        assertEquals("COMMAND_EXPIRED", dao.findById(expiredId)?.lastErrorCode)
    }

    private fun authorityDraft(
        commandId: String = "00000000-0000-0000-0000-000000000201",
        anchorId: String = "00000000-0000-0000-0000-000000000301",
        sequence: Long = 1L,
        lineageId: String = commandId,
        parentCommandId: String? = null,
        authoritySubjectId: String? = null,
        type: String = "send_message",
        expiresAt: Long? = null,
    ) = PendingChatCommandEntity(
        id = commandId,
        // Lineage is authority metadata, not part of the CommandCodec payload schema.
        schemaVersion = 1,
        conversationId = CONVERSATION_ID.toString(),
        authoritySubjectId = authoritySubjectId,
        assistantIdSnapshot = "00000000-0000-0000-0000-000000000401",
        lineageId = lineageId,
        parentCommandId = parentCommandId,
        branchAnchorMessageId = anchorId,
        stateVersion = 0L,
        type = type,
        payloadJson = "{\"content\":\"<redacted-test>\"}",
        state = DurableCommandState.PENDING.name,
        priority = 0,
        sequence = sequence,
        expectedTargetVersion = null,
        expectedBranchHeadMessageId = null,
        dedupeKey = null,
        idempotencyKey = commandId,
        attempt = 0,
        claimedBy = null,
        leaseUntil = null,
        createdAt = 10L,
        startedAt = null,
        finishedAt = null,
        expiresAt = expiresAt,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    private companion object {
        val CONVERSATION_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000501")
    }
}
