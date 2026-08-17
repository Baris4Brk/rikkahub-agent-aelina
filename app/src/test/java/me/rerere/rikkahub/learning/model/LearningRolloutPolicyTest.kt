package me.rerere.rikkahub.learning.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningRolloutPolicyTest {
    private val model = "a".repeat(64)

    @Test
    fun everyStageMapsToOneCanonicalFailClosedPreferenceSet() {
        LearningRolloutStage.entries.forEach { stage ->
            val configured = LearningRolloutPolicy.configure(
                current = LearningPreferencesV1(),
                stage = stage,
                exactModelIdentityDigest = model.takeIf {
                    stage >= LearningRolloutStage.CANDIDATE_SHADOW
                },
            )
            assertEquals(stage, LearningRolloutPolicy.stageOf(configured))
            assertEquals(configured, configured.failClosed())
        }
    }

    @Test
    fun providerBackedStagesRequireExactlyOneExactModel() {
        assertThrows(IllegalArgumentException::class.java) {
            LearningRolloutPolicy.configure(
                LearningPreferencesV1(),
                LearningRolloutStage.CANDIDATE_SHADOW,
            )
        }
        val invalid = LearningPreferencesV1(
            handoff = true,
            capture = true,
            jobs = true,
            reflectionShadow = true,
            policyCandidate = true,
            backgroundWorkAuthorized = true,
            authorizedModelIdentityDigests = setOf("a".repeat(64), "b".repeat(64)),
        )
        assertEquals(LearningPreferencesV1(), invalid.failClosed())
    }

    @Test
    fun arbitraryBooleanMixesAreNotAValidRolloutStage() {
        val invalid = LearningPreferencesV1(handoff = true, reflectionShadow = true)
        assertNull(LearningRolloutPolicy.stageOf(invalid))
        assertEquals(LearningPreferencesV1(), invalid.failClosed())
    }

    @Test
    fun stageDDoesNotEnableRemoteOrPolicyInjection() {
        val configured = LearningRolloutPolicy.configure(
            LearningPreferencesV1(),
            LearningRolloutStage.RETRIEVAL_SHADOW,
            model,
        )
        assertTrue(!configured.allowRemoteReflection)
        val flags = LearningFeatureFlagPolicy.resolve(
            LearningFeatureFlags(
                schemaReady = true,
                handoff = configured.handoff,
                capture = configured.capture,
                jobs = configured.jobs,
                reflectionShadow = configured.reflectionShadow,
                policyCandidate = configured.policyCandidate,
                policyRetrievalShadow = configured.policyRetrievalShadow,
                policyInjection = false,
            ),
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
            ),
        )
        assertTrue(flags.isValid)
        assertTrue(!flags.effective.policyInjection)
    }

    @Test
    fun stageEIsExplicitDeviceLocalPolicyOptIn() {
        val configured = LearningRolloutPolicy.configure(
            LearningPreferencesV1(),
            LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
            model,
        )
        assertEquals(LearningRolloutStage.REVIEWED_POLICY_OPT_IN, LearningRolloutPolicy.stageOf(configured))
        assertTrue(configured.policyInjection)
        assertTrue(configured.policyRetrievalShadow)
        assertTrue(!configured.allowRemoteReflection)
    }

    @Test
    fun remoteReflectionIsAnIndependentExplicitConsentAndRetentionPreservesStage() {
        val stage = LearningRolloutPolicy.configure(
            LearningPreferencesV1(),
            LearningRolloutStage.CANDIDATE_SHADOW,
            model,
        )
        val remote = LearningRolloutPolicy.configureRemoteReflection(
            stage,
            allowed = true,
            exactProviderIdentityDigest = "b".repeat(64),
            exactModelIdentityDigest = model,
        )
        assertTrue(remote.allowRemoteReflection)
        assertEquals(
            "remote consent is independent and must not make the persisted stage unclassifiable",
            LearningRolloutStage.CANDIDATE_SHADOW,
            LearningRolloutPolicy.stageOf(remote),
        )
        val retained = LearningRolloutPolicy.configureRetention(
            stage,
            LearningRetentionPreferencesV1(
                tracePreset = LearningRetentionPresetV1.MINIMAL,
                rewardPreset = LearningRetentionPresetV1.EXTENDED,
            ),
        )
        assertEquals(LearningRolloutStage.CANDIDATE_SHADOW, LearningRolloutPolicy.stageOf(retained))
        assertEquals(LearningRetentionPresetV1.MINIMAL, retained.retention.tracePreset)
        assertEquals(LearningRetentionPresetV1.EXTENDED, retained.retention.rewardPreset)
    }

    @Test
    fun remoteReflectionCannotOpenBeforeProviderBackedShadow() {
        assertThrows(IllegalArgumentException::class.java) {
            LearningRolloutPolicy.configureRemoteReflection(
                LearningPreferencesV1(),
                allowed = true,
                exactProviderIdentityDigest = "b".repeat(64),
                exactModelIdentityDigest = "c".repeat(64),
            )
        }
    }

    @Test
    fun workflowSwitchesAreIndependentOrderedAndStageRollbackTurnsThemOff() {
        val stageE = LearningRolloutPolicy.configure(
            LearningPreferencesV1(),
            LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
            model,
        )
        assertTrue(!stageE.workflowCandidate)
        assertTrue(!stageE.workflowPromotion)

        val candidate = LearningRolloutPolicy.configureWorkflowCandidate(stageE, enabled = true)
        assertTrue(candidate.workflowCandidate)
        assertTrue(!candidate.workflowPromotion)
        assertEquals(LearningRolloutStage.REVIEWED_POLICY_OPT_IN, LearningRolloutPolicy.stageOf(candidate))

        val promotion = LearningRolloutPolicy.configureWorkflowPromotion(candidate, enabled = true)
        assertTrue(promotion.workflowCandidate)
        assertTrue(promotion.workflowPromotion)
        assertEquals(promotion, promotion.failClosed())
        assertTrue(
            LearningRolloutPolicy.configure(
                promotion,
                LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
                model,
            ).workflowPromotion,
        )

        val candidateOff = LearningRolloutPolicy.configureWorkflowCandidate(promotion, enabled = false)
        assertTrue(!candidateOff.workflowCandidate)
        assertTrue(!candidateOff.workflowPromotion)

        val stageD = LearningRolloutPolicy.configure(
            promotion,
            LearningRolloutStage.RETRIEVAL_SHADOW,
            model,
        )
        assertTrue(!stageD.workflowCandidate)
        assertTrue(!stageD.workflowPromotion)
        assertThrows(IllegalArgumentException::class.java) {
            LearningRolloutPolicy.configureWorkflowPromotion(stageE, enabled = true)
        }
    }

    @Test
    fun curatorOperationSwitchesAreIndependentDefaultOffAndRollbackTogether() {
        val stageE = LearningRolloutPolicy.configure(
            LearningPreferencesV1(),
            LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
            model,
        )
        assertTrue(!stageE.curatorUpdate && !stageE.curatorMerge &&
            !stageE.curatorSplit && !stageE.curatorSupersede)

        val mergeOnly = LearningRolloutPolicy.configureCuratorOperation(
            stageE,
            LearningCuratorOperation.MERGE,
            enabled = true,
        )
        assertTrue(!mergeOnly.curatorUpdate)
        assertTrue(mergeOnly.curatorMerge)
        assertTrue(!mergeOnly.curatorSplit)
        assertTrue(!mergeOnly.curatorSupersede)
        assertEquals(mergeOnly, mergeOnly.failClosed())
        assertEquals(
            LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
            LearningRolloutPolicy.stageOf(mergeOnly),
        )

        val sameStage = LearningRolloutPolicy.configure(
            mergeOnly,
            LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
            model,
        )
        assertTrue(sameStage.curatorMerge)
        val rollback = LearningRolloutPolicy.configure(
            sameStage,
            LearningRolloutStage.RETRIEVAL_SHADOW,
            model,
        )
        assertTrue(!rollback.curatorUpdate && !rollback.curatorMerge &&
            !rollback.curatorSplit && !rollback.curatorSupersede)
        assertThrows(IllegalArgumentException::class.java) {
            LearningRolloutPolicy.configureCuratorOperation(
                rollback,
                LearningCuratorOperation.UPDATE,
                enabled = true,
            )
        }
    }
}
