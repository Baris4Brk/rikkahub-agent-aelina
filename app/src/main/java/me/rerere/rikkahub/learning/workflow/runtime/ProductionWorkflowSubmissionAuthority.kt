package me.rerere.rikkahub.learning.workflow.runtime

import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowAuthorityResolver
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

/** Current AppDatabase authority plus the exact assistant's current local tool surface. */
class ProductionWorkflowSubmissionAuthority(
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val grants: PolicyGrantAuthoritySource,
) : WorkflowSubmissionAuthorityPort {
    override suspend fun loadCurrent(
        proposal: LearnedPolicyProposal,
    ): WorkflowSubmissionAuthorityContext? {
        val exactGrant = proposal.exactGrant
        if (!grants.revalidateExact(exactGrant)) return null
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return null
        val assistant = settings.assistants.singleOrNull {
            it.id.toString() == proposal.consumingAssistantId
        } ?: return null
        val subjectId = (exactGrant.scope as? LearningScope.AuthoritySubject)?.authoritySubjectId
        val authorityExists = subjectId?.let { requiredSubject ->
            SecondUserAuthorityRegistry.current()?.let { active ->
                active.subjectId == requiredSubject && active.assistantId == assistant.id
            } == true
        } ?: (exactGrant.scope == LearningScope.Assistant(assistant.id))
        if (!authorityExists) return null
        val catalog = ToolCatalogSnapshot.fromDefinitions(
            localTools.getTools(
                assistant.localTools,
                ToolInvocationContext(
                    callerAssistantId = assistant.id.toString(),
                    callerWorkspaceId = assistant.workspaceId?.toString(),
                    callOrigin = ToolCallOrigin.TrustedWorkflow,
                    isHeadless = true,
                ),
            ),
        )
        return WorkflowSubmissionAuthorityContext(
            exactGrant = exactGrant,
            catalog = catalog,
            authorityResolver = LearnedWorkflowAuthorityResolver { candidateAssistant, candidateSubject ->
                candidateAssistant == assistant.id.toString() &&
                    candidateSubject == subjectId && authorityExists
            },
        )
    }

    override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean =
        grants.revalidateExact(snapshot)
}
