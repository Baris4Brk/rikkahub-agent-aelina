package me.rerere.rikkahub.data.authority.transaction

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.authority.source.ConversationSourceAuthorityHead
import me.rerere.rikkahub.data.authority.source.ConversationSourceAuthorityStore
import me.rerere.rikkahub.data.authority.source.ConversationSourceAuthorityWriter
import me.rerere.rikkahub.data.authority.source.ConversationSourceChangeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceSnapshot
import me.rerere.rikkahub.data.authority.source.ConversationSourceState
import me.rerere.rikkahub.data.authority.source.MessageSourceAuthorityHead
import me.rerere.rikkahub.data.authority.source.MessageSourceSnapshot
import me.rerere.rikkahub.data.authority.source.MessageSourceTransitionInvalidationPort
import me.rerere.rikkahub.data.authority.source.SourceAuthorityObjectKind
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEvent
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEventPort
import me.rerere.rikkahub.service.chat.CommandClaim
import me.rerere.rikkahub.service.chat.CommandCompletionAuthority
import me.rerere.rikkahub.service.chat.CommandCompletionKind
import me.rerere.rikkahub.service.chat.DurableCommandState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCommandAuthorityTransactionsTest {
    @Test
    fun `admission commits graph source anchor and command together before post commit wake`() = runBlocking {
        val fixture = Fixture()
        val coordinator = fixture.admissionCoordinator()

        val source = snapshot()
        val commit = coordinator.admit(
            command = admissionDraft(),
            graphMutation = fixture.graph.mutation(source),
            commandMutation = fixture.commands.admissionMutation(),
        )

        assertEquals(1, fixture.graph.persisted.size)
        assertEquals(1L, commit.source.messagesById.getValue(USER_MESSAGE_ID).sourceRevision)
        assertEquals(1L, commit.command.conversationSourceRevision)
        assertEquals(DurableCommandState.PENDING, commit.command.state)
        assertEquals(listOf("ADMIT"), fixture.commands.calls.map { it.operation })
        assertEquals(1, fixture.commands.dispatchCount)
        assertFalse(fixture.runner.inTransaction)
    }

    @Test
    fun `admission anchor revision conflict rolls every preceding authority write back`() = runBlocking {
        val fixture = Fixture()

        val error = assertThrows(AuthorityTransactionConflictException::class.java) {
            runBlocking {
                val source = snapshot()
                fixture.admissionCoordinator().admit(
                    command = admissionDraft(branchRevision = 2L),
                    graphMutation = fixture.graph.mutation(source),
                    commandMutation = fixture.commands.admissionMutation(),
                )
            }
        }

        assertEquals("COMMAND_BRANCH_ANCHOR_REVISION_CONFLICT", error.reasonCode)
        assertTrue(fixture.graph.persisted.isEmpty())
        assertTrue(fixture.store.isEmpty())
        assertTrue(fixture.commands.calls.isEmpty())
        assertTrue(fixture.sourceEvents.events.isEmpty())
        assertEquals(0, fixture.commands.dispatchCount)
    }

    @Test
    fun `waiting commits tool graph source approval execution and command checkpoint atomically`() = runBlocking {
        val fixture = Fixture()
        fixture.seedSource(snapshot())
        val changed = snapshot(assistantDigest = DIGEST_C, occurredAtMs = 20L)

        val commit = fixture.waitingCoordinator().checkpoint(
            claim = claim(),
            ownerCommandId = COMMAND_ID,
            assistantMessageId = ASSISTANT_MESSAGE_ID,
            graphMutation = fixture.graph.mutation(changed),
            approvalMutation = fixture.approvals.mutation(COMMAND_ID, changed),
        )

        val completion = commit.command.completion
        assertEquals(CommandCompletionKind.GENERATION_WAITING_APPROVAL, completion?.kind)
        assertEquals(2L, completion?.resultMessage?.messageRevision)
        assertEquals(2L, commit.command.conversationSourceRevision)
        assertEquals(DurableCommandState.WAITING_APPROVAL, commit.command.state)
        assertEquals(1, fixture.approvals.durable.size)
        assertEquals(1, fixture.approvals.postCommitCount)
        assertEquals(1, fixture.commands.dispatchCount)
        assertEquals(1, fixture.sourceEvents.dispatchCount)
        assertFalse(fixture.runner.inTransaction)
    }

    @Test
    fun `waiting fence failure rolls back graph revisions approval rows and outbox`() = runBlocking {
        val fixture = Fixture()
        fixture.seedSource(snapshot())
        fixture.commands.failOperation = "WAITING"

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val changed = snapshot(assistantDigest = DIGEST_C, occurredAtMs = 20L)
                fixture.waitingCoordinator().checkpoint(
                    claim = claim(),
                    ownerCommandId = COMMAND_ID,
                    assistantMessageId = ASSISTANT_MESSAGE_ID,
                    graphMutation = fixture.graph.mutation(changed),
                    approvalMutation = fixture.approvals.mutation(COMMAND_ID, changed),
                )
            }
        }

        assertTrue(fixture.graph.persisted.isEmpty())
        assertTrue(fixture.approvals.durable.isEmpty())
        assertEquals(0, fixture.approvals.postCommitCount)
        assertTrue(fixture.sourceEvents.events.isEmpty())
        assertEquals(0, fixture.sourceEvents.dispatchCount)
        assertEquals(0, fixture.commands.dispatchCount)
        assertEquals(
            1L,
            fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.sourceRevision,
        )
        assertEquals(DIGEST_B, fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.payloadIntegritySha256)
    }

    @Test
    fun `feedback invalidation failure rolls source transition and outbox back`() = runBlocking {
        val fixture = Fixture()
        fixture.seedSource(snapshot())
        val failingWriter = ConversationSourceAuthorityWriter(
            store = fixture.store,
            events = fixture.sourceEvents,
            transitionInvalidations = MessageSourceTransitionInvalidationPort {
                error("feedback_invalidation_failed")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.runner.inTransaction {
                    failingWriter.reconcileInCurrentTransaction(
                        snapshot(assistantDigest = DIGEST_C, occurredAtMs = 20L),
                    )
                }
            }
        }

        assertEquals(1L, fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.sourceRevision)
        assertEquals(DIGEST_B, fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.payloadIntegritySha256)
        assertTrue(fixture.sourceEvents.events.isEmpty())
        assertEquals(0, fixture.sourceEvents.dispatchCount)
    }

    @Test
    fun `final saved result uses exact assistant and conversation revisions from same transaction`() = runBlocking {
        val fixture = Fixture()

        val source = snapshot()
        val commit = fixture.finalCoordinator().finish(
            request = finalRequest(
                kind = CommandCompletionKind.GENERATION_FINAL_SAVED,
                state = DurableCommandState.COMPLETED,
                resultMessageId = ASSISTANT_MESSAGE_ID,
            ),
            graphMutation = fixture.graph.mutation(source),
        )

        assertEquals(CommandCompletionKind.GENERATION_FINAL_SAVED, commit.command.completion?.kind)
        assertEquals(ASSISTANT_MESSAGE_ID, commit.command.completion?.resultMessage?.messageId)
        assertEquals(1L, commit.command.completion?.resultMessage?.messageRevision)
        assertEquals(commit.source.conversation.sourceRevision, commit.command.conversationSourceRevision)
        assertEquals(DurableCommandState.COMPLETED, commit.command.state)
        assertEquals(1, fixture.commands.dispatchCount)
    }

    @Test
    fun `final result side writer observes exact assistant pair inside owning transaction`() = runBlocking {
        val fixture = Fixture()
        var observed: me.rerere.rikkahub.service.chat.CommandResultMessageAuthority? = null

        fixture.finalCoordinator().finish(
            request = finalRequest(
                kind = CommandCompletionKind.GENERATION_FINAL_SAVED,
                state = DurableCommandState.COMPLETED,
                resultMessageId = ASSISTANT_MESSAGE_ID,
            ),
            graphMutation = fixture.graph.mutation(snapshot()),
            resultMutation = FinalResultAuthorityMutation { assistant ->
                assertTrue(fixture.runner.inTransaction)
                observed = assistant
            },
        )

        assertEquals(ASSISTANT_MESSAGE_ID, observed?.messageId)
        assertEquals(1L, observed?.messageRevision)
    }

    @Test
    fun `final result side writer failure rolls back graph source and command`() = runBlocking {
        val fixture = Fixture()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.finalCoordinator().finish(
                    request = finalRequest(
                        kind = CommandCompletionKind.GENERATION_FINAL_SAVED,
                        state = DurableCommandState.COMPLETED,
                        resultMessageId = ASSISTANT_MESSAGE_ID,
                    ),
                    graphMutation = fixture.graph.mutation(snapshot()),
                    resultMutation = FinalResultAuthorityMutation { error("binding_conflict") },
                )
            }
        }

        assertTrue(fixture.graph.persisted.isEmpty())
        assertTrue(fixture.store.isEmpty())
        assertTrue(fixture.commands.calls.isEmpty())
        assertEquals(0, fixture.commands.dispatchCount)
    }

    @Test
    fun `final saved without exact assistant pair fails and rolls graph back`() = runBlocking {
        val fixture = Fixture()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val source = snapshot()
                fixture.finalCoordinator().finish(
                    request = finalRequest(
                        kind = CommandCompletionKind.GENERATION_FINAL_SAVED,
                        state = DurableCommandState.COMPLETED,
                        resultMessageId = null,
                    ),
                    graphMutation = fixture.graph.mutation(source),
                )
            }
        }

        assertTrue(fixture.graph.persisted.isEmpty())
        assertTrue(fixture.store.isEmpty())
        assertTrue(fixture.commands.calls.isEmpty())
        assertEquals(0, fixture.commands.dispatchCount)
    }

    @Test
    fun `fast path is typed separately even though its saved assistant pair is authoritative`() = runBlocking {
        val fixture = Fixture()

        val source = snapshot()
        val commit = fixture.finalCoordinator().finish(
            request = finalRequest(
                kind = CommandCompletionKind.FAST_PATH_HANDLED,
                state = DurableCommandState.COMPLETED,
                resultMessageId = ASSISTANT_MESSAGE_ID,
            ),
            graphMutation = fixture.graph.mutation(source),
        )

        assertEquals(CommandCompletionKind.FAST_PATH_HANDLED, commit.command.completion?.kind)
        assertEquals(ASSISTANT_MESSAGE_ID, commit.command.completion?.resultMessage?.messageId)
    }

    @Test
    fun `final save failure is a separate command only terminal with no result or conversation revision`() = runBlocking {
        val fixture = Fixture()
        fixture.graph.failNext = true
        val coordinator = fixture.finalCoordinator()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val source = snapshot()
                coordinator.finish(
                    request = finalRequest(
                        kind = CommandCompletionKind.GENERATION_FINAL_SAVED,
                        state = DurableCommandState.COMPLETED,
                        resultMessageId = ASSISTANT_MESSAGE_ID,
                    ),
                    graphMutation = fixture.graph.mutation(source),
                )
            }
        }
        assertTrue(fixture.store.isEmpty())
        assertTrue(fixture.commands.calls.isEmpty())
        fixture.graph.failNext = false

        val failure = coordinator.finishAfterFinalSaveFailure(
            claim = claim(),
            commandId = COMMAND_ID,
            conversationId = CONVERSATION_ID,
        )

        assertEquals(CommandCompletionKind.FAILED_FINAL_SAVE, failure.completion?.kind)
        assertNull(failure.completion?.resultMessage)
        assertNull(failure.conversationSourceRevision)
        assertEquals(DurableCommandState.FAILED, failure.state)
        assertEquals(1, fixture.commands.dispatchCount)
    }

    @Test
    fun `cancel and regenerate supersession cannot carry a saved result pair`() = runBlocking {
        listOf(
            CommandCompletionKind.CENSORED_CANCELLED,
            CommandCompletionKind.SUPERSEDED_REGENERATE,
        ).forEach { kind ->
            val fixture = Fixture()
            val source = snapshot()
            val commit = fixture.finalCoordinator().finish(
                request = finalRequest(
                    kind = kind,
                    state = DurableCommandState.CANCELLED,
                    resultMessageId = null,
                ),
                graphMutation = fixture.graph.mutation(source),
            )
            assertEquals(kind, commit.command.completion?.kind)
            assertNull(commit.command.completion?.resultMessage)
        }
    }

    @Test
    fun `transient regeneration fallback commits legacy invalidation graph source and outbox before one wake`() =
        runBlocking {
            val fixture = Fixture()
            fixture.seedSource(snapshot())

            val commit = fixture.transientFinalizationCoordinator().finish(
                graphMutation = fixture.graph.transientFinalizationMutation(
                    regeneratedSnapshot(),
                ),
            )

            assertEquals(listOf(CONVERSATION_ID), fixture.graph.legacyInvalidations)
            assertEquals(1, fixture.graph.persisted.size)
            assertEquals(1, commit.sources.size)
            assertEquals(
                2L,
                commit.sources.single().messagesById.getValue(ASSISTANT_MESSAGE_ID).sourceRevision,
            )
            assertEquals(2, fixture.sourceEvents.events.size)
            val exactMessageEvent = fixture.sourceEvents.events.single {
                it.objectKind == SourceAuthorityObjectKind.MESSAGE
            }
            assertEquals(SCOPE, exactMessageEvent.scope)
            assertEquals(ASSISTANT_MESSAGE_ID, exactMessageEvent.sourceId)
            assertEquals(1L, exactMessageEvent.previousSourceRevision)
            assertEquals(2L, exactMessageEvent.sourceRevision)
            assertEquals(ConversationSourceState.TOMBSTONED, exactMessageEvent.sourceState)
            assertEquals(ConversationSourceChangeKind.DELETED, exactMessageEvent.changeKind)
            assertEquals(1, fixture.sourceEvents.dispatchCount)
            assertFalse(fixture.runner.inTransaction)
        }

    @Test
    fun `transient regeneration fallback rolls back every authority write when source invalidation fails`() =
        runBlocking {
            val fixture = Fixture()
            fixture.seedSource(snapshot())
            val failingSources = ConversationSourceAuthorityWriter(
                store = fixture.store,
                events = fixture.sourceEvents,
                transitionInvalidations = MessageSourceTransitionInvalidationPort {
                    error("simulated_source_invalidation_failure")
                },
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    fixture.transientFinalizationCoordinator(failingSources).finish(
                        graphMutation = fixture.graph.transientFinalizationMutation(
                            regeneratedSnapshot(),
                        ),
                    )
                }
            }

            assertTrue(fixture.graph.legacyInvalidations.isEmpty())
            assertTrue(fixture.graph.persisted.isEmpty())
            assertTrue(fixture.sourceEvents.events.isEmpty())
            assertEquals(0, fixture.sourceEvents.dispatchCount)
            assertEquals(
                1L,
                fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.sourceRevision,
            )
            assertEquals(
                DIGEST_B,
                fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.payloadIntegritySha256,
            )
        }

    @Test
    fun `transient regeneration fallback replay is source and outbox idempotent`() = runBlocking {
        val fixture = Fixture()
        fixture.seedSource(snapshot())
        val coordinator = fixture.transientFinalizationCoordinator()
        val final = regeneratedSnapshot()

        val first = coordinator.finish(fixture.graph.transientFinalizationMutation(final))
        val replay = coordinator.finish(fixture.graph.transientFinalizationMutation(final))

        assertTrue(first.sources.single().didMutate)
        assertFalse(replay.sources.single().didMutate)
        assertFalse(replay.sources.single().insertedOutbox)
        assertEquals(2L, fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.sourceRevision)
        assertEquals(
            ConversationSourceState.TOMBSTONED,
            fixture.store.message(SCOPE, ASSISTANT_MESSAGE_ID)?.sourceState,
        )
        assertEquals(
            1L,
            fixture.store.message(SCOPE, REGENERATED_ASSISTANT_MESSAGE_ID)?.sourceRevision,
        )
        assertEquals(2, fixture.sourceEvents.events.size)
        assertEquals(1, fixture.sourceEvents.dispatchCount)
        assertEquals(2, fixture.graph.persisted.size)
    }

    @Test
    fun `transient regeneration fallback reconciles all known scopes with one coalesced wake`() =
        runBlocking {
            val fixture = Fixture()
            fixture.seedSource(snapshot())
            fixture.seedSource(snapshot().copy(scope = SUBJECT_SCOPE))

            val commit = fixture.transientFinalizationCoordinator().finish(
                fixture.graph.transientFinalizationMutation(
                    regeneratedSnapshot(),
                ),
            )

            assertEquals(setOf(SCOPE, SUBJECT_SCOPE), commit.sources.map { it.conversation.scope }.toSet())
            assertTrue(commit.sources.all {
                it.messagesById.getValue(ASSISTANT_MESSAGE_ID).sourceRevision == 2L
            })
            assertEquals(4, fixture.sourceEvents.events.size)
            assertEquals(1, fixture.sourceEvents.dispatchCount)
        }

    private fun Fixture.admissionCoordinator() = CommandAdmissionAuthorityCoordinator(
        transactions = runner,
        sources = sources,
        commands = commands,
    )

    private fun Fixture.waitingCoordinator() = WaitingApprovalAuthorityCoordinator(
        transactions = runner,
        sources = sources,
        commands = commands,
    )

    private fun Fixture.finalCoordinator() = FinalConversationAuthorityCoordinator(
        transactions = runner,
        sources = sources,
        commands = commands,
    )

    private fun Fixture.transientFinalizationCoordinator(
        writer: ConversationSourceAuthorityWriter = sources,
    ) = TransientConversationFinalizationAuthorityCoordinator(
        transactions = runner,
        sources = writer,
    )

    private fun admissionDraft(branchRevision: Long = 1L) = CommandAdmissionAuthorityDraft(
        commandId = COMMAND_ID,
        conversationId = CONVERSATION_ID,
        assistantIdSnapshot = ASSISTANT_ID,
        authoritySubjectId = null,
        lineageId = COMMAND_ID,
        parentCommandId = null,
        branchAnchorMessageId = USER_MESSAGE_ID,
        branchAnchorMessageRevision = branchRevision,
    )

    private fun finalRequest(
        kind: CommandCompletionKind,
        state: DurableCommandState,
        resultMessageId: String?,
    ) = CommandFinalAuthorityRequest(
        claim = claim(),
        commandId = COMMAND_ID,
        conversationId = CONVERSATION_ID,
        terminalState = state,
        completionKind = kind,
        resultAssistantMessageId = resultMessageId,
        errorCode = null,
        terminalizeWaitingLineage = false,
    )

    private fun claim(): CommandClaim = CommandClaim.create(
        commandId = Uuid.parse(COMMAND_ID),
        workerId = Uuid.parse(WORKER_ID),
        stateVersion = 2L,
        leaseUntilMs = 10_000L,
    )

    private fun snapshot(
        assistantDigest: String = DIGEST_B,
        occurredAtMs: Long = 10L,
    ) = ConversationSourceSnapshot(
        scope = SCOPE,
        conversationId = CONVERSATION_ID,
        assistantIdSnapshot = ASSISTANT_ID,
        messages = listOf(
            MessageSourceSnapshot(USER_MESSAGE_ID, "USER", DIGEST_A),
            MessageSourceSnapshot(ASSISTANT_MESSAGE_ID, "ASSISTANT", assistantDigest),
        ),
        selectedBranchMessageIds = listOf(USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID),
        occurredAtMs = occurredAtMs,
    )

    private fun regeneratedSnapshot() = ConversationSourceSnapshot(
        scope = SCOPE,
        conversationId = CONVERSATION_ID,
        assistantIdSnapshot = ASSISTANT_ID,
        messages = listOf(
            MessageSourceSnapshot(USER_MESSAGE_ID, "USER", DIGEST_A),
            MessageSourceSnapshot(REGENERATED_ASSISTANT_MESSAGE_ID, "ASSISTANT", DIGEST_C),
        ),
        selectedBranchMessageIds = listOf(USER_MESSAGE_ID, REGENERATED_ASSISTANT_MESSAGE_ID),
        occurredAtMs = 20L,
    )

    private class Fixture {
        val store = TransactionSourceStore()
        val sourceEvents = TransactionSourceEvents()
        val graph = TransactionGraphPort()
        val commands = TransactionCommandPort(CONVERSATION_ID)
        val approvals = TransactionApprovalPort()
        val runner = RollbackRunner(store, sourceEvents, graph, commands, approvals)
        val sources = ConversationSourceAuthorityWriter(store, sourceEvents)

        init {
            sourceEvents.runner = runner
            graph.runner = runner
            commands.runner = runner
            approvals.runner = runner
        }

        suspend fun seedSource(snapshot: ConversationSourceSnapshot) {
            runner.inTransaction { sources.reconcileInCurrentTransaction(snapshot) }
            sourceEvents.events.clear()
            sourceEvents.dispatchCount = 0
        }
    }

    private companion object {
        val SCOPE = ConversationSourceScope(
            ConversationSourceScopeKind.ASSISTANT,
            "00000000-0000-0000-0000-000000000001",
        )
        val SUBJECT_SCOPE = ConversationSourceScope(
            ConversationSourceScopeKind.AUTHORITY_SUBJECT,
            "authority-subject-0001",
        )
        const val ASSISTANT_ID = "00000000-0000-0000-0000-000000000001"
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000002"
        const val USER_MESSAGE_ID = "00000000-0000-0000-0000-000000000003"
        const val ASSISTANT_MESSAGE_ID = "00000000-0000-0000-0000-000000000004"
        const val REGENERATED_ASSISTANT_MESSAGE_ID = "00000000-0000-0000-0000-000000000007"
        const val COMMAND_ID = "00000000-0000-0000-0000-000000000005"
        const val WORKER_ID = "00000000-0000-0000-0000-000000000006"
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val DIGEST_C = "c".repeat(64)
    }
}

