package me.rerere.rikkahub.memory.dreaming.review

import me.rerere.rikkahub.memory.dreaming.DreamingTestFixtures
import me.rerere.rikkahub.memory.dreaming.model.DREAM_SNAPSHOT_SCHEMA_VERSION
import me.rerere.rikkahub.memory.dreaming.model.DreamCanonicalJson
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimMutationReason
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimVersionCanonicalV1
import me.rerere.rikkahub.memory.dreaming.model.DreamValidatedClaimVersion
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotCompileRequest
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSnapshotDiffTest {
    @Test
    fun `diff reports only added updated and retired with confidence and temporal flags`() {
        val first = DreamingTestFixtures.claim()
        val retired = DreamingTestFixtures.claim(
            id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            key = "retired",
        )
        val updated = first.copy(
            revision = 2,
            confidencePermille = 800,
            validToEpochMs = first.validToEpochMs?.plus(1_000),
        )
        val added = DreamingTestFixtures.claim(
            id = DreamingTestFixtures.NEW_CLAIM_ID,
            key = "added",
        )

        val result = DreamSnapshotDiff.compare(
            previous = document("11111111-1111-4111-8111-111111111112", listOf(first, retired)),
            current = document("11111111-1111-4111-8111-111111111113", listOf(updated, added)),
        ) as DreamSnapshotDiffResult.Available

        assertEquals(
            setOf(
                DreamSnapshotChangeType.ADDED,
                DreamSnapshotChangeType.UPDATED,
                DreamSnapshotChangeType.RETIRED,
            ),
            result.changes.mapTo(mutableSetOf(), DreamSnapshotChange::type),
        )
        val changed = result.changes.single { it.type == DreamSnapshotChangeType.UPDATED }
        assertTrue(changed.confidenceChanged)
        assertTrue(changed.temporalChanged)
    }

    @Test
    fun `payload hash mismatch fails closed`() {
        val document = document(
            "11111111-1111-4111-8111-111111111113",
            listOf(DreamingTestFixtures.claim()),
        ).copy(payloadHash = me.rerere.rikkahub.memory.dreaming.model.DreamSha256("0".repeat(64)))

        assertEquals(
            DreamSnapshotDiffResult.Unavailable(DreamSnapshotDiffFailure.PAYLOAD_HASH_MISMATCH),
            DreamSnapshotDiff.compare(previous = null, current = document),
        )
    }

    @Test
    fun `fragment mutation with recomputed payload hash still fails manifest binding`() {
        val original = document(
            "11111111-1111-4111-8111-111111111113",
            listOf(DreamingTestFixtures.claim()),
        )
        val mutatedJson = original.payloadJson.replace("Offline memory project", "Altered memory project")
        val mutated = original.copy(
            payloadJson = mutatedJson,
            payloadHash = DreamCanonicalJson.sha256(mutatedJson.toByteArray(Charsets.UTF_8)),
        )

        assertEquals(
            DreamSnapshotDiffResult.Unavailable(DreamSnapshotDiffFailure.FRAGMENT_INVALID),
            DreamSnapshotDiff.compare(previous = null, current = mutated),
        )
    }

    @Test
    fun `both review reasons are part of the canonical immutable claim codec`() {
        val head = DreamingTestFixtures.claim()
        listOf(
            DreamClaimMutationReason.USER_REJECTED to DreamClaimState.REJECTED,
            DreamClaimMutationReason.USER_CORRECTION to DreamClaimState.SUPERSEDED,
        ).forEach { (reason, state) ->
            val encoded = DreamClaimVersionCanonicalV1.encode(
                DreamValidatedClaimVersion(
                    claimId = head.claimId,
                    expectedPreviousRevision = head.revision,
                    nextRevision = head.revision + 1,
                    claimKey = head.claimKey,
                    storageClass = head.storageClass,
                    epistemicType = head.epistemicType,
                    nextState = state,
                    title = head.title,
                    statement = head.statement,
                    confidencePermille = head.confidencePermille,
                    temporalState = head.temporalState,
                    validFromEpochMs = head.validFromEpochMs,
                    validToEpochMs = head.validToEpochMs,
                    sources = head.sources,
                    reason = reason,
                ),
            )
            assertTrue(encoded.canonicalClaimJson.contains("\"reason\":\"${reason.name}\""))
        }
    }

    private fun document(
        snapshotId: String,
        claims: List<me.rerere.rikkahub.memory.dreaming.model.DreamClaimHead>,
    ): DreamSnapshotDocument {
        val compiled = DreamSnapshotCompiler.compile(
            DreamSnapshotCompileRequest(
                scopeId = DreamingTestFixtures.scope,
                compilerRevision = "review-test-v1",
                claims = claims,
            ),
        )
        return DreamSnapshotDocument(
            scopeId = DreamingTestFixtures.scope,
            snapshotId = snapshotId,
            schemaVersion = DREAM_SNAPSHOT_SCHEMA_VERSION,
            compilerRevision = compiled.compilerRevision,
            payloadJson = compiled.payloadJson,
            payloadHash = compiled.payloadHash,
            manifestHash = compiled.manifestHash,
            claimCount = compiled.claimCount,
        )
    }
}
