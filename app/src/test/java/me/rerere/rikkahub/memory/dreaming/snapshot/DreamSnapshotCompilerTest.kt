package me.rerere.rikkahub.memory.dreaming.snapshot

import me.rerere.rikkahub.memory.dreaming.DreamingTestFixtures
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSnapshotCompilerTest {
    @Test
    fun `shuffled claims compile to byte-identical snapshot`() {
        val first = DreamingTestFixtures.claim()
        val second = DreamingTestFixtures.claim(
            id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            key = "project.alpha",
        )

        val left = compile(listOf(first, second))
        val right = compile(listOf(second, first))

        assertEquals(left.payloadJson, right.payloadJson)
        assertEquals(left.payloadHash, right.payloadHash)
        assertEquals(left.manifestHash, right.manifestHash)
    }

    @Test
    fun `pending content and volatile run metadata never enter active snapshot`() {
        val pending = DreamingTestFixtures.claim(state = DreamClaimState.PENDING_REVIEW)
        val compiled = compile(listOf(pending))

        assertEquals(0, compiled.claimCount)
        assertFalse(compiled.payloadJson.contains(DreamingTestFixtures.RUN_ID))
        assertFalse(compiled.payloadJson.contains(DreamingTestFixtures.NOW.toString()))
        assertTrue(compiled.payloadJson.contains("\"profile\":[]"))
        assertTrue(compiled.payloadJson.contains("\"current_projects\":[]"))
    }

    @Test
    fun `same content under different compiler version changes hash`() {
        val claims = listOf(DreamingTestFixtures.claim())
        val v1 = DreamSnapshotCompiler.compile(
            DreamSnapshotCompileRequest(DreamingTestFixtures.scope, "compiler-v1", claims),
        )
        val v2 = DreamSnapshotCompiler.compile(
            DreamSnapshotCompileRequest(DreamingTestFixtures.scope, "compiler-v2", claims),
        )
        assertFalse(v1.payloadHash == v2.payloadHash)
    }

    @Test
    fun `oversized claim fragment is rejected before aggregate construction`() {
        val giant = DreamingTestFixtures.claim().copy(statement = "界".repeat(1_000))

        val failure = assertThrows(DreamSnapshotCompilationException::class.java) {
            DreamSnapshotCompiler.compile(
                DreamSnapshotCompileRequest(
                    scopeId = DreamingTestFixtures.scope,
                    compilerRevision = "compiler-v1",
                    claims = listOf(giant),
                    limits = DreamSnapshotCompileLimits(maxClaimFragmentUtf8Bytes = 512),
                ),
            )
        }

        assertEquals(DreamSnapshotCompilationFailure.CLAIM_FRAGMENT_TOO_LARGE, failure.failure)
    }

    @Test
    fun `incremental UTF8 accounting accepts exact boundary and rejects one byte less`() {
        val claims = listOf(DreamingTestFixtures.claim())
        val baseline = compile(claims)
        val payloadBytes = baseline.payloadJson.toByteArray(Charsets.UTF_8).size
        val manifestBytes = baseline.manifestJson.toByteArray(Charsets.UTF_8).size
        val exact = DreamSnapshotCompileLimits(
            maxClaimFragmentUtf8Bytes = 96 * 1_024,
            maxManifestUtf8Bytes = manifestBytes,
            maxPayloadUtf8Bytes = payloadBytes,
        )

        val atBoundary = DreamSnapshotCompiler.compile(
            DreamSnapshotCompileRequest(DreamingTestFixtures.scope, "compiler-v1", claims, exact),
        )
        assertEquals(baseline.payloadHash, atBoundary.payloadHash)

        val failure = assertThrows(DreamSnapshotCompilationException::class.java) {
            DreamSnapshotCompiler.compile(
                DreamSnapshotCompileRequest(
                    DreamingTestFixtures.scope,
                    "compiler-v1",
                    claims,
                    exact.copy(maxPayloadUtf8Bytes = payloadBytes - 1),
                ),
            )
        }
        assertEquals(DreamSnapshotCompilationFailure.PAYLOAD_TOO_LARGE, failure.failure)
    }

    @Test
    fun `active claim count is a hard failure rather than silent truncation`() {
        val claims = listOf(
            DreamingTestFixtures.claim(),
            DreamingTestFixtures.claim(
                id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                key = "project.second",
            ),
        )

        val failure = assertThrows(DreamSnapshotCompilationException::class.java) {
            DreamSnapshotCompiler.compile(
                DreamSnapshotCompileRequest(
                    DreamingTestFixtures.scope,
                    "compiler-v1",
                    claims,
                    DreamSnapshotCompileLimits(maxActiveClaims = 1),
                ),
            )
        }
        assertEquals(DreamSnapshotCompilationFailure.ACTIVE_CLAIM_COUNT_LIMIT, failure.failure)
    }

    private fun compile(claims: List<me.rerere.rikkahub.memory.dreaming.model.DreamClaimHead>) =
        DreamSnapshotCompiler.compile(
            DreamSnapshotCompileRequest(DreamingTestFixtures.scope, "compiler-v1", claims),
        )
}