private data class TransactionWorldState(
    val source: TransactionSourceStore.State,
    val sourceEvents: TransactionSourceEvents.State,
    val graph: TransactionGraphPort.State,
    val commands: TransactionCommandPort.State,
    val approvals: TransactionApprovalPort.State,
)

private class RollbackRunner(
    private val store: TransactionSourceStore,
    private val sourceEvents: TransactionSourceEvents,
    private val graph: TransactionGraphPort,
    private val commands: TransactionCommandPort,
    private val approvals: TransactionApprovalPort,
) : AuthorityTransactionRunner {
    var inTransaction: Boolean = false
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        check(!inTransaction) { "nested_test_transaction" }
        val snapshot = TransactionWorldState(
            store.state(),
            sourceEvents.state(),
            graph.state(),
            commands.state(),
            approvals.state(),
        )
        inTransaction = true
        return try {
            block()
        } catch (error: Throwable) {
            store.restore(snapshot.source)
            sourceEvents.restore(snapshot.sourceEvents)
            graph.restore(snapshot.graph)
            commands.restore(snapshot.commands)
            approvals.restore(snapshot.approvals)
            throw error
        } finally {
            inTransaction = false
        }
    }
}

private class TransactionSourceStore : ConversationSourceAuthorityStore {
    data class State(
        val conversations: Map<String, ConversationSourceAuthorityHead>,
        val messages: Map<String, MessageSourceAuthorityHead>,
    )

