package me.rerere.rikkahub.learning.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningDatabaseV3ContractTest {
    @Test
    fun schemaIsSplitIntoExactlyFiveV2AndFourV3Tables() {
        assertEquals(3, LEARNING_DATABASE_VERSION)
        assertEquals(1, LEARNING_MIGRATION_1_2.startVersion)
        assertEquals(2, LEARNING_MIGRATION_1_2.endVersion)
        assertEquals(2, LEARNING_MIGRATION_2_3.startVersion)
        assertEquals(3, LEARNING_MIGRATION_2_3.endVersion)
        val v2Sql = LEARNING_V2_SCHEMA_SQL.joinToString("\n")
        listOf(
            "algorithm_identity",
            "prompt_identity",
            "provider_kind_identity",
            "model_identity",
            "provider_identity",
            "provider_configuration_identity",
            "provider_config_generation",
            "source_schema_identity",
            "toolset_identity",
            "output_schema_identity",
        ).forEach { frozenColumn -> assertTrue(frozenColumn in v2Sql) }
        assertEquals(
            setOf(
                "learning_episodes",
                "learning_trace_features",
                "learning_episode_lessons",
                "learning_reward_windows",
                "learning_source_validity",
            ),
            createdTables(LEARNING_V2_SCHEMA_SQL),
        )
        assertEquals(
            setOf(
                "learning_policies",
                "policy_evidence",
                "policy_revisions",
                "policy_lineage",
            ),
            createdTables(LEARNING_V3_SCHEMA_SQL),
        )
        assertTrue(
            LEARNING_V3_SCHEMA_SQL.any {
                "PRIMARY KEY(`policy_id`, `episode_id`)" in it
            },
        )
        assertTrue(
            LEARNING_V2_SCHEMA_SQL.any {
                "PRIMARY KEY(`stream_id`, `replay_generation`, `scope_kind`, `scope_id`, " +
                    "`source_type`, `source_id`, `source_revision`)" in it
            },
        )
    }

    @Test
    fun p1StorageHasNoRawConversationProviderOrToolPayloadEscapeHatch() {
        val forbidden = listOf(
            "raw",
            "prompttext",
            "conversationtext",
            "messagetext",
            "reasoning",
            "chainofthought",
            "toolargs",
            "tooloutput",
            "exception",
            "credential",
            "secret",
            "absoluteuri",
            "absolutepath",
        )
        val entities = listOf(
            LearningEpisodeEntity::class.java,
            LearningTraceFeatureEntity::class.java,
            LearningEpisodeLessonEntity::class.java,
            LearningRewardWindowEntity::class.java,
            LearningSourceValidityEntity::class.java,
            LearningPolicyEntity::class.java,
            PolicyEvidenceEntity::class.java,
            PolicyRevisionEntity::class.java,
            PolicyLineageEntity::class.java,
        )
        entities.forEach { type ->
            type.declaredFields.forEach { field ->
                val normalized = field.name.lowercase().replace("_", "")
                assertFalse(
                    "${type.simpleName}.${field.name} is a private-data escape hatch",
                    forbidden.any(normalized::contains),
                )
            }
        }
        val migrationText = (LEARNING_V2_SCHEMA_SQL + LEARNING_V3_SCHEMA_SQL)
            .joinToString("\n")
            .lowercase()
        listOf("payload_json", "raw_prompt", "tool_args", "tool_output", "exception_text")
            .forEach { assertFalse(it in migrationText) }
    }

    @Test
    fun p1PolicyCannotPretendShadowWasUsedOrMeasured() {
        val policy = policy()
        assertEquals(0L, policy.usageCount)
        assertNull(policy.lastUsedAtMs)
        assertNull(policy.observedUtilityDelta)
        assertNull(policy.utilityUncertainty)
        assertThrows(IllegalArgumentException::class.java) { policy.copy(usageCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(lastUsedAtMs = 10) }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(observedUtilityDelta = 0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(status = "ACTIVE")
        }
    }

    @Test
    fun summariesRejectCredentialRawPayloadPathAndUrlQueryMaterial() {
        listOf(
            "api_key=private-value",
            "Bearer abcdefghijklmnop",
            "C:\\Users\\person\\private.txt",
            "/home/person/private.txt",
            "https://example.test/path?token=private",
            "{\"raw\":\"payload\"}",
            "<tool-output>private</tool-output>",
        ).forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                policy().copy(triggerSummary = unsafe)
            }
        }
        assertTrue(policy().triggerSummary.isNotBlank())
    }

    @Test
    fun retentionThresholdsAreCentralizedAndClockFrozen() {
        val days = 24L * 60L * 60L * 1_000L
        val now = 500L * days
        val frozen = LearningRetentionPolicyV1 { now }.freeze()
        assertEquals(now - 7L * days, frozen.openEpisodeCutoffMs)
        assertEquals(now - 30L * days, frozen.traceCutoffMs)
        assertEquals(now - 90L * days, frozen.episodeAndRewardCutoffMs)
        assertEquals(now - 180L * days, frozen.lessonCutoffMs)
        assertEquals(now - 180L * days, frozen.dormantPolicyCutoffMs)
        assertEquals(now - 180L * days, frozen.invalidSourceCutoffMs)
    }

    @Test
    fun activeDistillationConservativelyPinsEveryRebuildInput() {
        assertTrue("DISTILL_POLICY_V1" in NO_ACTIVE_DISTILL_JOB_PREDICATE)
        listOf("PENDING", "RETRY", "RUNNING").forEach { state ->
            assertTrue(state in NO_ACTIVE_DISTILL_JOB_PREDICATE)
        }
    }

    private fun policy() = LearningPolicyEntity(
        id = "policy-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000002",
        taskSignature = "task-signature-v1",
        policyType = "PROCEDURE",
        triggerSummary = "需要执行已验证的任务时",
        procedureSummary = "先检查前置条件，再执行有界步骤。",
        verificationSummary = "核对结构化结果状态。",
        boundarySummary = "仅限当前助手范围。",
        failureModeSummary = "来源不确定时放弃使用。",
        stateVersion = 1,
        artifactSha256 = "a".repeat(64),
        compilerAbi = "policy-compiler-v1",
        status = StoredLearningPolicyStatus.CANDIDATE.name,
        sourceValid = true,
        schemaValid = true,
        staleReason = null,
        distinctEpisodeSupport = 0,
        positiveEpisodeCount = 0,
        negativeEpisodeCount = 0,
        usageCount = 0,
        confidence = 0.0,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        producerModelIdentity = "a".repeat(64),
        producerProviderIdentity = "b".repeat(64),
        producerProviderKind = "local_litert",
        producerConfigurationIdentity = "c".repeat(64),
        producerConfigGeneration = 1,
        producerPromptIdentity = "distiller-prompt-v1",
        producerTemplateIdentity = "policy-template-v1",
        producerSchemaIdentity = "policy-schema-v1",
        createdAtMs = 1,
        updatedAtMs = 1,
        lastUsedAtMs = null,
    )
}

private fun createdTables(statements: List<String>): Set<String> = statements.mapNotNull { sql ->
    Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(sql)?.groupValues?.get(1)
}.toSet()
