package me.rerere.rikkahub.memory.dreaming.review

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import me.rerere.rikkahub.memory.MemorySourceInvalidationBatch
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.store.FakeDreamObserverStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMutationDreamAuthorityCorrectionPortTest {
    @Test
    fun `committed authority returns the resulting scope epoch`() = runBlocking {
        val observer = FakeDreamObserverStore()
        observer.ensureScopeState(SCOPE, 10L)
        observer.recordAuthorityChangesInCurrentTransaction(
            me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest(
                changes = listOf(
                    me.rerere.rikkahub.memory.dreaming.model.AuthorityChange(
                        scopeId = SCOPE,
                        entityKind = me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind.MEMORY,
                        entityId = "17",
                        entityRevision = 1L,
                        operation = me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation.CREATE,
                        reasonCode = me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason.USER_MUTATION,
                    ),
                ),
                createdAtMs = 11L,
            ),
        )
        val port = MemoryMutationDreamAuthorityCorrectionPort(
            mutationCoordinator = RecordingMutationCoordinator(MemoryMutationResult.Applied(17, 1)),
            observerStore = observer,
        )

        val result = port.create(request())

        assertTrue(result is DreamAuthorityCorrectionResult.Applied)
        val applied = result as DreamAuthorityCorrectionResult.Applied
        assertEquals(1L, applied.resultingMemoryEpoch)
    }

    @Test
    fun `missing epoch after commit is rebuild pending instead of a failed authority write`() = runBlocking {
        val port = MemoryMutationDreamAuthorityCorrectionPort(
            mutationCoordinator = RecordingMutationCoordinator(MemoryMutationResult.Applied(17, 1)),
            observerStore = FakeDreamObserverStore(),
        )

        assertEquals(
            DreamAuthorityCorrectionResult.AppliedRebuildPending(17, 1),
            port.create(request()),
        )
    }

    @Test
    fun `missing captured assistant never writes global authority`() = runBlocking {
        val coordinator = RecordingMutationCoordinator(MemoryMutationResult.Applied(17, 1))
        val port = MemoryMutationDreamAuthorityCorrectionPort(coordinator, FakeDreamObserverStore())

        val result = port.create(request().copy(capturedOriginAssistantId = null))

        assertEquals(
            DreamAuthorityCorrectionResult.Rejected("dream_correction_origin_missing"),
            result,
        )
        assertEquals(null, coordinator.command)
    }

    private fun request() = DreamAuthorityCorrectionRequest(
        mutationId = "10000000-0000-0000-0000-000000000001",
        scopeId = SCOPE,
        title = "Project",
        content = "The project uses a reviewed correction.",
        kind = me.rerere.rikkahub.memory.MemoryKind.OTHER,
        tags = listOf("project"),
        expiresAtEpochMs = null,
        capturedOriginAssistantId = SCOPE.value,
        approvalSource = MemoryApprovalSource.USER_REVIEWED,
    )

    private class RecordingMutationCoordinator(
        private val result: MemoryMutationResult,
    ) : MemoryMutationCoordinator {
        var command: MemoryMutationCommand? = null

        override suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult {
            this.command = command
            return result
        }

        override suspend fun invalidateSourceConversation(scopeId: String, conversationId: String) = 0
        override suspend fun invalidateSourceMessages(
            scopeId: String,
            conversationId: String,
            messageIds: Set<String>,
        ) = 0
        override suspend fun runRetention() = 0
        override suspend fun purgeScope(scopeId: String) = 0
        override suspend fun invalidateSources(batch: MemorySourceInvalidationBatch, nowMs: Long) = 0
    }

    private companion object {
        val SCOPE = DreamScopeId.requireCanonical("00000000-0000-0000-0000-000000000001")
    }
}