    private val conversations = linkedMapOf<String, ConversationSourceAuthorityHead>()
    private val messages = linkedMapOf<String, MessageSourceAuthorityHead>()

    override suspend fun findConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ) = conversations[conversationKey(scope, conversationId)]

    override suspend fun insertConversationInitial(head: ConversationSourceAuthorityHead): Boolean {
        val key = conversationKey(head.scope, head.conversationId)
        if (key in conversations) return false
        conversations[key] = head
        return true
    }

    override suspend fun updateConversationFenced(
        expectedRevision: Long,
        head: ConversationSourceAuthorityHead,
    ): Boolean {
        val key = conversationKey(head.scope, head.conversationId)
        val current = conversations[key] ?: return false
        if (current.sourceRevision != expectedRevision || current.sourceState == ConversationSourceState.TOMBSTONED) {
            return false
        }
        conversations[key] = head
        return true
    }

    override suspend fun countConversationScopes(conversationId: String): Int =
        conversations.values.count {
            it.conversationId == conversationId &&
                it.sourceState != ConversationSourceState.TOMBSTONED
        }

    override suspend fun listConversationScopesAfter(
        conversationId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<ConversationSourceAuthorityHead> {
        val after = "$afterScopeKind\u0000$afterScopeId"
        return conversations.values.asSequence()
            .filter {
                it.conversationId == conversationId &&
                    it.sourceState != ConversationSourceState.TOMBSTONED
            }
            .filter { "${it.scope.kind.name}\u0000${it.scope.id}" > after }
            .sortedBy { "${it.scope.kind.name}\u0000${it.scope.id}" }
            .take(limit)
            .toList()
    }

    override suspend fun findMessage(scope: ConversationSourceScope, messageId: String) =
        messages[messageKey(scope, messageId)]

    override suspend fun insertMessageInitial(head: MessageSourceAuthorityHead): Boolean {
        val key = messageKey(head.scope, head.messageId)
        if (key in messages) return false
        messages[key] = head
        return true
    }

    override suspend fun updateMessageFenced(
        expectedRevision: Long,
        head: MessageSourceAuthorityHead,
    ): Boolean {
        val key = messageKey(head.scope, head.messageId)
        val current = messages[key] ?: return false
        if (current.sourceRevision != expectedRevision || current.sourceState == ConversationSourceState.TOMBSTONED) {
            return false
        }
        messages[key] = head
        return true
    }

    override suspend fun countMessagesForConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): Int = messages.values.count { it.scope == scope && it.conversationId == conversationId }

    override suspend fun listMessagesForConversationAfter(
        scope: ConversationSourceScope,
        conversationId: String,
        afterMessageId: String,
        limit: Int,
    ): List<MessageSourceAuthorityHead> = messages.values.asSequence()
        .filter { it.scope == scope && it.conversationId == conversationId && it.messageId > afterMessageId }
        .sortedBy(MessageSourceAuthorityHead::messageId)
        .take(limit)
        .toList()

    fun message(scope: ConversationSourceScope, messageId: String) = messages[messageKey(scope, messageId)]
    fun isEmpty(): Boolean = conversations.isEmpty() && messages.isEmpty()
    fun state() = State(conversations.toMap(), messages.toMap())
    fun restore(state: State) {
        conversations.clear()
        conversations.putAll(state.conversations)
        messages.clear()
        messages.putAll(state.messages)
    }

    private fun conversationKey(scope: ConversationSourceScope, id: String) =
        "${scope.kind}:${scope.id}:$id"
    private fun messageKey(scope: ConversationSourceScope, id: String) =
        "${scope.kind}:${scope.id}:$id"
}

