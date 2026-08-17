package me.rerere.rikkahub.learning.policy.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source composition contract; Android Room/Koin are not booted by the plain JVM suite. */
class PolicyRuntimeProductionCompositionTest {
    @Test
    fun `terminal caller and final dispatch surface reach production governors`() {
        val root = locateProjectRoot()
        val di = read(root, "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt")
        val linker = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/exposure/" +
                "PolicyExposureOutcomeLinker.kt",
        )
        val p1 = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/jobs/" +
                "P1ProductionRuntimeDependencies.kt",
        )
        val facade = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
        )
        val facadePolicySupport = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/runtime/" +
                "LearningRuntimePolicyPresentation.kt",
        )
        val generation = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
        )
        val production = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/policy/runtime/" +
                "ProductionPolicyOutcomeSafety.kt",
        )

        listOf(
            "single<me.rerere.rikkahub.learning.policy.runtime.PolicyOutcomeLinkedObserverFactory>",
            "ProductionPolicyOutcomeLinkedObserverFactory",
            "policyOutcomeObserverFactory = get()",
        ).forEach { assertTrue("Missing P2 production DI edge: $it", it in di) }
        listOf(
            "outcomeObserver.onLinked(",
            "PolicyOutcomeSafetyTrigger(",
            "receipt.hasObserved(PolicyExposureState.OUTCOME_LINKED)",
        ).forEach { assertTrue("Outcome caller is not exact: $it", it in linker) }
        assertTrue("P1 must inject the database-scoped observer",
            "policyOutcomeObserverFactory.create(database)" in p1)
        listOf(
            "RoomPolicyOutcomeSafetyMaterialSource(database)",
            "RoomPolicyLifecycleMutationStore(database)",
            "SINGLE_POLICY_COMPLETED_RESPONSE_AUTHORITATIVE_FAILURE",
            "Advisory harm review has no durable production queue",
        ).forEach { assertTrue("Safety production chain is incomplete: $it", it in production) }
        listOf(
            "override suspend fun observeFinalDispatchSurface(",
            "PolicyExactDispatchSchemaObserver(",
            "expectedCapabilityDigest = applicableCapabilityDigest",
            "RoomPolicyLifecycleMutationStore(opened)",
        ).forEach { required ->
            assertTrue(
                "Schema observer is not production composed: $required",
                required in facade || required in facadePolicySupport,
            )
        }
        listOf(
            "observeFinalDispatchSurface(",
            "availableToolSchemaFingerprints = finalToolSchemas",
            "PolicyDispatchSurfaceObservationResult.Unavailable",
            "policy.policyId in dispatchSurfaceEligibleIds",
        ).forEach { assertTrue("Final tool surface caller is missing: $it", it in generation) }

        listOf(
            "ObservedUtilityMatchedAssignmentIntentPort",
            "observedUtilityAssignments = get()",
        ).forEach { assertTrue("Observed utility DI edge is missing: $it", it in di) }
        listOf(
            "RoomObservedUtilityLedger(",
            "ProductionObservedUtilityRuntime(",
            "ObservedUtilityMaintenanceCoordinator(",
            "override suspend fun reserveMatched(",
        ).forEach { assertTrue("Observed utility production chain is incomplete: $it", it in facade) }
        assertTrue(
            "Terminal authority must project both exposure and non-exposure assignments",
            "ObservedUtilityTerminalAuthorityProjector(database)" in linker,
        )
        assertFalse("Advisory queue must not be advertised as durable",
            "PolicySafetyAdvisoryRuntime(" in di)
    }

    private fun read(root: Path, relative: String): String =
        Files.readString(root.resolve(relative), StandardCharsets.UTF_8)

    private fun locateProjectRoot(): Path {
        var cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("app/src/main/java"))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate project root")
    }
}
