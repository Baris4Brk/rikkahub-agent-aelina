package me.rerere.rikkahub.owner

import me.rerere.rikkahub.data.ai.tools.ownerActionGuideCoverageGaps
import me.rerere.rikkahub.data.ai.tools.ownerToolSchemaUtf8Bytes
import me.rerere.rikkahub.data.repository.MemoryRelationReviewEndpoint
import me.rerere.rikkahub.data.repository.MemoryRelationReviewRecord
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.DreamSupportType
import me.rerere.rikkahub.memory.dreaming.review.DreamClaimDetail
import me.rerere.rikkahub.memory.dreaming.review.DreamClaimMutationTarget
import me.rerere.rikkahub.memory.dreaming.review.DreamClaimSummary
import me.rerere.rikkahub.memory.dreaming.review.DreamDerivedStatus
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceReference
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceSummary
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceValidity
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewFence
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewProjection
import me.rerere.rikkahub.memory.dreaming.review.DreamRunUsageSummary
import me.rerere.rikkahub.memory.dreaming.review.DreamSnapshotDiffFailure
import me.rerere.rikkahub.memory.dreaming.review.DreamSnapshotDiffResult
import me.rerere.rikkahub.memory.dreaming.review.DreamSnapshotSummary
import me.rerere.rikkahub.memory.dreaming.review.DreamUsageMode
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotSection
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerActionRegistryTest {
    @Test
    fun `registry preserves every legacy family and action while exposing playback speed`() {
        assertEquals(OwnerToolFamily.entries.toSet(), OwnerActionRegistry.families.map { it.family }.toSet())
        assertEquals(167, OwnerActionRegistry.actionCount())
        assertTrue(OwnerActionRegistry.action(OwnerToolFamily.TTS, "tts_get_playback_speed") != null)
        assertTrue(OwnerActionRegistry.action(OwnerToolFamily.TTS, "tts_set_playback_speed") != null)
        assertTrue(OwnerActionRegistry.action(OwnerToolFamily.APP_SETTINGS, "reverse_geocoder_upsert") != null)
        assertTrue(OwnerActionRegistry.action(OwnerToolFamily.APP_SETTINGS, "reverse_geocoder_test") != null)
        val memoryDelete = requireNotNull(
            OwnerActionRegistry.action(OwnerToolFamily.MEMORY, "memory_delete"),
        )
        assertEquals(OwnerOperationRisk.REVERSIBLE_WRITE, memoryDelete.risk)
        assertTrue(memoryDelete.argumentGuide.contains("memory_scope"))
        assertTrue(memoryDelete.argumentGuide.contains("expected_revision"))
        val memoryReviewList = requireNotNull(
            OwnerActionRegistry.action(OwnerToolFamily.MEMORY, "memory_review_list"),
        )
        assertEquals(OwnerOperationRisk.READ_ONLY, memoryReviewList.risk)
        assertEquals("assistant_id?, limit?", memoryReviewList.argumentGuide)
        val dreamStatus = requireNotNull(
            OwnerActionRegistry.action(OwnerToolFamily.MEMORY, "dream_status"),
        )
        val dreamExplain = requireNotNull(
            OwnerActionRegistry.action(OwnerToolFamily.MEMORY, "dream_claim_explain"),
        )
        assertEquals(OwnerOperationRisk.READ_ONLY, dreamStatus.risk)
        assertEquals(OwnerOperationRisk.READ_ONLY, dreamExplain.risk)
        assertTrue(dreamExplain.argumentGuide.contains("dream_scope"))
        assertTrue(dreamExplain.argumentGuide.contains("expected_revision"))
        assertTrue(ownerActionGuideCoverageGaps().isEmpty())
    }

    @Test
    fun `direct owner schemas stay inside the fixed token-cost byte budget`() {
        val sizes = ownerToolSchemaUtf8Bytes()
        assertTrue(sizes.entries.joinToString { "${it.key}=${it.value}" }, sizes.values.all { it <= 12 * 1024 })
        assertTrue("total=${sizes.values.sum()}", sizes.values.sum() <= 64 * 1024)
    }

    @Test
    fun `relation review payload exposes only wire scope and redacted review fields`() {
        val rawScope = "11111111-1111-1111-1111-111111111111"
        val payload = ownerMemoryRelationReviewsPayload(
            scope = OwnerMemoryScopeBinding.effective(rawScope, useGlobalMemory = false),
            records = listOf(
                MemoryRelationReviewRecord(
                    relationCandidateId = "relation-1",
                    relationType = "RELATED_TO",
                    description = "bounded derived description",
                    source = MemoryRelationReviewEndpoint(7, null, 2),
                    target = MemoryRelationReviewEndpoint(null, "candidate-2", 3),
                    evidenceCount = 2,
                    status = "PENDING",
                    createdAtMs = 123L,
                ),
            ),
        )

        assertEquals("assistant", payload.getValue("memory_scope").jsonPrimitive.content)
        val item = payload.getValue("items").jsonArray.single().jsonObject
        assertEquals("relation-1", item.getValue("relation_candidate_id").jsonPrimitive.content)
        assertEquals(2, item.getValue("evidence_count").jsonPrimitive.content.toInt())
        assertFalse(payload.toString().contains(rawScope))
        assertFalse(payload.containsKey("query"))
        assertFalse(payload.toString().contains("excerpt"))
        assertFalse(payload.toString().contains("evidence_message"))
    }

    @Test
    fun `dream status is bounded and omits repository and source identities`() {
        val rawScope = "11111111-1111-1111-1111-111111111111"
        val scopeId = DreamScopeId.requireCanonical(rawScope)
        val fence = DreamReviewFence(scopeId, 8, 7, 4, "22222222-2222-2222-2222-222222222222")
        val claims = (1..25).map { ordinal ->
            val claimId = "00000000-0000-0000-0000-${ordinal.toString().padStart(12, '0')}"
            DreamClaimSummary(
                claimId = claimId,
                revision = ordinal.toLong(),
                section = DreamSnapshotSection.PROFILE,
                state = DreamClaimState.ACTIVE_CONTEXTUAL,
                title = if (ordinal == 1) "t".repeat(300) else "Claim $ordinal",
                statement = "private derived statement $ordinal",
                confidencePermille = 900,
                temporalState = TemporalState.CURRENT,
                validFromEpochMs = 100,
                validToEpochMs = null,
                evidenceCount = 1,
                originAssistantId = rawScope,
            )
        }
        val projection = DreamReviewProjection(
            fence = fence,
            derivedStatus = DreamDerivedStatus.DIRTY,
            usageMode = DreamUsageMode.SHADOW,
            claims = claims,
            activeSnapshot = DreamSnapshotSummary(
                snapshotId = fence.expectedActiveSnapshotId!!,
                sourceMemoryEpoch = 7,
                committedDreamRevision = 4,
                payloadHash = DreamSha256("a".repeat(64)),
                compilerRevision = "compiler-v1",
                claimCount = claims.size,
                estimatedTokens = 123,
                createdAtEpochMs = 456,
            ),
            supersededSnapshot = null,
            snapshotDiff = DreamSnapshotDiffResult.Unavailable(DreamSnapshotDiffFailure.PAYLOAD_HASH_MISMATCH),
            recentRuns = listOf(
                DreamRunUsageSummary(
                    runId = "33333333-3333-3333-3333-333333333333",
                    inputTokens = 10,
                    outputTokens = null,
                    startedAtEpochMs = 1,
                    finishedAtEpochMs = 2,
                    statusCode = "SUCCEEDED",
                ),
            ),
        )

        val payload = ownerDreamStatusPayload(
            OwnerMemoryScopeBinding.effective(rawScope, useGlobalMemory = false),
            projection,
            requestedLimit = 999,
        )

        assertEquals("assistant", payload.getValue("dream_scope").jsonPrimitive.content)
        assertEquals(20, payload.getValue("claims").jsonArray.size)
        assertTrue(payload.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertFalse(
            payload.getValue("flags").jsonObject
                .getValue("snapshot_usable").jsonPrimitive.content.toBoolean(),
        )
        val tokenUsage = payload.getValue("token_usage").jsonObject
        assertEquals(10L, tokenUsage.getValue("measured_input_tokens").jsonPrimitive.content.toLong())
        assertFalse(tokenUsage.containsKey("measured_output_tokens"))
        val raw = payload.toString()
        assertFalse(raw.contains(rawScope))
        assertFalse(raw.contains("private derived statement"))
        assertFalse(raw.contains("33333333-3333-3333-3333-333333333333"))
        assertFalse(raw.contains(fence.expectedActiveSnapshotId!!))
        assertFalse(raw.contains("a".repeat(64)))
        assertFalse(raw.contains("evidence"))
    }

    @Test
    fun `dream explain exposes derived metadata but never evidence identity or excerpt`() {
        val rawScope = "11111111-1111-1111-1111-111111111111"
        val scopeId = DreamScopeId.requireCanonical(rawScope)
        val claimId = "44444444-4444-4444-4444-444444444444"
        val fence = DreamReviewFence(scopeId, 8, 8, 5, "55555555-5555-5555-5555-555555555555")
        val target = DreamClaimMutationTarget(fence, claimId, 3)
        val secretMemoryId = "source-memory-secret"
        val secretHash = "b".repeat(64)
        val detail = DreamClaimDetail(
            target = target,
            summary = DreamClaimSummary(
                claimId = claimId,
                revision = 3,
                section = DreamSnapshotSection.ACTIVE_PLANS,
                state = DreamClaimState.ACTIVE_CONTEXTUAL,
                title = "Derived plan",
                statement = "s".repeat(2_000),
                confidencePermille = 800,
                temporalState = TemporalState.UPCOMING,
                validFromEpochMs = 100,
                validToEpochMs = 200,
                evidenceCount = 1,
                originAssistantId = rawScope,
            ),
            storageClass = DreamStorageClass.EPISODIC,
            epistemicType = DreamEpistemicType.PLAN,
            versions = emptyList(),
            evidence = listOf(
                DreamEvidenceSummary(
                    reference = DreamEvidenceReference(
                        scopeId = scopeId,
                        claimId = claimId,
                        claimRevision = 3,
                        memoryId = secretMemoryId,
                        memoryRevision = 9,
                        expectedSemanticHash = DreamSha256(secretHash),
                        expectedSourceManifestHash = DreamSha256("c".repeat(64)),
                        supportType = DreamSupportType.SUPPORTS,
                    ),
                    validity = DreamEvidenceValidity.VALID,
                ),
            ),
        )

        val payload = ownerDreamClaimExplainPayload(
            OwnerMemoryScopeBinding.effective(rawScope, useGlobalMemory = false),
            detail,
        )

        assertEquals("assistant", payload.getValue("dream_scope").jsonPrimitive.content)
        assertEquals(1_600, payload.getValue("statement").jsonPrimitive.content.length)
        assertTrue(payload.getValue("truncated").jsonPrimitive.content.toBoolean())
        val source = payload.getValue("sources").jsonArray.single().jsonObject
        assertEquals(setOf("validity", "support_type", "excerpt_available"), source.keys)
        assertTrue(source.getValue("excerpt_available").jsonPrimitive.content.toBoolean())
        val raw = payload.toString()
        assertFalse(raw.contains(rawScope))
        assertFalse(raw.contains(secretMemoryId))
        assertFalse(raw.contains(secretHash))
        assertFalse(raw.contains("memory_revision"))
        assertFalse(raw.contains("\"excerpt\":"))
        assertFalse(raw.contains("message"))
    }
}
