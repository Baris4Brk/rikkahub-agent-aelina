package me.rerere.rikkahub.data.authority.reward

import me.rerere.rikkahub.data.authority.source.ConversationSourceChangeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceState
import me.rerere.rikkahub.data.authority.source.MessageSourceAuthorityHead
import me.rerere.rikkahub.data.authority.source.MessageSourceRevisionTransition
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityRevisionEntity
import me.rerere.rikkahub.data.db.projection.RewardFeedbackTargetAuthorityProjection
import me.rerere.rikkahub.learning.model.LearningFeatureCapabilities
import me.rerere.rikkahub.learning.model.LearningFeatureFlagPolicy
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningFeatureFlags
import me.rerere.rikkahub.learning.model.LearningScopeConsentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardFeedbackAuthorityRepositoryTest {
    @Test
    fun `record derives authority and fixed reward inside transaction`() = runSuspend {
        val store = FakeStore()
        val events = FakeEventPort(inserted = true)
        val repository = repository(store, events) { 100L }

        val result = repository.record(TARGET, RewardFeedbackVerdict.INCORRECT)

        assertTrue(result is RewardFeedbackWriteResult.Committed)
        val head = requireNotNull(store.head)
        assertEquals("ASSISTANT", head.scopeKind)
        assertEquals(ASSISTANT, head.scopeId)
        assertEquals(7L, head.commandRevision)
        assertEquals(3L, head.targetAssistantMessageRevision)
        assertEquals("GOAL", head.dimension)
        assertEquals("EXPLICIT_USER_CORRECTION", head.signalKind)
        assertEquals(-1_000, head.valueMilli)
        assertEquals(1, store.journal.size)
        assertEquals(head.feedbackId, events.events.single().feedbackId)
        assertEquals(1, events.wakes)
    }

    @Test
    fun `handoff disabled still commits head and append-only journal`() = runSuspend {
        val store = FakeStore()
        val events = FakeEventPort(inserted = false)
        val repository = repository(store, events) { 100L }

        val result = repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)

        assertTrue(result is RewardFeedbackWriteResult.Committed)
        assertEquals(false, (result as RewardFeedbackWriteResult.Committed).insertedOutbox)
        assertEquals(1, store.journal.size)
        assertEquals(0, events.wakes)
    }

    @Test
    fun `duplicate meaning does not create another authority revision`() = runSuspend {
        val store = FakeStore()
        val events = FakeEventPort(inserted = true)
        val repository = repository(store, events) { 100L }

        repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)
        val duplicate = repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)

        assertTrue(duplicate is RewardFeedbackWriteResult.Duplicate)
        assertEquals(1L, store.head?.sourceRevision)
        assertEquals(1, store.journal.size)
        assertEquals(1, events.events.size)
    }

    @Test
    fun `retraction is a terminal tombstone with no reward value`() = runSuspend {
        val store = FakeStore()
        val events = FakeEventPort(inserted = true)
        var now = 100L
        val repository = repository(store, events) { now++ }

        repository.record(TARGET, RewardFeedbackVerdict.NOT_HELPFUL)
        val result = repository.retract(TARGET, RewardFeedbackVerdict.HELPFUL)

        assertTrue(result is RewardFeedbackWriteResult.Committed)
        val head = requireNotNull(store.head)
        assertEquals("TOMBSTONED", head.sourceState)
        assertEquals(2L, head.sourceRevision)
        assertEquals(1L, head.previousSourceRevision)
        assertNull(head.valueMilli)
        assertEquals(2, store.journal.size)
    }

    @Test
    fun `stale target revision is rejected without a partial journal`() = runSuspend {
        val store = FakeStore(
            target = activeMessage(TARGET, "ASSISTANT", revision = 4L),
        )
        val repository = repository(store) { 100L }

        val result = repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)

        assertEquals(
            RewardFeedbackRejection.SOURCE_NOT_ACTIVE_EXACT,
            (result as RewardFeedbackWriteResult.Rejected).reason,
        )
        assertNull(store.head)
        assertTrue(store.journal.isEmpty())
    }

    @Test
    fun `source invalidation appends a replayable tombstone without caller scope or revision`() =
        runSuspend {
            val store = FakeStore()
            val events = FakeEventPort(inserted = true)
            var now = 100L
            val repository = repository(store, events) { now++ }
            repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)
            store.targetOverride = activeMessage(TARGET, "ASSISTANT", revision = 4L)

            val result = repository.invalidateIfSourceNoLongerExact(TARGET)

            assertEquals(1, result.examinedHeads)
            assertEquals(1, result.tombstonedHeads)
            assertEquals("TOMBSTONED", store.head?.sourceState)
            assertEquals(2, store.journal.size)
            assertEquals("TOMBSTONED", events.events.last().sourceState.name)
        }

    @Test
    fun `source writer seam tombstones and appends without an early wake`() = runSuspend {
        val store = FakeStore()
        val events = FakeEventPort(inserted = true)
        val repository = repository(store, events) { 100L }
        repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)
        events.wakes = 0

        val insertedOutbox = repository.invalidateInCurrentTransaction(sourceTransition())

        assertTrue(insertedOutbox)
        assertEquals("TOMBSTONED", store.head?.sourceState)
        assertEquals(2, store.journal.size)
        assertEquals(2, events.events.size)
        assertEquals(0, events.wakes)

        assertEquals(false, repository.invalidateInCurrentTransaction(sourceTransition()))
        assertEquals(2, store.journal.size)
        assertEquals(2, events.events.size)
    }

    @Test
    fun `capture denial rejects before feedback authority write`() = runSuspend {
        val store = FakeStore()
        val repository = RewardFeedbackAuthorityRepository(
            store = store,
            featureFlags = LearningFeatureFlagSource {
                LearningFeatureFlagPolicy.resolve(LearningFeatureFlags())
            },
            scopeConsent = LearningScopeConsentSource { true },
            nowMs = { 100L },
        )

        val result = repository.record(TARGET, RewardFeedbackVerdict.HELPFUL)

        assertEquals(
            RewardFeedbackRejection.CAPTURE_NOT_AUTHORIZED,
            (result as RewardFeedbackWriteResult.Rejected).reason,
        )
        assertNull(store.head)
        assertTrue(store.journal.isEmpty())
    }

    private fun repository(
        store: RewardFeedbackAuthorityStore,
        events: RewardFeedbackAuthorityEventPort = DisabledRewardFeedbackAuthorityEventPort,
        nowMs: () -> Long,
    ) = RewardFeedbackAuthorityRepository(
        store = store,
        events = events,
        featureFlags = ENABLED_FLAGS,
        scopeConsent = ALLOW_CAPTURE,
        nowMs = nowMs,
    )

    private class FakeStore(
        target: LearningMessageSourceAuthorityEntity =
            activeMessage(TARGET, "ASSISTANT", revision = 3L),
    ) : RewardFeedbackAuthorityStore, RewardFeedbackAuthorityTransaction {
        var targetOverride: LearningMessageSourceAuthorityEntity = target
        var head: RewardFeedbackAuthorityEntity? = null
        val journal = mutableListOf<RewardFeedbackAuthorityRevisionEntity>()

        override suspend fun <T> inAuthorityTransaction(
            block: suspend RewardFeedbackAuthorityTransaction.() -> T,
        ): T = block(this)

        override suspend fun <T> inCurrentAuthorityTransaction(
            block: suspend RewardFeedbackAuthorityTransaction.() -> T,
        ): T = block(this)

        override suspend fun findTerminalCommands(targetMessageId: String, limit: Int) =
            listOf(command()).take(limit)

        override suspend fun findMessage(
            scopeKind: String,
            scopeId: String,
            messageId: String,
        ): LearningMessageSourceAuthorityEntity? = when (messageId) {
            TARGET -> targetOverride
            ANCHOR -> activeMessage(ANCHOR, "USER", revision = 2L)
            else -> null
        }

        override suspend fun findHead(feedbackId: String): RewardFeedbackAuthorityEntity? =
            head?.takeIf { it.feedbackId == feedbackId }

        override suspend fun listActiveHeadsForTarget(
            targetMessageId: String,
            limit: Int,
        ): List<RewardFeedbackAuthorityEntity> = listOfNotNull(
            head?.takeIf {
                it.targetAssistantMessageId == targetMessageId && it.sourceState == "ACTIVE"
            },
        ).take(limit)

        override suspend fun insertHeadIgnore(entity: RewardFeedbackAuthorityEntity): Boolean {
            if (head != null) return false
            head = entity
            return true
        }

        override suspend fun updateHeadFenced(
            previous: RewardFeedbackAuthorityEntity,
            next: RewardFeedbackAuthorityEntity,
        ): Boolean {
            if (head != previous) return false
            head = next
            return true
        }

        override suspend fun insertRevision(entity: RewardFeedbackAuthorityRevisionEntity) {
            check(journal.none {
                it.feedbackId == entity.feedbackId && it.sourceRevision == entity.sourceRevision
            })
            journal += entity
        }
    }

    private class FakeEventPort(
        private val inserted: Boolean,
    ) : RewardFeedbackAuthorityEventPort {
        val events = mutableListOf<RewardFeedbackAuthorityEvent>()
        var wakes: Int = 0

        override suspend fun appendInCurrentTransaction(event: RewardFeedbackAuthorityEvent): Boolean {
            if (inserted) events += event
            return inserted
        }

        override fun dispatchPostCommit(insertedOutbox: Boolean) {
            if (insertedOutbox) wakes += 1
        }
    }

    private companion object {
        val ENABLED_FLAGS = LearningFeatureFlagSource {
            LearningFeatureFlagPolicy.resolve(
                configured = LearningFeatureFlags(
                    schemaReady = true,
                    handoff = true,
                    capture = true,
                    jobs = true,
                ),
                capabilities = LearningFeatureCapabilities(
                    schemaReady = true,
                    typedJobExecutionReady = true,
                ),
            )
        }
        val ALLOW_CAPTURE = LearningScopeConsentSource { true }
        const val ASSISTANT = "00000000-0000-0000-0000-000000000010"
        const val TARGET = "assistant-message-1"
        const val ANCHOR = "user-message-1"

        fun command() = RewardFeedbackTargetAuthorityProjection(
            commandId = "command-1",
            state = "COMPLETED",
            stateVersion = 7,
            conversationId = "conversation-1",
            authoritySubjectId = null,
            assistantIdSnapshot = ASSISTANT,
            lineageId = "lineage-1",
            branchAnchorMessageId = ANCHOR,
            branchAnchorMessageRevision = 2,
            conversationSourceRevision = 9,
            completionKind = "GENERATION_FINAL_SAVED",
            resultAssistantMessageId = TARGET,
            resultAssistantMessageRevision = 3,
        )

        fun activeMessage(
            id: String,
            role: String,
            revision: Long,
        ) = LearningMessageSourceAuthorityEntity(
            scopeKind = "ASSISTANT",
            scopeId = ASSISTANT,
            conversationId = "conversation-1",
            messageId = id,
            messageRole = role,
            sourceRevision = revision,
            previousSourceRevision = if (revision == 1L) null else revision - 1L,
            sourceState = "ACTIVE",
            changeKind = "UPDATED",
            payloadIntegritySha256 = "a".repeat(64),
            occurredAtMs = 10,
            updatedAtMs = 10,
        )

        fun sourceTransition(): MessageSourceRevisionTransition {
            val scope = ConversationSourceScope(ConversationSourceScopeKind.ASSISTANT, ASSISTANT)
            val previous = MessageSourceAuthorityHead(
                scope = scope,
                conversationId = "conversation-1",
                messageId = TARGET,
                messageRole = "ASSISTANT",
                sourceRevision = 3,
                previousSourceRevision = 2,
                sourceState = ConversationSourceState.ACTIVE,
                changeKind = ConversationSourceChangeKind.UPDATED,
                payloadIntegritySha256 = "a".repeat(64),
                occurredAtMs = 10,
                updatedAtMs = 10,
            )
            return MessageSourceRevisionTransition(
                previous = previous,
                current = previous.copy(
                    sourceRevision = 4,
                    previousSourceRevision = 3,
                    changeKind = ConversationSourceChangeKind.UPDATED,
                    payloadIntegritySha256 = "b".repeat(64),
                    occurredAtMs = 101,
                    updatedAtMs = 101,
                ),
            )
        }
    }
}

private fun runSuspend(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
