package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.model.LearningProviderKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ProviderExecutionSafetyTest {
    @Test
    fun localLiteRtNeedsRuntimeManifestAndDurableAttemptFacts() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = null,
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = true,
                ),
            ),
        )
        assertTrue(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = "a".repeat(64),
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = true,
                ),
            ),
        )
    }

    @Test
    fun remoteNeedsExactConsentInAdditionToDispatchManifestAndAttemptFacts() {
        assertTrue(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.REMOTE,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = "a".repeat(64),
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = true,
                    exactRemoteConsent = true,
                ),
            ),
        )
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.REMOTE,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = "a".repeat(64),
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = true,
                    exactRemoteConsent = false,
                ),
            ),
        )
    }

    @Test
    fun localRuntimeDigestMustBeCanonicalLowerSha256() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = "A".repeat(64),
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = true,
                ),
            ),
        )
    }

    @Test
    fun exactManifestAloneCannotSubstituteDurableAttemptAuthority() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = "a".repeat(64),
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = false,
                ),
            ),
        )
    }

    @Test
    fun aicoreIsNeverAuthorizedForBackgroundLearning() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.AICORE,
                capabilities = P1ProviderExecutionCapabilities(
                    runtimeAttestationSha256 = "a".repeat(64),
                    exactManifestValidated = true,
                    durableAttemptAuthorityPresent = true,
                ),
            ),
        )
    }
}
