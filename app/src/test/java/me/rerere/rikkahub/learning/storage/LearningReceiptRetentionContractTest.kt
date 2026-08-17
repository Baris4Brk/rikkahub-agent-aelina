package me.rerere.rikkahub.learning.storage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningReceiptRetentionContractTest {
    @Test
    fun providerCohortCleanupIsBoundedAndOnlyUnreferenced() {
        val source = file("LearningProviderExecutionDao.kt")
        val sql = queryBefore(source, "suspend fun deleteUnreferencedConfigCohortsPage")
        assertTrue("NOT EXISTS" in sql)
        assertTrue("learning_provider_job_manifests" in sql)
        assertTrue("LIMIT :limit" in sql)
    }

    @Test
    fun rewardSignalsReferencedByPolicyCapsuleCannotExpire() {
        val source = file("LearningRewardSignalDao.kt")
        val sql = queryBefore(source, "suspend fun deleteExpiredUnreferencedSignalsPage")
        assertTrue("created_at_ms < :createdBeforeMs" in sql)
        assertTrue("NOT EXISTS" in sql)
        assertTrue("policy_reward_evidence" in sql)
        assertTrue("LIMIT :limit" in sql)
    }

    @Test
    fun ambiguousDispatchedExposureIsNeverSelectedForExpiry() {
        val source = file("LearningPolicyExposureDao.kt")
        val sql = queryBefore(source, "suspend fun deleteExpiredSettledPage")
        assertTrue("host_dispatched_at_ms IS NULL" in sql)
        assertTrue("terminal_at_ms IS NOT NULL" in sql)
        assertTrue("outcome_linked_at_ms IS NOT NULL" in sql)
        assertTrue("LIMIT :limit" in sql)
    }

    @Test
    fun providerBudgetsPartitionCallsTokensAndCostByLocalOrRemoteKind() {
        val source = file("LearningProviderExecutionDao.kt")
        val sql = queryBefore(source, "suspend fun readReservedBudgetForProviderKind")
        assertTrue("JOIN learning_provider_job_manifests" in sql)
        assertTrue("JOIN learning_provider_config_cohorts" in sql)
        assertTrue("c.provider_kind = :providerKind" in sql)
        listOf(
            "actual_provider_calls",
            "actual_input_tokens",
            "actual_output_tokens",
            "actual_cost_micros",
        ).forEach { dimension -> assertTrue(dimension in sql) }
    }

    private fun file(name: String): String = File(
        "src/main/java/me/rerere/rikkahub/learning/storage/$name",
    ).readText()

    private fun queryBefore(source: String, signature: String): String =
        source.substringBefore(signature).substringAfterLast("@Query(")
}