private class TransactionSourceEvents : SourceInvalidationAuthorityEventPort {
    data class State(val events: List<SourceInvalidationAuthorityEvent>, val dispatchCount: Int)
    lateinit var runner: RollbackRunner
    val events = mutableListOf<SourceInvalidationAuthorityEvent>()
    var dispatchCount: Int = 0

    override suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean {
        check(runner.inTransaction)
        val inserted = event !in events
        if (inserted) events += event
        return inserted
    }

    override fun dispatchPostCommit(insertedOutbox: Boolean) {
        check(!runner.inTransaction)
        if (insertedOutbox) dispatchCount++
    }

    fun state() = State(events.toList(), dispatchCount)
    fun restore(state: State) {
        events.clear()
        events.addAll(state.events)
        dispatchCount = state.dispatchCount
    }
}

private class TransactionGraphPort {
    data class State(
        val persisted: List<ConversationSourceSnapshot>,
        val legacyInvalidations: List<String>,
        val failNext: Boolean,
    )
    lateinit var runner: RollbackRunner
    val persisted = mutableListOf<ConversationSourceSnapshot>()
    val legacyInvalidations = mutableListOf<String>()
    var failNext = false

    suspend fun persistInCurrentTransaction(snapshot: ConversationSourceSnapshot) {
        check(runner.inTransaction)
        if (failNext) error("simulated_final_save_failure")
        persisted += snapshot
    }

