package me.rerere.rikkahub.learning.eval

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P5RoomIntegrationArchitectureContractTest {
    @Test
    fun `disposable evaluator exercises production double Room path and no pure state machine`() {
        val root = repositoryRoot()
        val source = Files.readString(
            root.resolve(
                "app/src/androidTest/java/me/rerere/rikkahub/learning/eval/" +
                    "P5ProductionRoomIntegrationEvaluationTest.kt",
            ),
            StandardCharsets.UTF_8,
        )

        listOf(
            "AppDatabase::class.java",
            "LearningDatabase::class.java",
            "createAppSQLiteOpenHelperFactory(context)",
            "LearningRuntimeFacade(",
            "RoomPolicyGrantService(primary)",
            "AppFirstPolicyGrantReviewCoordinator(",
            "PolicyFtsManager(seedDatabase).searchEligible(",
            "compileRecallPrompt(",
            "facade.reserve(",
            "facade.reserveMatched(",
            "PolicyExposureOutcomeLinker(linkedDatabase)",
            "ProductionObservedUtilityRuntime(ledger, ledger)",
            "ProductionLearningEvaluationCiEntry.evaluate(",
            "roomIntegration = roomAttestation",
            "fourArmRuntime = fourArmAttestation",
            "journal.commitPreRegistration(preRegistration)",
            "journal.persistCloseAndReopen(captured, preRegistration)",
            "captureCheckedInFixture(",
            "ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(",
            "PlatformTestStorageRegistry.getInstance()",
            "openOutputFile(REDACTED_ARTIFACT_FILE)",
        ).forEach { marker ->
            assertTrue("missing production integration marker: $marker", source.contains(marker))
        }
        assertFalse(source.contains("PolicyExposureStateMachine"))
        assertFalse(source.contains("FrozenFixtureReplayExecutor"))
        assertFalse(source.contains("ProductionFourArmRuntimeAttestationFactory.passed"))
        assertFalse(source.contains("connectedAndroidTest" + "("))
    }

    @Test
    fun `AGP 9 test storage service and managed-device host export are statically wired`() {
        val root = repositoryRoot()
        val build = Files.readString(root.resolve("app/build.gradle.kts"), StandardCharsets.UTF_8)
        val androidTest = Files.readString(
            root.resolve(
                "app/src/androidTest/java/me/rerere/rikkahub/learning/eval/" +
                    "P5ProductionRoomIntegrationEvaluationTest.kt",
            ),
            StandardCharsets.UTF_8,
        )

        assertTrue(build.contains("testInstrumentationRunnerArguments[\"useTestStorageService\"] = \"true\""))
        assertTrue(build.contains("androidTestUtil(\"androidx.test.services:test-services:1.6.0\")"))
        assertTrue(build.contains("create(\"p5DisposablePixel6Api35\")"))
        assertTrue(androidTest.contains("androidx.test.platform.io.PlatformTestStorageRegistry"))
        assertTrue(androidTest.contains("openOutputFile(REDACTED_ARTIFACT_FILE)"))
        assertFalse(androidTest.contains("File(context.filesDir, REDACTED_ARTIFACT_FILE)"))
    }

    @Test
    fun `production facade and emulator share exact jieba FTS initializer`() {
        val root = repositoryRoot()
        val facade = Files.readString(
            root.resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/runtime/" +
                    "LearningRuntimeFacade.kt",
            ),
            StandardCharsets.UTF_8,
        )
        val fts = Files.readString(
            root.resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/retrieval/PolicyFtsManager.kt",
            ),
            StandardCharsets.UTF_8,
        )

        assertTrue(facade.contains("initializePolicyFtsRuntime("))
        assertTrue(fts.contains("SimpleDictManager.extractDict(context.applicationContext)"))
        assertTrue(fts.contains("SELECT jieba_dict(?)"))
        assertTrue(fts.contains("ensurePolicyFtsSchema(db)"))
    }

    @Test
    fun `durable pass has one verifier path and requires independent authority origin`() {
        val root = repositoryRoot()
        val evalRoot = root.resolve(
            "app/src/main/java/me/rerere/rikkahub/learning/eval",
        )
        val gate = Files.readString(
            evalRoot.resolve("ProductionFourArmRuntimeGate.kt"),
            StandardCharsets.UTF_8,
        )
        val verifier = Files.readString(
            evalRoot.resolve("DurableFourArmRuntimeEvidence.kt"),
            StandardCharsets.UTF_8,
        )
        val authority = Files.readString(
            evalRoot.resolve("FrozenArmBlindAuthorityTraceV1.kt"),
            StandardCharsets.UTF_8,
        )
        val adapters = Files.readString(
            evalRoot.resolve("FrozenProductionComponentReplayV1.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(gate.contains("internal fun passed("))
        assertEquals(1, verifier.split(
            "ProductionFourArmRuntimeAttestationFactory.passed(",
        ).size - 1)
        assertTrue(verifier.contains("DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE"))
        assertTrue(verifier.contains("captureIndependentRuntime("))
        assertTrue(verifier.contains("captureCheckedInFixture("))
        assertTrue(verifier.contains("CHECKED_IN_REGRESSION_FIXTURE_ONLY"))
        assertFalse(authority.contains("ReplayFixtureScenario"))
        assertFalse(adapters.contains("unit.scenario"))
        assertFalse(adapters.contains("outcomeByArm"))
    }

    private fun repositoryRoot() = generateSequence(
        Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }.take(7).first { Files.isDirectory(it.resolve("app/src/main/java")) }
}
