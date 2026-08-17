package me.rerere.rikkahub.learning.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPreferencesV1Test {
    @Test
    fun defaultsAreCompletelyDisabled() {
        val preferences = LearningPreferencesV1().failClosed()

        assertFalse(preferences.handoff)
        assertFalse(preferences.capture)
        assertFalse(preferences.jobs)
        assertFalse(preferences.reflectionShadow)
        assertFalse(preferences.policyCandidate)
        assertFalse(preferences.policyRetrievalShadow)
        assertFalse(preferences.policyInjection)
        assertFalse(preferences.curatorUpdate)
        assertFalse(preferences.curatorMerge)
        assertFalse(preferences.curatorSplit)
        assertFalse(preferences.curatorSupersede)
        assertFalse(preferences.backgroundWorkAuthorized)
        assertFalse(preferences.allowRemoteReflection)
        assertTrue(preferences.authorizedModelIdentityDigests.isEmpty())
    }

    @Test
    fun invalidImportedConsentFailsClosed() {
        val invalid = LearningPreferencesV1(
            schemaVersion = 99,
            handoff = true,
            capture = true,
            jobs = true,
            backgroundWorkAuthorized = true,
            authorizedModelIdentityDigests = setOf("not-a-digest"),
            allowRemoteReflection = true,
        )

        assertEquals(LearningPreferencesV1(), invalid.failClosed())
    }

    @Test
    fun remoteReflectionRequiresBackgroundAuthorization() {
        val invalid = LearningPreferencesV1(allowRemoteReflection = true)
        assertEquals(LearningPreferencesV1(), invalid.failClosed())
    }

    @Test
    fun remoteReflectionConsentDoesNotChangeRolloutStageIdentity() {
        val model = "a".repeat(64)
        val shadow = LearningRolloutPolicy.configure(
            current = LearningPreferencesV1(),
            stage = LearningRolloutStage.CANDIDATE_SHADOW,
            exactModelIdentityDigest = model,
        )
        val remote = LearningRolloutPolicy.configureRemoteReflection(
            current = shadow,
            allowed = true,
            exactProviderIdentityDigest = "b".repeat(64),
            exactModelIdentityDigest = model,
        )

        assertEquals(LearningRolloutStage.CANDIDATE_SHADOW, LearningRolloutPolicy.stageOf(shadow))
        assertEquals(LearningRolloutStage.CANDIDATE_SHADOW, LearningRolloutPolicy.stageOf(remote))
        assertTrue(remote.allowRemoteReflection)
        assertEquals(setOf(model), remote.authorizedModelIdentityDigests)
        assertEquals(model, remote.remoteReflectionModelIdentityDigest)
    }

    @Test
    fun remoteStageSelectionAtomicallyPinsSoleModelAndExactConsentPair() {
        val provider = "b".repeat(64)
        val model = "c".repeat(64)

        val configured = LearningRolloutPolicy.configure(
            current = LearningPreferencesV1(),
            stage = LearningRolloutStage.RETRIEVAL_SHADOW,
            exactModelIdentityDigest = model,
            exactRemoteProviderIdentityDigest = provider,
            authorizeRemoteReflection = true,
        )

        assertEquals(setOf(model), configured.authorizedModelIdentityDigests)
        assertTrue(configured.allowRemoteReflection)
        assertEquals(provider, configured.remoteReflectionProviderIdentityDigest)
        assertEquals(model, configured.remoteReflectionModelIdentityDigest)
        assertEquals(configured, configured.failClosed())

        val local = LearningRolloutPolicy.configure(
            current = configured,
            stage = LearningRolloutStage.RETRIEVAL_SHADOW,
            exactModelIdentityDigest = "d".repeat(64),
        )
        assertFalse(local.allowRemoteReflection)
        assertEquals(null, local.remoteReflectionProviderIdentityDigest)
        assertEquals(null, local.remoteReflectionModelIdentityDigest)
    }

    @Test
    fun assistantScopeCaptureConsentsDefaultOff() {
        val assistant = me.rerere.rikkahub.data.model.Assistant()
        assertFalse(assistant.learningCaptureEnabled)
        assertFalse(assistant.authoritySubjectLearningCaptureEnabled)
    }
}
