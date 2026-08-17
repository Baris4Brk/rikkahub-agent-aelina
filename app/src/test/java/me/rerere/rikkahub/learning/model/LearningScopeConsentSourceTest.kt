package me.rerere.rikkahub.learning.model

import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningScopeConsentSourceTest {
    @Test
    fun disabledDefaultRejectsEveryScopeAndExplicitTestPolicyIsExact() {
        val assistant = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        val authority = LearningScope.AuthoritySubject("authority-subject-v1")

        assertFalse(DisabledLearningScopeConsentSource.captureAllowed(assistant))
        assertFalse(DisabledLearningScopeConsentSource.captureAllowed(authority))
        assertTrue(AllowAllLearningScopeConsentSource.captureAllowed(assistant))
        assertTrue(AllowAllLearningScopeConsentSource.captureAllowed(authority))
    }

    @Test
    fun initialSourceCaptureRequiresResolvedGlobalStageAndExactScopeConsent() {
        val scope = ConversationSourceScope(
            ConversationSourceScopeKind.ASSISTANT,
            "00000000-0000-4000-8000-000000000001",
        )
        fun flags(capture: Boolean) = LearningFeatureFlagSource {
            LearningFeatureFlagPolicy.resolve(
                configured = LearningFeatureFlags(
                    schemaReady = capture,
                    handoff = capture,
                    capture = capture,
                    jobs = capture,
                ),
                capabilities = LearningFeatureCapabilities(
                    schemaReady = true,
                    typedJobExecutionReady = true,
                ),
            )
        }

        assertFalse(
            LearningConversationSourceInitialCaptureGate(
                flags(false),
                LearningScopeConsentSource { true },
            ).allowInitialCapture(scope),
        )
        assertFalse(
            LearningConversationSourceInitialCaptureGate(
                flags(true),
                LearningScopeConsentSource { false },
            ).allowInitialCapture(scope),
        )
        assertTrue(
            LearningConversationSourceInitialCaptureGate(
                flags(true),
                LearningScopeConsentSource { true },
            ).allowInitialCapture(scope),
        )
    }
}