    fun mutation(snapshot: ConversationSourceSnapshot) = ConversationGraphAuthorityMutation {
        persistInCurrentTransaction(snapshot)
        snapshot
    }

    fun transientFinalizationMutation(
        snapshot: ConversationSourceSnapshot,
    ) = ConversationGraphAuthorityMutation {
        check(runner.inTransaction)
        legacyInvalidations += snapshot.conversationId
        persistInCurrentTransaction(snapshot)
        snapshot
    }

    fun state() = State(persisted.toList(), legacyInvalidations.toList(), failNext)
    fun restore(state: State) {
        persisted.clear()
        persisted.addAll(state.persisted)
        legacyInvalidations.clear()
        legacyInvalidations.addAll(state.legacyInvalidations)
        failNext = state.failNext
    }
}

private data class TransactionCommandCall(
    val operation: String,
    val completion: CommandCompletionAuthority?,
    val conversationSource: ConversationSourceAuthorityLink?,
)

private class TransactionCommandPort(
    private val expectedConversationId: String,
) : CommandCompletionAuthorityPort {
    data class State(
        val calls: List<TransactionCommandCall>,
        val dispatchCount: Int,
        val failOperation: String?,
    )

    lateinit var runner: RollbackRunner
    val calls = mutableListOf<TransactionCommandCall>()
    var dispatchCount = 0
    var failOperation: String? = null

    suspend fun admitInCurrentTransaction(
        draft: CommandAdmissionAuthorityDraft,
        conversationSource: ConversationSourceAuthorityLink,
    ): CommandAuthorityMutationReceipt {
        record("ADMIT", null, conversationSource)
        return CommandAuthorityMutationReceipt(
            commandId = draft.commandId,
            conversationId = draft.conversationId,
            stateVersion = 1L,
            state = DurableCommandState.PENDING,
            completion = null,
            conversationSource = conversationSource,
            insertedOutbox = true,
            duplicate = false,
        )
    }

    fun admissionMutation() = CommandAdmissionAuthorityMutation { draft, source ->
        admitInCurrentTransaction(draft, source)
    }

    override suspend fun markWaitingInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink,
    ): CommandAuthorityMutationReceipt {
        record("WAITING", completion, conversationSource)
        return receipt(claim, completion, conversationSource)
    }

    override suspend fun finishClaimedInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
        errorCode: String?,
        terminalizeWaitingLineage: Boolean,
    ): CommandAuthorityMutationReceipt {
        record("FINAL", completion, conversationSource)
        return receipt(claim, completion, conversationSource)
    }

    override suspend fun finishUnclaimedInCurrentTransaction(
        commandId: String,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
        errorCode: String?,
    ): CommandAuthorityMutationReceipt {
        record("UNCLAIMED_FINAL", completion, conversationSource)
        return CommandAuthorityMutationReceipt(
            commandId = commandId,
            conversationId = expectedConversationId,
            stateVersion = 3L,
            state = completion.commandState,
            completion = completion,
            conversationSource = conversationSource,
            insertedOutbox = true,
            duplicate = false,
        )
    }

    override fun dispatchPostCommit(insertedOutbox: Boolean) {
        check(!runner.inTransaction)
        if (insertedOutbox) dispatchCount++
    }

    private fun record(
        operation: String,
        completion: CommandCompletionAuthority?,
        conversationSource: ConversationSourceAuthorityLink?,
    ) {
        check(runner.inTransaction)
        calls += TransactionCommandCall(operation, completion, conversationSource)
        if (failOperation == operation) error("simulated_$operation failure")
    }

    private fun receipt(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
    ) = CommandAuthorityMutationReceipt(
        commandId = claim.commandId.toString(),
        conversationId = expectedConversationId,
        stateVersion = claim.stateVersion + 1L,
        state = completion.commandState,
        completion = completion,
        conversationSource = conversationSource,
        insertedOutbox = true,
        duplicate = false,
    )

    fun state() = State(calls.toList(), dispatchCount, failOperation)
    fun restore(state: State) {
        calls.clear()
        calls.addAll(state.calls)
        dispatchCount = state.dispatchCount
        failOperation = state.failOperation
    }
}

