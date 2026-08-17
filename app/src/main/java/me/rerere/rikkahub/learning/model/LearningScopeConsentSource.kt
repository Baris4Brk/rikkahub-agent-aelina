package me.rerere.rikkahub.learning.model

import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.authority.source.ConversationSourceInitialCaptureGate
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import kotlin.uuid.Uuid

/** Explicit, scope-exact user capture consent. It is separate from the global rollout stage. */
fun interface LearningScopeConsentSource {
    fun captureAllowed(scope: LearningScope): Boolean
}

object DisabledLearningScopeConsentSource : LearningScopeConsentSource {
    override fun captureAllowed(scope: LearningScope): Boolean = false
}

/** Test-only/open policy must be passed explicitly; production never binds this object. */
internal object AllowAllLearningScopeConsentSource : LearningScopeConsentSource {
    override fun captureAllowed(scope: LearningScope): Boolean = true
}

class SettingsLearningScopeConsentSource(
    private val settingsStore: SettingsStore,
) : LearningScopeConsentSource {
    override fun captureAllowed(scope: LearningScope): Boolean = try {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return false
        when (scope) {
            is LearningScope.Assistant -> settings.assistants.singleOrNull {
                it.id == scope.assistantId
            }?.learningCaptureEnabled == true

            is LearningScope.AuthoritySubject -> {
                val authority = SecondUserAuthorityRegistry.current() ?: return false
                authority.subjectId == scope.authoritySubjectId &&
                    settings.assistants.singleOrNull { it.id == authority.assistantId }
                        ?.authoritySubjectLearningCaptureEnabled == true
            }
        }
    } catch (_: Exception) {
        false
    }
}

/** Keeps never-enabled conversations on the baseline DB path while preserving later invalidation. */
class LearningConversationSourceInitialCaptureGate(
    private val flags: LearningFeatureFlagSource,
    private val consent: LearningScopeConsentSource,
) : ConversationSourceInitialCaptureGate {
    override fun allowInitialCapture(scope: ConversationSourceScope): Boolean = try {
        val resolved = flags.current()
        if (
            !resolved.isValid || !resolved.effective.handoff ||
            !resolved.effective.capture || !resolved.effective.jobs
        ) return false
        val learningScope = when (scope.kind) {
            ConversationSourceScopeKind.ASSISTANT -> LearningScope.Assistant(Uuid.parse(scope.id))
            ConversationSourceScopeKind.AUTHORITY_SUBJECT ->
                LearningScope.AuthoritySubject(scope.id)
        }
        consent.captureAllowed(learningScope)
    } catch (_: Exception) {
        false
    }
}
