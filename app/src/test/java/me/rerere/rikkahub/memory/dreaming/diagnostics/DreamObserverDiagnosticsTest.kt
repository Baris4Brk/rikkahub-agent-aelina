package me.rerere.rikkahub.memory.dreaming.diagnostics

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.store.FakeDreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.StartDreamRunRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DreamObserverDiagnosticsTest {
    @Test
    fun `projection exposes only payload-free dirty and stale lease state`() = runBlocking {
        val store = FakeDreamObserverStore()
        store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(
                listOf(
                    AuthorityChange(
                        scopeId = PRIVATE_SCOPE,
                        entityKind = AuthorityEntityKind.MEMORY,
                        entityId = "not-returned-by-diagnostics",
                        entityRevision = 1L,
                        operation = AuthorityChangeOperation.UPDATE,
                        reasonCode = AuthorityChangeReason.USER_MUTATION,
                    ),
                ),
                createdAtMs = 1L,
            ),
        )
        store.startRun(
            StartDreamRunRequest(
                runId = RUN_ID,
                scopeId = PRIVATE_SCOPE,
                mode = DreamRunMode.OBSERVER_REPLAY,
                leaseOwner = "observer-a",
                nowMs = 10L,
                leaseDurationMs = 5L,
            ),
        )
        val diagnostics = StoreDreamObserverDiagnostics(store, nowMs = { 20L })

        val scope = diagnostics.readDirtyScopes().single()

        assertEquals(DreamObserverScopeStatus.STALE_LEASE, scope.status)
        assertEquals(1L, scope.pendingEpochCount)
        assertEquals(RUN_ID, scope.activeRunId)
        assertEquals(1, scope.recentRuns.size)
        assertEquals(RUN_ID, scope.recentRuns.single().runId)
        assertNull(scope.recentRuns.single().failureCode)
    }

    private companion object {
        val PRIVATE_SCOPE: DreamScopeId = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
        const val RUN_ID = "00000000-0000-0000-0000-000000000001"
    }
}