private class TransactionApprovalPort {
    data class State(val durable: List<String>, val postCommitCount: Int)
    lateinit var runner: RollbackRunner
    val durable = mutableListOf<String>()
    var postCommitCount = 0

    suspend fun persistInCurrentTransaction(
        conversation: ConversationSourceSnapshot,
        ownerCommandId: String,
        assistantMessage: me.rerere.rikkahub.service.chat.CommandResultMessageAuthority,
    ): ApprovalBarrierAuthorityReceipt {
        check(runner.inTransaction)
        durable += "$ownerCommandId:${assistantMessage.messageId}:${assistantMessage.messageRevision}"
        return ApprovalBarrierAuthorityReceipt(
            insertedOutbox = true,
            postCommit = {
                check(!runner.inTransaction)
                postCommitCount++
            },
        )
    }

    fun mutation(
        ownerCommandId: String,
        conversation: ConversationSourceSnapshot,
    ) = ApprovalBarrierAuthorityMutation { assistantMessage ->
        persistInCurrentTransaction(conversation, ownerCommandId, assistantMessage)
    }

    fun state() = State(durable.toList(), postCommitCount)
    fun restore(state: State) {
        durable.clear()
        durable.addAll(state.durable)
        postCommitCount = state.postCommitCount
    }
}
