package me.rerere.rikkahub.learning.workflow.review

import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

/** Fresh host metadata only; it never executes a Tool. */
class ProductionWorkflowReviewToolMetadataSource(
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
) : WorkflowReviewToolMetadataSource {
    override suspend fun current(
        assistantId: String,
        authoritySubjectId: String?,
    ): ToolCatalogSnapshot? {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return null
        val assistant = settings.assistants.singleOrNull { it.id.toString() == assistantId }
            ?: return null
        if (authoritySubjectId != null) {
            val authority = SecondUserAuthorityRegistry.current() ?: return null
            if (authority.subjectId != authoritySubjectId || authority.assistantId != assistant.id) {
                return null
            }
        }
        val definitions = localTools.getTools(
            assistant.localTools,
            ToolInvocationContext(
                callerAssistantId = assistantId,
                callerWorkspaceId = assistant.workspaceId?.toString(),
                callOrigin = ToolCallOrigin.TrustedWorkflow,
                isHeadless = true,
            ),
        )
        return ToolCatalogSnapshot.fromDefinitions(definitions)
    }
}
