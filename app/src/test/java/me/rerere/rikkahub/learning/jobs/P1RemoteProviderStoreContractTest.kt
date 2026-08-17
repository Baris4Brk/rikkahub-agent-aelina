package me.rerere.rikkahub.learning.jobs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1RemoteProviderStoreContractTest {
    @Test
    fun remoteUsesTheSharedManifestAttemptAdmissionInsteadOfAnImmediateDeadLetterLane() {
        val source = File(
            "src/main/java/me/rerere/rikkahub/learning/jobs/RoomLearningJobStore.kt",
        ).readText()

        assertTrue("providerKind != PROVIDER_KIND_LOCAL && providerKind != PROVIDER_KIND_REMOTE" in source)
        assertTrue("ProviderAdmission.Accepted(manifest, cohort" in source)
        assertTrue("readReservedBudgetForProviderKind" in source)
        assertFalse("data object Remote : ProviderAdmission" in source)
    }

    @Test
    fun remoteCostAuthorizationIsNonZeroButActualCostRemainsProviderReported() {
        assertTrue(REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS > 0L)
        val authority = File(
            "src/main/java/me/rerere/rikkahub/learning/jobs/RoomLearningProviderAttemptAuthority.kt",
        ).readText()
        assertTrue("actualCostMicros = usage.costMicros" in authority)
    }
}
