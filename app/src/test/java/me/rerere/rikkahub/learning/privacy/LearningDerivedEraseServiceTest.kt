package me.rerere.rikkahub.learning.privacy

import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningDerivedEraseServiceTest {
    @Test
    fun confirmationTokenIsTypedAndScopeIsForwardedExactly() = runBlocking {
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        var observedScope: LearningScope? = null
        var observedNow = -1L
        val receipt = LearningEraseReceipt(
            erasedEpisodes = 1,
            erasedTraceFeatures = 2,
            erasedLessons = 3,
            erasedRewards = 4,
            erasedPolicies = 5,
            retainedAuditTombstones = 0,
            erasedSourceValidityRows = 6,
            erasedJobs = 7,
            erasedInboxEvents = 8,
            erasedMainDatabaseWorkflows = 9,
        )
        val service = LearningDerivedEraseService(
            store = LearningDerivedEraseStore { actualScope, frozenNowMs ->
                observedScope = actualScope
                observedNow = frozenNowMs
                receipt
            },
            clockMs = { 42L },
        )
        val token = service.issueConfirmation(scope)

        assertEquals(receipt, service.eraseConfirmed(scope, token))
        assertEquals(scope, observedScope)
        assertEquals(42L, observedNow)
    }

    @Test
    fun receiptRejectsNegativeCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            LearningEraseReceipt(-1, 0, 0, 0, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningEraseReceipt(
                erasedEpisodes = 0,
                erasedTraceFeatures = 0,
                erasedLessons = 0,
                erasedRewards = 0,
                erasedPolicies = 0,
                retainedAuditTombstones = 0,
                erasedMainDatabaseWorkflows = -1,
            )
        }
    }

    @Test
    fun ephemeralRegistryAttemptsEveryHandleWhenOneFails() {
        val registry = LearningEphemeralScopeRegistry()
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        var secondCalled = false
        registry.register(LearningEphemeralSnapshotHandle { false })
        registry.register(
            LearningEphemeralSnapshotHandle {
                secondCalled = true
                true
            },
        )

        assertFalse(registry.clearForScope(scope))
        assertEquals(true, secondCalled)
    }

    @Test
    fun serviceClearsLiveEphemeralStateBeforeStorageErase() = runBlocking {
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        val registry = LearningEphemeralScopeRegistry()
        var cleared = false
        var storeCalledAfterClear = false
        registry.register(
            LearningEphemeralSnapshotHandle {
                cleared = true
                true
            },
        )
        val service = LearningDerivedEraseService(
            store = LearningDerivedEraseStore { _, _ ->
                storeCalledAfterClear = cleared
                LearningEraseReceipt(0, 0, 0, 0, 0, 0)
            },
            ephemeralRegistry = registry,
            clockMs = { 1L },
        )

        service.eraseConfirmed(scope, service.issueConfirmation(scope))
        assertTrue(storeCalledAfterClear)
    }

    @Test
    fun failedEphemeralClearPreventsAnyDatabaseErase() = runBlocking {
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        val registry = LearningEphemeralScopeRegistry()
        registry.register(LearningEphemeralSnapshotHandle { false })
        var storeCalls = 0
        val service = LearningDerivedEraseService(
            store = LearningDerivedEraseStore { _, _ ->
                storeCalls += 1
                LearningEraseReceipt(0, 0, 0, 0, 0, 0)
            },
            ephemeralRegistry = registry,
            clockMs = { 1L },
        )
        val failure = runCatching {
            service.eraseConfirmed(scope, service.issueConfirmation(scope))
        }.exceptionOrNull() as LearningDerivedEraseUnavailableException

        assertEquals(LearningDerivedEraseFailureCode.EPHEMERAL_CLEAR_FAILED, failure.failureCode)
        assertEquals(0, storeCalls)
    }
}
