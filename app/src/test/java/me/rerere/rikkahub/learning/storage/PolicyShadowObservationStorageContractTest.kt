package me.rerere.rikkahub.learning.storage

import java.io.File
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalDropReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyShadowObservationStorageContractTest {
    @Test
    fun `v8 owns content-free request and exact Policy item tables`() {
        val sql = LEARNING_V8_SCHEMA_SQL
            .filter { statement ->
                statement.startsWith(
                    "CREATE TABLE IF NOT EXISTS `learning_policy_shadow_observation",
                )
            }
            .joinToString("\n")
        listOf(
            "learning_policy_shadow_observations",
            "request_identity",
            "scope_kind",
            "scope_id",
            "task_signature",
            "gate_identity",
            "learning_policy_shadow_observation_items",
            "policy_state_version",
            "policy_content_revision",
            "artifact_sha256",
            "FOREIGN KEY(`policy_id`) REFERENCES `learning_policies`(`id`)",
        ).forEach { required -> assertTrue(required in sql) }
        listOf("query_text", "prompt", "response", "outcome", "provider_identity", "model_identity")
            .forEach { forbidden -> assertFalse(forbidden in sql) }
    }

    @Test
    fun `drop count wire is canonical bounded and replayable`() {
        val counts = mapOf(
            PolicyRetrievalDropReason.SOURCE_STALE to 2,
            PolicyRetrievalDropReason.CANDIDATE_LIMIT to 1,
        )
        val wire = encodePolicyShadowDropReasonCounts(counts)
        assertEquals("CANDIDATE_LIMIT:1,SOURCE_STALE:2", wire)
        assertEquals(counts, decodePolicyShadowDropReasonCounts(wire))
        assertEquals("NONE", encodePolicyShadowDropReasonCounts(emptyMap()))
    }

    @Test
    fun `production commit path is one Room transaction and review sources are separated`() {
        val working = File(System.getProperty("user.dir"))
        val root = if (working.resolve("app/src/main").isDirectory) {
            working.resolve("app")
        } else {
            working
        }
        val store = root.resolve(
            "src/main/java/me/rerere/rikkahub/learning/retrieval/" +
                "RoomPolicyShadowObservationStore.kt",
        ).readText()
        assertTrue("database.withTransaction" in store)
        assertTrue("mutateInOpenTransaction" in store)
        assertTrue("PolicyShadowCommitRollback" in store)
        assertTrue("insertObservationIgnore" in store)
        assertTrue("insertItems" in store)

        val facade = root.resolve(
            "src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
        ).readText()
        assertTrue("policyShadowObservationDao().aggregateForPolicyReview" in facade)
        assertTrue("policyExposureDao().aggregateForPolicyReview" in facade)
        assertFalse("shadowRecallCount = exposure." in facade)
    }
}
