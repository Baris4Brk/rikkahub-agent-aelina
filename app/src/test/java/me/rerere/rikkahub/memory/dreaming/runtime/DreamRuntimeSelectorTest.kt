package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotSection
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamRuntimeSelectorTest {
    @Test
    fun `only the three V1 current-state claim types are eligible`() {
        val project = DreamRuntimeTestFixtures.claim()
        val plan = DreamRuntimeTestFixtures.claim(
            id = DreamRuntimeTestFixtures.CLAIM_B,
            section = DreamSnapshotSection.ACTIVE_PLANS,
            epistemicType = DreamEpistemicType.PLAN,
            temporalState = TemporalState.UPCOMING,
            validFromEpochMs = null,
        )
        val constraint = DreamRuntimeTestFixtures.claim(
            id = DreamRuntimeTestFixtures.CLAIM_C,
            section = DreamSnapshotSection.ACTIVE_CONSTRAINTS,
            epistemicType = DreamEpistemicType.CONSTRAINT,
            temporalState = TemporalState.TIMELESS,
            validFromEpochMs = null,
            validToEpochMs = null,
        )

        val result = DreamContextCompiler.compile(
            DreamRuntimeTestFixtures.request(
                projection = DreamRuntimeTestFixtures.projection(
                    claims = listOf(constraint, plan, project),
                ),
            ),
        )

        assertEquals(DreamRuntimeCompileStatus.COMPILED, result.status)
        assertEquals(
            listOf(project.ref, plan.ref, constraint.ref),
            result.actualClaimRefs,
        )
        assertEquals(3, result.actualClaimCount)
    }

    @Test
    fun `preference belief profile rejected stale multihop and expired claims are excluded`() {
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                section = DreamSnapshotSection.PROFILE,
                storageClass = DreamStorageClass.PROFILE,
                epistemicType = DreamEpistemicType.PREFERENCE_SUMMARY,
            ),
            expected = DreamRuntimeDropReason.DERIVED_PREFERENCE_EXCLUDED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                section = DreamSnapshotSection.OTHER_CONTEXT,
                epistemicType = DreamEpistemicType.BELIEF,
            ),
            expected = DreamRuntimeDropReason.BELIEF_EXCLUDED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(storageClass = DreamStorageClass.PROFILE),
            expected = DreamRuntimeDropReason.PROFILE_STORAGE_EXCLUDED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(currentState = DreamClaimState.REJECTED),
            expected = DreamRuntimeDropReason.CLAIM_REJECTED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(currentState = DreamClaimState.STALE),
            expected = DreamRuntimeDropReason.CLAIM_STALE,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(indirectSourceCount = 1),
            expected = DreamRuntimeDropReason.MULTIHOP_SOURCE_EXCLUDED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(directSupportingSourceCount = 0),
            expected = DreamRuntimeDropReason.NO_DIRECT_SUPPORTING_SOURCE,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                sourceValidity = DreamRuntimeSourceValidity.EXPIRED,
            ),
            expected = DreamRuntimeDropReason.SOURCE_NOT_CURRENT,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                validToEpochMs = DreamRuntimeTestFixtures.NOW,
            ),
            expected = DreamRuntimeDropReason.EXPIRED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                temporalState = TemporalState.PAST_UNVERIFIED,
            ),
            expected = DreamRuntimeDropReason.TEMPORAL_STATE_NOT_CURRENT,
        )
    }

    @Test
    fun `live head revision and exact frozen source check must match the snapshot member`() {
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(currentRevision = 2L),
            expected = DreamRuntimeDropReason.CLAIM_REVISION_CHANGED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                currentVersionHash = me.rerere.rikkahub.memory.dreaming.model.DreamSha256(
                    "3".repeat(64),
                ),
            ),
            expected = DreamRuntimeDropReason.CLAIM_VERSION_HASH_CHANGED,
        )
        assertSingleDrop(
            claim = DreamRuntimeTestFixtures.claim(
                sourceCheckedAtEpochMs = DreamRuntimeTestFixtures.NOW - 1L,
            ),
            expected = DreamRuntimeDropReason.SOURCE_CHECK_TIME_MISMATCH,
        )
    }

    @Test
    fun `explicit ranker can order a subset but cannot invent or duplicate refs`() {
        val first = DreamRuntimeTestFixtures.claim()
        val second = DreamRuntimeTestFixtures.claim(
            id = DreamRuntimeTestFixtures.CLAIM_B,
            ordinal = 1,
        )
        val third = DreamRuntimeTestFixtures.claim(
            id = DreamRuntimeTestFixtures.CLAIM_C,
            ordinal = 2,
        )
        val projection = DreamRuntimeTestFixtures.projection(listOf(first, second, third))
        val result = DreamContextCompiler.compile(
            DreamRuntimeTestFixtures.request(
                projection = projection,
                ranking = DreamRuntimeRanking.Explicit(listOf(third.ref, first.ref)),
            ),
        )

        assertEquals(listOf(third.ref, first.ref), result.actualClaimRefs)
        assertEquals(
            DreamRuntimeDropReason.NOT_SELECTED_BY_RANKER,
            result.dropped.single { it.ref == second.ref }.reason,
        )

        val duplicate = DreamContextCompiler.compile(
            DreamRuntimeTestFixtures.request(
                projection = projection,
                ranking = DreamRuntimeRanking.Explicit(listOf(first.ref, first.ref)),
            ),
        )
        assertEquals(DreamRuntimeCompileStatus.INVALID_REQUEST, duplicate.status)
        assertTrue(
            DreamRuntimeRequestFailure.DUPLICATE_RANK_REFERENCE in duplicate.requestFailures,
        )

        val unknown = DreamRuntimeClaimRef(
            "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            1L,
        )
        val invented = DreamContextCompiler.compile(
            DreamRuntimeTestFixtures.request(
                projection = projection,
                ranking = DreamRuntimeRanking.Explicit(listOf(unknown)),
            ),
        )
        assertTrue(DreamRuntimeRequestFailure.UNKNOWN_RANK_REFERENCE in invented.requestFailures)
    }

    private fun assertSingleDrop(
        claim: DreamRuntimeClaimProjection,
        expected: DreamRuntimeDropReason,
    ) {
        val result = DreamContextCompiler.compile(
            DreamRuntimeTestFixtures.request(
                projection = DreamRuntimeTestFixtures.projection(listOf(claim)),
            ),
        )
        assertEquals(DreamRuntimeCompileStatus.EMPTY, result.status)
        assertEquals(emptyList<DreamRuntimeClaimRef>(), result.actualClaimRefs)
        assertEquals(expected, result.dropped.single().reason)
    }
}
