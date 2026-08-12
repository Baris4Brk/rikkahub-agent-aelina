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
}
