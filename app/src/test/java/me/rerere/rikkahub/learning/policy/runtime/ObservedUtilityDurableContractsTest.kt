package me.rerere.rikkahub.learning.policy.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.ObservedUtilityAssignmentMethod
import me.rerere.rikkahub.learning.policy.ObservedUtilityAttributionUnit
import me.rerere.rikkahub.learning.policy.ObservedUtilityCohortIdentity
import me.rerere.rikkahub.learning.policy.ObservedUtilityDesign
import me.rerere.rikkahub.learning.policy.ObservedUtilitySelectionMethod
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.storage.LEARNING_V9_SCHEMA_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedUtilityDurableContractsTest {
    @Test
    fun `source unavailable is append-only ABSTAIN with explicit unknown watermark`() = runBlocking {
        val receipts = mutableListOf<ObservedUtilityEvaluationReceipt>()
        val runtime = ProductionObservedUtilityRuntime(
            source = object : DurableObservedUtilitySource {
                override suspend fun loadExact(request: ObservedUtilityRuntimeRequest) =
                    DurableObservedUtilityBatchResult.Unavailable

                override suspend fun revalidatePolicyFence(fence: PolicyMutationFence) = true
            },
            store = ObservedUtilityEvaluationStore {
                receipts += it
                ObservedUtilityPersistenceDisposition.APPLIED
            },
        )

        val result = runtime.evaluate(request(), 5_000L)

        assertTrue(result is ObservedUtilityRuntimeResult.Abstained)
        val abstained = result as ObservedUtilityRuntimeResult.Abstained
        assertEquals(ObservedUtilityRuntimeAbstainReason.SOURCE_UNAVAILABLE, abstained.reason)
        assertEquals(ObservedUtilityPersistenceDisposition.APPLIED, abstained.persistence)
        assertEquals(1, receipts.size)
        assertEquals(ObservedUtilityRuntimeStatus.ABSTAINED, receipts.single().status)
        assertEquals(
            ObservedUtilitySourceWatermarkStatus.UNKNOWN,
            receipts.single().sourceWatermarkStatus,
        )
        assertEquals("SOURCE_UNAVAILABLE", receipts.single().resultCode)
        assertNull(receipts.single().observedUtilityDelta)
        assertNull(receipts.single().scalarProjectionPolicyId)
        assertEquals(receipts.single().canonicalDigest(), receipts.single().receiptDigest)
    }

    @Test
    fun `incomplete bounded page persists ABSTAIN rather than disappearing`() = runBlocking {
        val request = request()
        val receipts = mutableListOf<ObservedUtilityEvaluationReceipt>()
        val runtime = ProductionObservedUtilityRuntime(
            source = object : DurableObservedUtilitySource {
                override suspend fun loadExact(request: ObservedUtilityRuntimeRequest) =
                    DurableObservedUtilityBatchResult.Ready(
                        DurableObservedUtilityBatch(
                            rows = emptyList(),
                            sourceWatermarkDigest = digest("bounded-watermark"),
                            complete = false,
                        ),
                    )

                override suspend fun revalidatePolicyFence(fence: PolicyMutationFence) = true
            },
            store = ObservedUtilityEvaluationStore {
                receipts += it
                ObservedUtilityPersistenceDisposition.APPLIED
            },
        )

        val result = runtime.evaluate(request, 5_000L) as ObservedUtilityRuntimeResult.Abstained

        assertEquals(ObservedUtilityRuntimeAbstainReason.SOURCE_WINDOW_INCOMPLETE, result.reason)
        assertEquals(ObservedUtilitySourceWatermarkStatus.KNOWN, result.receipt.sourceWatermarkStatus)
        assertEquals("SOURCE_WINDOW_INCOMPLETE", result.receipt.resultCode)
        assertEquals(1, receipts.size)
    }

    @Test
    fun `pre-treatment assignment identity freezes design cohort fence and clocks`() {
        val assignment = assignment()
        assertEquals(assignment.assignmentId, assignment.copy().assignmentId)
        assertEquals(assignment.designDigest, observedUtilityDesignDigest(assignment.design))
        assertEquals(assignment.cohortDigest, observedUtilityCohortDigest(assignment.cohort))
        assertTrue(assignment.assignmentId.startsWith("observed-utility-assignment-v1:"))
    }

    @Test
    fun `bundle design never projects one policy scalar`() = runBlocking {
        val request = request(
            design = design(
                attributionUnit = ObservedUtilityAttributionUnit.BUNDLE,
                targetPolicyId = null,
            ),
        )
        val receipts = mutableListOf<ObservedUtilityEvaluationReceipt>()
        val runtime = ProductionObservedUtilityRuntime(
            source = object : DurableObservedUtilitySource {
                override suspend fun loadExact(request: ObservedUtilityRuntimeRequest) =
                    DurableObservedUtilityBatchResult.Ready(
                        DurableObservedUtilityBatch(
                            rows = emptyList(),
                            sourceWatermarkDigest = digest("empty-window"),
                            complete = true,
                        ),
                    )

                override suspend fun revalidatePolicyFence(fence: PolicyMutationFence) = true
            },
            store = ObservedUtilityEvaluationStore {
                receipts += it
                ObservedUtilityPersistenceDisposition.APPLIED
            },
        )

        runtime.evaluate(request, 5_000L)

        assertEquals(1, receipts.size)
        assertEquals(ObservedUtilityRuntimeStatus.ABSTAINED, receipts.single().status)
        assertNull(receipts.single().scalarProjectionPolicyId)
    }

    @Test
    fun `maintenance processes one bounded resumable page`() = runBlocking {
        val requests = listOf(
            request(windowEnd = 2_000L),
            request(windowEnd = 3_000L),
        )
        val evaluated = mutableListOf<Long>()
        val runtime = ProductionObservedUtilityRuntime(
            source = object : DurableObservedUtilitySource {
                override suspend fun loadExact(request: ObservedUtilityRuntimeRequest):
                    DurableObservedUtilityBatchResult {
                    evaluated += request.sourceWindowEndMs
                    return DurableObservedUtilityBatchResult.Unavailable
                }

                override suspend fun revalidatePolicyFence(fence: PolicyMutationFence) = true
            },
            store = ObservedUtilityEvaluationStore {
                ObservedUtilityPersistenceDisposition.APPLIED
            },
        )
        val coordinator = ObservedUtilityMaintenanceCoordinator(
            candidates = ObservedUtilityMaintenanceCandidateSource { _, _, limit ->
                assertEquals(2, limit)
                ObservedUtilityMaintenancePage(
                    candidates = requests.mapIndexed { index, request ->
                        ObservedUtilityMaintenanceCandidate(
                            request = request,
                            representativeAssignmentId = "assignment-${index + 1}",
                        )
                    },
                    hasMore = true,
                )
            },
            runtime = runtime,
        )

        val result = coordinator.runPage(
            after = ObservedUtilityMaintenanceCursor.START,
            frozenNowMs = 4_000L,
            limit = 2,
        ) as ObservedUtilityMaintenancePageResult.Processed

        assertEquals(listOf(2_000L, 3_000L), evaluated)
        assertFalse(result.complete)
        assertEquals(2, result.abstainedCount)
        assertEquals(3_000L, result.nextCursor?.sourceWindowEndMs)
    }

    @Test
    fun `schema API encodes append-only bounded exact transaction contract`() {
        val root = locateProjectRoot()
        val entity = read(root, "app/src/main/java/me/rerere/rikkahub/learning/storage/" +
            "LearningObservedUtilityEntity.kt")
        val dao = read(root, "app/src/main/java/me/rerere/rikkahub/learning/storage/" +
            "LearningObservedUtilityDao.kt")
        val adapter = read(root, "app/src/main/java/me/rerere/rikkahub/learning/policy/runtime/" +
            "RoomObservedUtilityLedger.kt")
        val migrations = read(root, "app/src/main/java/me/rerere/rikkahub/learning/storage/" +
            "LearningDatabaseMigrations.kt")

        listOf(
            "learning_observed_utility_assignments",
            "learning_observed_utility_outcomes",
            "learning_observed_utility_evaluation_receipts",
            "pre_registered_design_digest",
            "producer_configuration_generation",
            "confidence_lower",
            "causal_interpretation",
            "scalar_projection_policy_id",
        ).forEach { assertTrue("Missing durable observed-utility field: $it", it in entity) }
        assertTrue("@Insert(onConflict = OnConflictStrategy.ABORT)" in dao)
        assertFalse("OnConflictStrategy.REPLACE" in dao)
        assertTrue("ORDER BY assigned_at_ms ASC, id ASC LIMIT :limit" in dao)
        assertTrue("AND r.source_window_end_ms = a.source_window_end_ms)" in dao)
        assertFalse("r.result_code != 'SOURCE_UNAVAILABLE'" in dao)
        assertFalse("JOIN learning_policies p ON p.id = a.target_policy_id" in dao)
        assertTrue("database.withTransaction" in adapter)
        assertTrue("updateObservedUtilityProjectionIfExact" in adapter)
        assertTrue("scalarProjectionPolicyId != null" in adapter)
        assertTrue("internal val LEARNING_V9_SCHEMA_SQL" in migrations)
        assertTrue("Migration(8, 9)" in migrations)
        assertEquals(10, LEARNING_V9_SCHEMA_SQL.size)
        assertEquals(
            setOf(
                "learning_observed_utility_assignments",
                "learning_observed_utility_outcomes",
                "learning_observed_utility_evaluation_receipts",
            ),
            LEARNING_V9_SCHEMA_SQL.mapNotNull { sql ->
                Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`")
                    .find(sql)?.groupValues?.get(1)
            }.toSet(),
        )
    }

    private fun request(
        design: ObservedUtilityDesign = design(),
        windowEnd: Long = 2_000L,
    ) = ObservedUtilityRuntimeRequest(
        fence = fence(),
        design = design,
        expectedCohortDigest = observedUtilityCohortDigest(cohort()),
        sourceWindowStartMs = 1_000L,
        sourceWindowEndMs = windowEnd,
    )

    private fun assignment() = ObservedUtilityPreTreatmentAssignment(
        streamId = Uuid.parse("11111111-1111-1111-1111-111111111111"),
        replayGeneration = 7L,
        episodeId = requireNotNull(
            EpisodeId.parseOrNull("episode-v1:${digest("episode")}"),
        ),
        logicalRunId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
        attemptOrdinal = 1,
        fence = fence(),
        design = design(),
        cohort = cohort(),
        arm = ObservedUtilityArm.EXPOSED,
        matchKeyDigest = digest("match"),
        propensity = null,
        expectedExposureId = "policy-exposure-v1:${digest("exposure")}",
        sourceWindowStartMs = 1_000L,
        sourceWindowEndMs = 2_000L,
        eligibilityDeterminedAtMs = 1_050L,
        assignedAtMs = 1_100L,
    )

    private fun fence() = PolicyMutationFence(
        policyId = "policy-1",
        scope = LearningScope.Assistant(
            Uuid.parse("33333333-3333-3333-3333-333333333333"),
        ),
        expectedRevision = 4L,
        expectedContentRevision = 2L,
        expectedArtifactHash = digest("artifact"),
    )

    private fun design(
        attributionUnit: ObservedUtilityAttributionUnit =
            ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY,
        targetPolicyId: String? = "policy-1",
    ) = ObservedUtilityDesign(
        targetPolicySetDigest = digest("set"),
        assignmentMethod = ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE,
        selectionMethod = ObservedUtilitySelectionMethod.EXACT_MATCHED_COHORT,
        preRegisteredDesignDigest = null,
        exposureRecordingReliable = true,
        exposureContractVersion = 1,
        eligibilityDeterminedBeforeTreatment = true,
        assignmentBeforeCompileOrInjection = true,
        fixedOutcomeWindow = true,
        randomizedAssignment = false,
        factorialIsolation = false,
        attributionUnit = attributionUnit,
        targetPolicyId = targetPolicyId,
    )

    private fun cohort() = ObservedUtilityCohortIdentity(
        taskSignature = "task.v1",
        taskSignatureVersion = 1,
        modelIdentity = "model.v1",
        modelVersion = "model-version.v1",
        providerIdentity = "provider.v1",
        providerVersion = "provider-version.v1",
        toolsetFingerprint = digest("tools"),
        toolSchemaVersion = "schema.v1",
        producerModelIdentity = "producer-model.v1",
        producerProviderIdentity = "producer-provider.v1",
        producerConfigurationIdentity = "producer-config.v1",
        producerConfigurationGeneration = 7L,
        outcomeDefinitionVersion = "outcome.v1",
        outcomeWindowIdentity = "window.v1",
    )

    private fun digest(value: String) = LearningCanonicalId.digest(
        domainVersion = "observed-utility-durable-contract-test-v1",
        fields = listOf(value),
    )

    private fun read(root: Path, relative: String): String =
        Files.readString(root.resolve(relative), StandardCharsets.UTF_8)

    private fun locateProjectRoot(): Path {
        var cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(7) {
            if (Files.isDirectory(cursor.resolve("app/src/main/java"))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate project root")
    }
}
