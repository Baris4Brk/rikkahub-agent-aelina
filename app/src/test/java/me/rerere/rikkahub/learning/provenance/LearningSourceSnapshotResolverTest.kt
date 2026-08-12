package me.rerere.rikkahub.learning.provenance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningSourceSnapshotResolverTest {
    @Test
    fun revisionChangeBeforeCommitRejectsResultAndClearsSnapshot() = runBlocking {
        val fake = FakeResolver()
        val request = LearningSourceSnapshotRequest(
            source = SOURCE,
            expectedScope = SCOPE,
            maxChars = 100,
            frozenNowMs = 10,
            expiresAtMs = 100,
        )
        val clockValues = ArrayDeque(listOf(20L, 30L))
        var snapshot: LearningEphemeralSourceSnapshot? = null
        var commitCalled = false
        val result = LearningSourceSnapshotGuard(fake) { clockValues.removeFirst() }
            .withValidatedSnapshot(
                request = request,
                derive = { value ->
                    snapshot = value
                    fake.revalidateCount = 1
                    value.useText { text -> assertEquals(14, text.length) }
                },
                commit = { commitCalled = true },
            )
        assertEquals(
            LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.REVISION_MISMATCH),
            result,
        )
        assertTrue(!commitCalled)
        assertTrue(requireNotNull(snapshot).isClearedForTest())
    }

    @Test
    fun expiryDuringProviderCallRejectsCommitAndClearsTransferredBuffer() = runBlocking {
        val buffer = "synthetic text".toCharArray()
        val fake = FakeResolver(buffer = buffer)
        val clockValues = ArrayDeque(listOf(20L, 101L))
        var snapshot: LearningEphemeralSourceSnapshot? = null
        var commitCalled = false
        val result = LearningSourceSnapshotGuard(fake) { clockValues.removeFirst() }
            .withValidatedSnapshot(
                request = LearningSourceSnapshotRequest(
                    source = SOURCE,
                    expectedScope = SCOPE,
                    maxChars = 100,
                    frozenNowMs = 10,
                    expiresAtMs = 100,
                ),
                derive = { value ->
                    snapshot = value
                    value.useText { }
                },
                commit = { commitCalled = true },
            )
        assertEquals(
            LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.EXPIRED),
            result,
        )
        assertTrue(!commitCalled)
        assertTrue(requireNotNull(snapshot).isClearedForTest())
        assertTrue(buffer.all { it == '\u0000' })
    }

    @Test
    fun resolverCannotReturnAnotherScopeOrLongerLifetime() = runBlocking {
        val wrongSource = SOURCE.copy(
            scope = LearningScope.Assistant(
                Uuid.parse("00000000-0000-0000-0000-000000000099"),
            ),
        )
        val fake = FakeResolver(returnedSource = wrongSource, expiresAtMs = 101)
        val result = LearningSourceSnapshotGuard(fake) { 20L }.withValidatedSnapshot(
            request = LearningSourceSnapshotRequest(
                source = SOURCE,
                expectedScope = SCOPE,
                maxChars = 100,
                frozenNowMs = 10,
                expiresAtMs = 100,
            ),
            derive = { error("mismatched snapshot must not be exposed") },
            commit = { error("mismatched snapshot must not be committed") },
        )
        assertEquals(
            LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.SNAPSHOT_MISMATCH),
            result,
        )
    }

    @Test
    fun clockRollbackFailsClosed() = runBlocking {
        val result = LearningSourceSnapshotGuard(FakeResolver()) { 9L }.withValidatedSnapshot(
            request = LearningSourceSnapshotRequest(
                source = SOURCE,
                expectedScope = SCOPE,
                maxChars = 100,
                frozenNowMs = 10,
                expiresAtMs = 100,
            ),
            derive = { error("rollback must reject before use") },
            commit = { error("rollback must reject before commit") },
        )
        assertEquals(
            LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.CLOCK_ROLLBACK),
            result,
        )
    }

    @Test
    fun requestSpecificLimitIsCheckedEvenWhenResolverViolatesItsContract() = runBlocking {
        var deriveCalled = false
        var commitCalled = false
        val result = LearningSourceSnapshotGuard(
            FakeResolver(buffer = "12345".toCharArray()),
        ) { 20L }.withValidatedSnapshot(
            request = LearningSourceSnapshotRequest(
                source = SOURCE,
                expectedScope = SCOPE,
                maxChars = 4,
                frozenNowMs = 10,
                expiresAtMs = 100,
            ),
            derive = { deriveCalled = true },
            commit = { commitCalled = true },
        )

        assertEquals(
            LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.TOO_LARGE),
            result,
        )
        assertTrue(!deriveCalled)
        assertTrue(!commitCalled)
    }

    @Test
    fun successfulCommitRunsOnlyAfterSnapshotWasWipedAndSecondAuthorityCheck() = runBlocking {
        val events = mutableListOf<String>()
        var snapshot: LearningEphemeralSourceSnapshot? = null
        val resolver = object : LearningSourceSnapshotResolver {
            override suspend fun resolve(
                request: LearningSourceSnapshotRequest,
            ): LearningSourceSnapshotResult = LearningSourceSnapshotResult.Available(
                LearningEphemeralSourceSnapshot(
                    source = request.source,
                    alias = "E1",
                    text = "synthetic text".toCharArray(),
                    expiresAtMs = request.expiresAtMs,
                ),
            )

            override suspend fun revalidate(source: LearningSourceRef): LearningSourceReadFailure? {
                events += "revalidate"
                return null
            }
        }
        val clockValues = ArrayDeque(listOf(20L, 30L))
        val result = LearningSourceSnapshotGuard(resolver) { clockValues.removeFirst() }
            .withValidatedSnapshot(
                request = LearningSourceSnapshotRequest(
                    source = SOURCE,
                    expectedScope = SCOPE,
                    maxChars = 100,
                    frozenNowMs = 10,
                    expiresAtMs = 100,
                ),
                derive = { value ->
                    snapshot = value
                    events += "derive"
                    value.useText { text -> assertEquals("synthetic text", text) }
                },
                commit = {
                    assertTrue(requireNotNull(snapshot).isClearedForTest())
                    events += "commit"
                },
            )

        assertEquals(LearningGuardedSourceResult.Completed, result)
        assertEquals(listOf("revalidate", "derive", "revalidate", "commit"), events)
    }

    @Test
    fun noOpResolverNeverExposesAuthorityText() = runBlocking {
        var deriveCalled = false
        val result = LearningSourceSnapshotGuard(NoOpLearningSourceSnapshotResolver) { 20L }
            .withValidatedSnapshot(
                request = LearningSourceSnapshotRequest(
                    source = SOURCE,
                    expectedScope = SCOPE,
                    maxChars = 100,
                    frozenNowMs = 10,
                    expiresAtMs = 100,
                ),
                derive = { deriveCalled = true },
                commit = { error("NoOp resolver cannot commit") },
            )

        assertEquals(
            LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.UNAVAILABLE),
            result,
        )
        assertTrue(!deriveCalled)
    }

    @Test
    fun derivationCancellationPropagatesAndWipesSnapshot() = runBlocking {
        var snapshot: LearningEphemeralSourceSnapshot? = null
        val failure = runCatching {
            LearningSourceSnapshotGuard(FakeResolver()) { 20L }.withValidatedSnapshot(
                request = LearningSourceSnapshotRequest(
                    source = SOURCE,
                    expectedScope = SCOPE,
                    maxChars = 100,
                    frozenNowMs = 10,
                    expiresAtMs = 100,
                ),
                derive = { value ->
                    snapshot = value
                    throw CancellationException("synthetic cancellation")
                },
                commit = { error("cancelled derivation cannot commit") },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(requireNotNull(snapshot).isClearedForTest())
    }

    @Test(expected = IllegalArgumentException::class)
    fun crossScopeRequestFailsBeforeResolverRead() {
        LearningSourceSnapshotRequest(
            source = SOURCE,
            expectedScope = LearningScope.Assistant(
                Uuid.parse("00000000-0000-0000-0000-000000000099"),
            ),
            maxChars = 100,
            frozenNowMs = 10,
            expiresAtMs = 100,
        )
    }

    private class FakeResolver(
        private val buffer: CharArray = "synthetic text".toCharArray(),
        private val returnedSource: LearningSourceRef? = null,
        private val expiresAtMs: Long? = null,
    ) : LearningSourceSnapshotResolver {
        var revalidateCount = 0

        override suspend fun resolve(
            request: LearningSourceSnapshotRequest,
        ): LearningSourceSnapshotResult = LearningSourceSnapshotResult.Available(
            LearningEphemeralSourceSnapshot(
                source = returnedSource ?: request.source,
                alias = "E1",
                text = buffer,
                expiresAtMs = expiresAtMs ?: request.expiresAtMs,
            ),
        )

        override suspend fun revalidate(source: LearningSourceRef): LearningSourceReadFailure? =
            if (revalidateCount++ == 0) null else LearningSourceReadFailure.REVISION_MISMATCH
    }

    private companion object {
        val STREAM = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val ASSISTANT = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val SCOPE = LearningScope.Assistant(ASSISTANT)
        val SOURCE = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "command-1",
            sourceRevision = 1,
            missingRevisionReason = null,
            databaseStreamId = STREAM,
            scope = SCOPE,
            occurredAtMs = 1,
        )
    }
}
