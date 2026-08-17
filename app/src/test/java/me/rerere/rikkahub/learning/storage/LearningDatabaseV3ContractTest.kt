package me.rerere.rikkahub.learning.storage

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningDatabaseV9ContractTest {
    @Test
    fun schemaAddsP2ExposureWithoutWeakeningP1ProviderOrReconciliationClosure() {
        assertEquals(9, LEARNING_DATABASE_VERSION)
        assertEquals(1, LEARNING_MIGRATION_1_2.startVersion)
        assertEquals(2, LEARNING_MIGRATION_1_2.endVersion)
        assertEquals(2, LEARNING_MIGRATION_2_3.startVersion)
        assertEquals(3, LEARNING_MIGRATION_2_3.endVersion)
        assertEquals(3, LEARNING_MIGRATION_3_4.startVersion)
        assertEquals(4, LEARNING_MIGRATION_3_4.endVersion)
        assertEquals(4, LEARNING_MIGRATION_4_5.startVersion)
        assertEquals(5, LEARNING_MIGRATION_4_5.endVersion)
        assertEquals(5, LEARNING_MIGRATION_5_6.startVersion)
        assertEquals(6, LEARNING_MIGRATION_5_6.endVersion)
        assertEquals(6, LEARNING_MIGRATION_6_7.startVersion)
        assertEquals(7, LEARNING_MIGRATION_6_7.endVersion)
        assertEquals(7, LEARNING_MIGRATION_7_8.startVersion)
        assertEquals(8, LEARNING_MIGRATION_7_8.endVersion)
        assertEquals(8, LEARNING_MIGRATION_8_9.startVersion)
        assertEquals(9, LEARNING_MIGRATION_8_9.endVersion)
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
        assertEquals(
            setOf(
                "learning_provider_config_cohorts",
                "learning_provider_job_manifests",
                "learning_provider_attempts",
                "learning_reward_signals",
                "policy_reward_evidence",
            ),
            createdTables(LEARNING_V4_SCHEMA_SQL),
        )
        val v4Sql = LEARNING_V4_SCHEMA_SQL.joinToString("\n")
        listOf(
            "reward_dimension",
            "reward_signal_kind",
            "reward_value_milli",
            "execution_verification_state",
            "signal_set_sha256",
            "authority_outcome",
            "runtime_attestation_sha256",
            "request_hmac_sha256",
            "redaction_policy_identity",
            "field_categories_identity",
            "token_estimator_identity",
            "budget_authorization_sha256",
            "dispatch_knowledge",
        ).forEach { required -> assertTrue(required in v4Sql) }
        val v5Sql = LEARNING_V5_SCHEMA_SQL.single()
        assertEquals(
            "ALTER TABLE `learning_stream_checkpoints` ADD COLUMN " +
                "`reconciliation_cursor_v1_json` TEXT",
            v5Sql,
        )
        assertFalse("The v5 cursor must remain nullable", "NOT NULL" in v5Sql)
        assertFalse("The v5 cursor must not fabricate a default", "DEFAULT" in v5Sql)
        assertEquals(
            setOf("learned_workflow_candidates", "learned_workflow_candidate_revisions"),
            createdTables(LEARNING_V7_SCHEMA_SQL),
        )
        val v7Sql = LEARNING_V7_SCHEMA_SQL.joinToString("\n")
        listOf(
            "candidate_version", "state_version", "source_policy_artifact_sha256",
            "source_grant_digest", "positive_anchor_evidence_id", "canonical_template_json",
            "typed_slots_wire", "capability_snapshot_wire", "tool_schema_fingerprints_wire",
            "verification_report_wire", "PRIMARY KEY(`candidate_id`, `state_version`)",
            "ON UPDATE NO ACTION ON DELETE RESTRICT", "ON UPDATE NO ACTION ON DELETE CASCADE",
        ).forEach { required -> assertTrue(required in v7Sql) }
        assertEquals(
            setOf(
                "learning_policy_shadow_observations",
                "learning_policy_shadow_observation_items",
                "curator_delta_candidates",
                "curator_delta_revisions",
                "curator_delta_lineage",
            ),
            createdTables(LEARNING_V8_SCHEMA_SQL),
        )
        val v8Sql = LEARNING_V8_SCHEMA_SQL.joinToString("\n")
        listOf(
            "source_policy_ids_key",
            "candidate_wire",
            "candidate_sha256",
            "input_set_sha256",
            "producer_identity_sha256",
            "apply_plan_id",
            "apply_plan_wire",
            "apply_plan_sha256",
            "redacted_at_ms",
            "parent_revision",
            "parent_artifact_sha256",
            "child_revision",
            "child_artifact_sha256",
            "applicable_template_identity",
            "applicable_configuration_identity",
            "applicable_configuration_generation",
            "applicable_capability_digest",
            "applicable_authority_digest",
            "applicability_cohort_digest",
            POLICY_APPLICABILITY_UNPROVEN_V7_REASON,
            "PRIMARY KEY(`candidate_id`, `state_version`)",
            "ON UPDATE NO ACTION ON DELETE CASCADE",
        ).forEach { required -> assertTrue(required in v8Sql) }
        assertFalse("learning_observed_utility_" in v8Sql)
        assertEquals(
            setOf(
                "learning_observed_utility_assignments",
                "learning_observed_utility_outcomes",
                "learning_observed_utility_evaluation_receipts",
            ),
            createdTables(LEARNING_V9_SCHEMA_SQL),
        )
        assertEquals(10, LEARNING_V9_SCHEMA_SQL.size)
        val v9Sql = LEARNING_V9_SCHEMA_SQL.joinToString("\n")
        listOf(
            "pre_registered_design_digest",
            "cohort_digest",
            "expected_exposure_receipt_digest",
            "outcome_receipt_digest",
            "target_policy_set_digest",
            "source_watermark_status",
            "confidence_lower",
            "confidence_upper",
            "causal_interpretation",
            "ON UPDATE NO ACTION ON DELETE CASCADE",
        ).forEach { required -> assertTrue(required in v9Sql) }
        assertFalse("curator_delta_" in v9Sql)
        assertFalse("learning_policy_shadow_observations" in v9Sql)
        assertFalse("P2 exposure must not contaminate P1 migrations", "policy_exposure" in v4Sql)
        assertFalse("P2 exposure must not contaminate P1 cursor migration", "policy_exposure" in v5Sql)
        assertEquals(
            setOf("learning_policy_exposures", "learning_policy_exposure_items"),
            createdTables(LEARNING_V6_SCHEMA_SQL),
        )
        val v6Sql = LEARNING_V6_SCHEMA_SQL.joinToString("\n")
        assertTrue(
            "ALTER TABLE `learning_policies` ADD COLUMN `content_revision` " +
                "INTEGER NOT NULL DEFAULT 1" in v6Sql,
        )
        assertTrue(
            "ALTER TABLE `learning_policies` ADD COLUMN `applicable_tool_schemas_wire` " +
                "TEXT NOT NULL DEFAULT '$POLICY_TOOL_APPLICABILITY_UNPROVEN_V5'" in v6Sql,
        )
        listOf("applicable_model_identity_wire", "applicable_provider_identity_wire")
            .forEach { column ->
                assertTrue(
                    "ALTER TABLE `learning_policies` ADD COLUMN `$column` TEXT NOT NULL " +
                        "DEFAULT '$POLICY_IDENTITY_APPLICABILITY_ANY'" in v6Sql,
                )
            }
        listOf(
            "policy_set_digest",
            "treatment_arm",
            "context_compiler_abi",
            "furthest_state",
            "host_dispatched_at_ms",
            "first_progress_at_ms",
            "response_finished_at_ms",
            "outcome_linked_at_ms",
            "terminal_outcome",
            "attribution_state",
        ).forEach { required -> assertTrue(required in v6Sql) }
        assertTrue("ON UPDATE NO ACTION ON DELETE RESTRICT" in v6Sql)
        assertTrue("ON UPDATE NO ACTION ON DELETE CASCADE" in v6Sql)
        assertTrue("SET `status` = 'STALE_SOURCE' WHERE `status` = 'STALE'" in v6Sql)
        assertTrue(
            "SET `status` = 'STALE_SCHEMA', `schema_valid` = 0" in v6Sql &&
                "`stale_reason` = '$POLICY_APPLICABILITY_UNPROVEN_V5_REASON'" in v6Sql,
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
            LearningProviderConfigCohortEntity::class.java,
            LearningProviderJobManifestEntity::class.java,
            LearningProviderAttemptEntity::class.java,
            LearningRewardSignalEntity::class.java,
            PolicyRewardEvidenceEntity::class.java,
            LearningPolicyShadowObservationEntity::class.java,
            LearningPolicyShadowObservationItemEntity::class.java,
            LearningPolicyExposureEntity::class.java,
            LearningObservedUtilityAssignmentEntity::class.java,
            LearningObservedUtilityOutcomeEntity::class.java,
            LearningObservedUtilityEvaluationReceiptEntity::class.java,
            LearningPolicyExposureItemEntity::class.java,
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
        val migrationText = (
            LEARNING_V2_SCHEMA_SQL + LEARNING_V3_SCHEMA_SQL + LEARNING_V4_SCHEMA_SQL +
                LEARNING_V5_SCHEMA_SQL + LEARNING_V6_SCHEMA_SQL +
                LEARNING_V8_SCHEMA_SQL + LEARNING_V9_SCHEMA_SQL
            )
            .joinToString("\n")
            .lowercase()
        listOf("payload_json", "raw_prompt", "tool_args", "tool_output", "exception_text")
            .forEach { assertFalse(it in migrationText) }
    }

    @Test
    fun exposureSnapshotRequiresOrderedMilestonesTerminalAndAuthorityAttribution() {
        val retrieved = exposure()
        assertEquals(0L, retrieved.stateVersion)
        assertEquals(LearningPolicyExposureState.RETRIEVED.name, retrieved.furthestState)
        assertThrows(IllegalArgumentException::class.java) {
            retrieved.copy(
                furthestState = LearningPolicyExposureState.COMPILED.name,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            retrieved.copy(
                furthestState = LearningPolicyExposureState.HOST_DISPATCHED.name,
                hostDispatchedAtMs = 2,
                updatedAtMs = 2,
            )
        }

        val finishedWithoutProgress = retrieved.copy(
            stateVersion = 5,
            furthestState = LearningPolicyExposureState.RESPONSE_FINISHED.name,
            compiledAtMs = 2,
            injectedAtMs = 3,
            hostDispatchedAtMs = 4,
            responseFinishedAtMs = 5,
            terminalOutcome = LearningPolicyExposureTerminalOutcome.COMPLETED.name,
            terminalAtMs = 6,
            updatedAtMs = 6,
        )
        assertNull(finishedWithoutProgress.firstProgressAtMs)
        assertThrows(IllegalArgumentException::class.java) {
            finishedWithoutProgress.copy(
                furthestState = LearningPolicyExposureState.OUTCOME_LINKED.name,
                outcomeLinkedAtMs = 7,
                attributionState = LearningPolicyExposureAttributionState.KNOWN.name,
                updatedAtMs = 7,
            )
        }

        val linked = finishedWithoutProgress.copy(
            stateVersion = 6,
            furthestState = LearningPolicyExposureState.OUTCOME_LINKED.name,
            outcomeLinkedAtMs = 7,
            outcomeSourceType = "COMMAND",
            outcomeSourceId = "command-v1:authority",
            outcomeSourceRevision = 1,
            attributionState = LearningPolicyExposureAttributionState.KNOWN.name,
            updatedAtMs = 7,
        )
        assertEquals(LearningPolicyExposureAttributionState.KNOWN.name, linked.attributionState)
        assertFalse(linked.streamId in linked.toString())
        assertFalse(linked.episodeId in linked.toString())
    }

    @Test
    fun exposureItemRequiresAtomicOrderedObservationAndCannotDropInjectedPolicy() {
        val retrieved = exposureItem()
        assertThrows(IllegalArgumentException::class.java) {
            retrieved.copy(injectedAtMs = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            retrieved.copy(
                compiledAtMs = 2,
                injectedAtMs = 3,
                dropReason = "BUDGET",
            )
        }
        val dropped = retrieved.copy(compiledAtMs = 2, dropReason = "BUDGET")
        assertEquals("BUDGET", dropped.dropReason)
        assertFalse(dropped.policyId in dropped.toString())
    }

    @Test
    fun canonicalEmptyRewardSignalSetIsStableAndCannotClaimKnownAuthority() {
        assertEquals(
            LearningCanonicalId.digest("reward-signal-set-v1", emptyList()),
            EMPTY_REWARD_SIGNAL_SET_SHA256,
        )
        val migration = LEARNING_V4_SCHEMA_SQL.joinToString("\n")
        assertTrue("DEFAULT '$EMPTY_REWARD_SIGNAL_SET_SHA256'" in migration)
        assertTrue("DEFAULT 'UNKNOWN'" in migration)
        assertThrows(IllegalArgumentException::class.java) {
            rewardWindow().copy(
                authorityOutcome = LearningRewardAuthorityOutcome.SUCCESS.name,
            )
        }
    }

    @Test
    fun providerManifestAndAttemptRejectIncompleteAttestationBudgetAndDispatchState() {
        val manifest = providerManifest()
        assertEquals(1, manifest.maxProviderCalls)
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(runtimeAttestationSha256 = "not-a-sha")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(inputUtf8Bytes = manifest.maxInputUtf8Bytes + 1)
        }
        val attempt = providerAttempt()
        assertThrows(IllegalArgumentException::class.java) {
            attempt.copy(
                state = LearningProviderAttemptState.DISPATCH_STARTED.name,
                dispatchKnowledge = LearningProviderDispatchKnowledge.NOT_DISPATCHED.name,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            attempt.copy(reservedProviderCalls = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            attempt.copy(leaseProcessSessionId = "00000000-0000-0000-0000-000000000000")
        }
    }

    @Test
    fun providerCohortIdentityTupleIsReusableAndItsGenerationIsGloballyUnique() {
        val migration = LEARNING_V4_SCHEMA_SQL.joinToString("\n")
        assertTrue(
            "`model_identity_sha256`, `configuration_identity_sha256`)" in migration,
        )
        assertFalse(
            "`configuration_identity_sha256`, `configuration_generation`)" in migration,
        )
        assertTrue(
            "index_learning_provider_config_cohorts_configuration_generation" in migration,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LearningProviderConfigCohortEntity(
                id = "provider-cohort-v1:${"a".repeat(64)}",
                providerKind = "local_litert",
                providerIdentitySha256 = "b".repeat(64),
                modelIdentitySha256 = "c".repeat(64),
                configurationIdentitySha256 = "d".repeat(64),
                configurationGeneration = 0,
                createdAtMs = 1,
            )
        }
    }

    @Test
    fun rewardSignalRequiresExactKnownAuthorityAndNoPayloadEscapeHatch() {
        val signal = rewardSignal()
        assertEquals(LearningRewardKnowledge.KNOWN.name, signal.knowledge)
        assertThrows(IllegalArgumentException::class.java) {
            signal.copy(valueMilli = 1_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signal.copy(valueMilli = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signal.copy(sourceRevision = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signal.copy(streamId = "not-a-stream")
        }
    }

    @Test
    fun v3ProviderJobsAreFailClosedRatherThanGivenFabricatedManifests() {
        val migration = LEARNING_V4_SCHEMA_SQL.joinToString("\n")
        assertTrue("`state` = 'CANCELLED'" in migration)
        assertTrue("`last_error_code` = 'INVALID_JOB_SPEC'" in migration)
        assertTrue("'REFLECT_EPISODE_V1', 'DISTILL_POLICY_V1'" in migration)
        assertFalse("INSERT INTO `learning_provider_job_manifests`" in migration)
    }

    @Test
    fun policyUsageUtilityAndContentRevisionCannotBeHalfRecorded() {
        val policy = policy()
        assertEquals(1L, policy.contentRevision)
        assertEquals(0L, policy.usageCount)
        assertNull(policy.lastUsedAtMs)
        assertNull(policy.observedUtilityDelta)
        assertNull(policy.utilityUncertainty)
        assertThrows(IllegalArgumentException::class.java) { policy.copy(usageCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(lastUsedAtMs = 10) }
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(observedUtilityDelta = 0.1)
        }
        assertEquals(
            StoredLearningPolicyStatus.ACTIVE.name,
            policy.copy(status = StoredLearningPolicyStatus.ACTIVE.name).status,
        )
        assertThrows(IllegalArgumentException::class.java) {
            policy.copy(contentRevision = 0)
        }
    }

    @Test
    fun policyApplicabilityWireIsLosslessBoundedAndCanonical() {
        val first = "1".repeat(64)
        val second = "2".repeat(64)
        val wire = PolicyApplicabilityWire.encodeToolSchemas(linkedSetOf(second, first))
        assertEquals("$POLICY_TOOL_APPLICABILITY_EXACT_PREFIX$first,$second", wire)
        assertEquals(linkedSetOf(first, second), PolicyApplicabilityWire.decodeToolSchemasOrNull(wire))
        assertEquals(
            emptySet<String>(),
            PolicyApplicabilityWire.decodeToolSchemasOrNull(POLICY_TOOL_APPLICABILITY_EXACT_PREFIX),
        )
        assertNull(
            PolicyApplicabilityWire.decodeToolSchemasOrNull(
                POLICY_TOOL_APPLICABILITY_UNPROVEN_V5,
            ),
        )
        assertEquals(
            PolicyIdentityApplicability.Any,
            PolicyApplicabilityWire.decodeIdentity(POLICY_IDENTITY_APPLICABILITY_ANY),
        )
        assertEquals(
            PolicyIdentityApplicability.Exact("model-v1"),
            PolicyApplicabilityWire.decodeIdentity(
                PolicyApplicabilityWire.encodeExactIdentity("model-v1"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PolicyApplicabilityWire.decodeToolSchemasOrNull(
                "$POLICY_TOOL_APPLICABILITY_EXACT_PREFIX$second,$first",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyApplicabilityWire.decodeToolSchemasOrNull(
                "$POLICY_TOOL_APPLICABILITY_EXACT_PREFIX$first,$first",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyApplicabilityWire.encodeToolSchemas(
                (0..MAX_POLICY_APPLICABLE_TOOL_SCHEMAS).map { index ->
                    index.toString(16).padStart(64, '0')
                }.toSet(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyApplicabilityWire.decodeIdentity("model-v1")
        }
    }

    @Test
    fun unprovenV5ApplicabilityIsRepresentableOnlyAsFailClosedSchemaStale() {
        assertThrows(IllegalArgumentException::class.java) {
            policy().copy(applicableToolSchemasWire = POLICY_TOOL_APPLICABILITY_UNPROVEN_V5)
        }
        val migrated = policy().copy(
            status = StoredLearningPolicyStatus.STALE_SCHEMA.name,
            schemaValid = false,
            applicableToolSchemasWire = POLICY_TOOL_APPLICABILITY_UNPROVEN_V5,
            staleReason = POLICY_APPLICABILITY_UNPROVEN_V5_REASON,
        )
        assertFalse(migrated.schemaValid)
        assertEquals(StoredLearningPolicyStatus.STALE_SCHEMA.name, migrated.status)
        assertEquals(
            StoredLearningPolicyStatus.ARCHIVED.name,
            migrated.copy(
                status = StoredLearningPolicyStatus.ARCHIVED.name,
                staleReason = null,
            ).status,
        )
    }

    @Test
    fun finalApplicabilityProjectionMatchesExplicitIdentityAndRequiredToolSubset() {
        val required = "1".repeat(64)
        val extra = "2".repeat(64)
        val exact = LearningPolicyApplicabilityProjection(
            policyId = "policy-v1:${"a".repeat(64)}",
            contentRevision = 2,
            artifactSha256 = "b".repeat(64),
            status = StoredLearningPolicyStatus.ACTIVE.name,
            schemaValid = true,
            applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(setOf(required)),
            applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("model-v1"),
            applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("provider-v1"),
            applicableTemplateIdentity = "e".repeat(64),
            applicableConfigurationIdentity = "c".repeat(64),
            applicableConfigurationGeneration = 1L,
            applicableCapabilityDigest = null,
            applicableAuthorityDigest = null,
        )
        assertTrue(exact.matchesFinalBinding("model-v1", "provider-v1", "e".repeat(64), "c".repeat(64), 1L, setOf(required, extra)))
        assertFalse(exact.matchesFinalBinding("model-v2", "provider-v1", "e".repeat(64), "c".repeat(64), 1L, setOf(required, extra)))
        assertFalse(exact.matchesFinalBinding("model-v1", "provider-v2", "e".repeat(64), "c".repeat(64), 1L, setOf(required, extra)))
        assertFalse(exact.matchesFinalBinding("model-v1", "provider-v1", "f".repeat(64), "c".repeat(64), 1L, setOf(required, extra)))
        assertFalse(exact.matchesFinalBinding("model-v1", "provider-v1", "e".repeat(64), "d".repeat(64), 1L, setOf(required, extra)))
        assertFalse(exact.matchesFinalBinding("model-v1", "provider-v1", "e".repeat(64), "c".repeat(64), 2L, setOf(required, extra)))
        assertFalse(exact.matchesFinalBinding("model-v1", "provider-v1", "e".repeat(64), "c".repeat(64), 1L, setOf(extra)))
        assertFalse(
            exact.copy(status = StoredLearningPolicyStatus.SHADOW.name)
                .matchesFinalBinding("model-v1", "provider-v1", "e".repeat(64), "c".repeat(64), 1L, setOf(required)),
        )
        assertFalse(
            exact.copy(
                applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
                applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("b".repeat(64)),
                applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("c".repeat(64)),
            ).matchesFinalBinding("other-model", "other-provider", "e".repeat(64), "c".repeat(64), 1L, emptySet()),
        )
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

    private fun exposure() = LearningPolicyExposureEntity(
        id = "policy-exposure-v1:${"a".repeat(64)}",
        streamId = "00000000-0000-0000-0000-000000000001",
        replayGeneration = 0,
        episodeId = "episode-v1:${"b".repeat(64)}",
        logicalRunId = "00000000-0000-0000-0000-000000000003",
        attemptOrdinal = 1,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000002",
        taskSignature = "task-signature-v1",
        policySetDigest = "c".repeat(64),
        treatmentArm = "TREATMENT",
        modelIdentity = "model-v1",
        providerIdentity = "provider-v1",
        providerGeneration = 1,
        toolsetFingerprint = "d".repeat(64),
        contextCompilerAbi = "recall-compiler-v1",
        stateVersion = 0,
        furthestState = LearningPolicyExposureState.RETRIEVED.name,
        retrievedAtMs = 1,
        compiledAtMs = null,
        injectedAtMs = null,
        hostDispatchedAtMs = null,
        firstProgressAtMs = null,
        responseFinishedAtMs = null,
        outcomeLinkedAtMs = null,
        terminalOutcome = null,
        terminalAtMs = null,
        outcomeSourceType = null,
        outcomeSourceId = null,
        outcomeSourceRevision = null,
        attributionState = LearningPolicyExposureAttributionState.UNKNOWN.name,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun exposureItem() = LearningPolicyExposureItemEntity(
        exposureId = exposure().id,
        policyId = "policy-v1:${"e".repeat(64)}",
        policyRevision = 1,
        artifactSha256 = "f".repeat(64),
        applicabilityCohortDigest = "a".repeat(64),
        rank = 1,
        estimatedTokens = 32,
        dropReason = null,
        retrievedAtMs = 1,
        compiledAtMs = null,
        injectedAtMs = null,
    )

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
        contentRevision = 1,
        artifactSha256 = "a".repeat(64),
        compilerAbi = "policy-compiler-v1",
        status = StoredLearningPolicyStatus.CANDIDATE.name,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
        applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("b".repeat(64)),
        applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("c".repeat(64)),
        applicableTemplateIdentity = "e".repeat(64),
        applicableConfigurationIdentity = "d".repeat(64),
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = null,
        applicableAuthorityDigest = null,
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

    private fun rewardWindow() = LearningRewardWindowEntity(
        id = "reward-window-v1:${"a".repeat(64)}",
        episodeId = "episode-v1:${"b".repeat(64)}",
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000002",
        openedAtMs = 1,
        closeAfterMs = 2,
        state = LearningRewardWindowState.CLOSED.name,
        goalKnowledge = LearningRewardKnowledge.UNKNOWN.name,
        goalValue = null,
        goalUnknownReason = "NO_SIGNAL",
        goalEvidenceSha256 = null,
        processKnowledge = LearningRewardKnowledge.UNKNOWN.name,
        processValue = null,
        processUnknownReason = "NO_SIGNAL",
        processEvidenceSha256 = null,
        userKnowledge = LearningRewardKnowledge.UNKNOWN.name,
        userValue = null,
        userUnknownReason = "NO_SIGNAL",
        userEvidenceSha256 = null,
        weakLabel = null,
        rewardConfigIdentity = "reward-config-v1",
        closedAtMs = 2,
        updatedAtMs = 2,
    )

    private fun providerManifest() = LearningProviderJobManifestEntity(
        jobId = "learning-p1-job-v1:${"a".repeat(64)}",
        cohortId = "provider-cohort-v1:${"b".repeat(64)}",
        manifestSchemaVersion = PROVIDER_JOB_MANIFEST_SCHEMA_VERSION,
        requestHmacSha256 = "c".repeat(64),
        inputIdentitySha256 = "d".repeat(64),
        runtimeAttestationSha256 = "e".repeat(64),
        redactionPolicyIdentity = "learning-redaction-v1",
        fieldCategoriesIdentity = "field-categories-v1",
        tokenEstimatorIdentity = "token-estimator-v1",
        providerRequestKey = "learning-provider-v1:${"f".repeat(64)}",
        inputUtf8Bytes = 1_024,
        maxInputUtf8Bytes = 2_048,
        estimatedInputTokens = 256,
        maxOutputTokens = 512,
        maxOutputUtf8Bytes = 8_192,
        maxProviderCalls = 1,
        maxCostMicros = 0,
        timeoutMs = 60_000,
        frozenAtMs = 1,
    )

    private fun providerAttempt() = LearningProviderAttemptEntity(
        jobId = providerManifest().jobId,
        attemptOrdinal = 1,
        attemptIdentitySha256 = "a".repeat(64),
        state = LearningProviderAttemptState.RESERVED.name,
        dispatchKnowledge = LearningProviderDispatchKnowledge.NOT_DISPATCHED.name,
        budgetState = LearningProviderBudgetState.RESERVED.name,
        budgetAuthorizationSha256 = "b".repeat(64),
        budgetWindowStartMs = 0,
        budgetWindowEndMs = 86_400_000,
        reservedProviderCalls = 1,
        reservedInputTokens = 256,
        reservedOutputTokens = 512,
        reservedCostMicros = 0,
        actualProviderCalls = null,
        actualInputTokens = null,
        actualOutputTokens = null,
        actualCostMicros = null,
        terminalOutcome = null,
        leaseProcessSessionId = "00000000-0000-0000-0000-000000000010",
        leaseWorkerId = "00000000-0000-0000-0000-000000000011",
        leaseGeneration = 1,
        leaseUntilMs = 100,
        createdAtMs = 1,
        dispatchStartedAtMs = null,
        terminalObservedAtMs = null,
        updatedAtMs = 1,
        finishedAtMs = null,
    )

    private fun rewardSignal() = LearningRewardSignalEntity(
        id = "reward-signal-v1:${"a".repeat(64)}",
        episodeId = "episode-v1:${"b".repeat(64)}",
        streamId = "00000000-0000-0000-0000-000000000001",
        replayGeneration = 0,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000002",
        authorityEventId = "learning-event-v3:${"c".repeat(64)}",
        sourceType = "USER_FEEDBACK",
        sourceId = "feedback-v1:${"d".repeat(64)}",
        sourceRevision = 1,
        sourceIntegritySha256 = "e".repeat(64),
        dimension = LearningRewardDimension.USER.name,
        signalKind = LearningRewardSignalKind.EXPLICIT_USER_FEEDBACK.name,
        knowledge = LearningRewardKnowledge.KNOWN.name,
        valueMilli = 1_000,
        unknownReason = null,
        occurredAtMs = 1,
        createdAtMs = 1,
    )
}

private fun createdTables(statements: List<String>): Set<String> = statements.mapNotNull { sql ->
    Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(sql)?.groupValues?.get(1)
}.toSet()
