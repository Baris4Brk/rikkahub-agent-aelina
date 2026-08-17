package me.rerere.rikkahub.learning.eval

/**
 * P5-006 candidate baseline identity. Its checked-in counters remain useful for deterministic
 * regression, but are not reviewed measurement evidence until independent matched-environment
 * run artifacts exist. The manifest digest prevents accidental reuse across implementation drift.
 */
object FrozenProductionEvalBaselineV1 {
    const val BASELINE_ID: String = "p5-production-components-baseline-v1"
    const val ARTIFACT_SCHEMA_VERSION: Int = 1

    val expectedCounters: PerformanceCounterSnapshot = PerformanceCounterSnapshot(
        deterministicOperationUnits = 9_480L,
        logicalAllocationUnits = 2_280L,
    )

    /** Target environment for future independent reviewed measurements. */
    val reviewedEnvironment: ProductionEvalRuntimeEnvironment =
        ProductionEvalRuntimeEnvironment(
            osFamily = "linux",
            architecture = "x86_64",
            javaVendorFamily = "eclipse-adoptium",
            javaMajorVersion = "17",
            javaVmFamily = "openjdk-64-server",
            ciProfile = "gha-ubuntu-24.04-x64-temurin17-v1",
            gradleVersion = "9.4.1",
            androidGradlePluginVersion = "9.2.1",
            kotlinVersion = "2.4.0",
            jvmTarget = "17",
            compileSdk = "37",
            gateTask = "app-p5-production-evaluation-gate",
            frozenMatchRequired = true,
        )

    val manifest: FrozenProductionEvalManifest by lazy {
        FrozenProductionEvalManifest.freeze(FrozenProductionComponentReplayV1.adapters)
    }

    val baseline: ProductionEvalPerformanceBaseline by lazy {
        check(manifest.performanceThresholds.status == PerformanceThresholdStatus.DRAFT)
        check(manifest.performanceThresholds.baselineId == BASELINE_ID)
        ProductionEvalPerformanceBaseline(
            manifestDigestSha256 = manifest.digestSha256,
            baseline = PerformanceBaseline(
                baselineId = BASELINE_ID,
                environmentDigestSha256 = reviewedEnvironment.digestSha256,
                corpusDigestSha256 = FrozenReplayCorpusV1.digestSha256,
                counters = expectedCounters,
            ),
        )
    }

    /** Keep the deterministic fixture stable without calling it an independent measurement. */
    fun requireExactCandidateRun(run: ProductionComponentReplayRun) {
        check(run.abstainedComponentCount == 0) { "Production component replay abstained" }
        check(run.runnerPerformance == expectedCounters) {
            "Candidate production replay counters changed; explicit baseline review required"
        }
        check(run.report.corpusDigestSha256 == FrozenReplayCorpusV1.digestSha256)
        check(run.report.energy == EnergyAssessment.offlineJvm())
    }
}
