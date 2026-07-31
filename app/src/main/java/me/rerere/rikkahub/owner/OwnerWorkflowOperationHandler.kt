package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolRuntimeInvocation
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.model.WorkflowCapabilitySnapshot
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import kotlin.uuid.Uuid

/** Owner CRUD plus an interactive run path over the exact current-turn tool surface. */
class OwnerWorkflowOperationHandler(
    private val repository: WorkflowRepository,
    private val actionRunner: WorkflowActionRunner,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.WORKFLOW && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported Workflow action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Workflow action contains an unsupported field.")
        }
        if (action.type in setOf("workflow_create", "workflow_update")) {
            val parsed = parseDefinition(request, action)
            if (parsed is ParsedDefinition.Error) return invalid(parsed.code, parsed.message)
            val definition = (parsed as ParsedDefinition.Value).definition
            val recursive = definition.actions.firstOrNull {
                it.tool.startsWith("owner_") || it.tool == "workflow_run"
            }
            if (recursive != null) {
                return invalid("WORKFLOW_RECURSION_BLOCKED", "Owner and workflow_run tools cannot be nested inside a Workflow.")
            }
            val existing = repository.getById(definition.id)
            if (action.type == "workflow_create" && existing != null) {
                return invalid("WORKFLOW_ALREADY_EXISTS", "Workflow ID already exists; use workflow_update.")
            }
            if (action.type == "workflow_update" && existing == null) {
                return invalid("WORKFLOW_NOT_FOUND", "Workflow to update does not exist.")
            }
        }
        if (action.type in setOf("workflow_delete", "workflow_set_enabled", "workflow_run")) {
            val id = action.arguments.string("workflow_id")?.trim().orEmpty()
            if (id.isBlank() || repository.getById(id) == null) {
                return invalid("WORKFLOW_NOT_FOUND", "Workflow does not exist.")
            }
        }
        if (action.type == "workflow_run" && request.availableTools.isEmpty()) {
            return invalid("OWNER_TOOL_SURFACE_UNAVAILABLE", "The current interactive tool surface is unavailable.")
        }
        return OwnerActionValidation(true, "WORKFLOW_ACTION_VALID", "Workflow action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "workflow_list" -> list(index)
            "workflow_create", "workflow_update" -> upsert(index, request, action)
            "workflow_delete" -> delete(index, action)
            "workflow_set_enabled" -> setEnabled(index, action)
            "workflow_run" -> runInteractive(index, request, action, context)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported Workflow action.")
        }
    }.getOrElse {
        failure(index, action.type, "WORKFLOW_OPERATION_FAILED", "Workflow operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val id = action.arguments.string("workflow_id")
            ?: applied.result.data?.get("workflow_id")?.jsonPrimitive?.contentOrNull
        return when (action.type) {
            "workflow_create", "workflow_update", "workflow_set_enabled" -> if (!id.isNullOrBlank() && repository.getById(id) != null) {
                OwnerActionValidation(true, "WORKFLOW_ACTION_VERIFIED", "Workflow state verified.")
            } else invalid("WORKFLOW_VERIFY_FAILED", "Workflow state could not be confirmed.")
            "workflow_delete" -> if (!id.isNullOrBlank() && repository.getById(id) == null) {
                OwnerActionValidation(true, "WORKFLOW_DELETE_VERIFIED", "Workflow deletion verified.")
            } else invalid("WORKFLOW_VERIFY_FAILED", "Workflow deletion could not be confirmed.")
            else -> OwnerActionValidation(true, "WORKFLOW_ACTION_VERIFIED", "Workflow action completed.")
        }
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? WorkflowReceipt
            ?: return OwnerCompensationResult(true, "WORKFLOW_NO_COMPENSATION_REQUIRED")
        return runCatching {
            if (receipt.previous == null) {
                repository.deleteCascading(receipt.workflowId)
            } else {
                repository.upsert(receipt.previous)
                repository.setEnabled(receipt.workflowId, receipt.previousEnabled)
            }
            OwnerCompensationResult(true, "WORKFLOW_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "WORKFLOW_COMPENSATION_FAILED") }
    }

    private suspend fun list(index: Int): OwnerAppliedAction = success(
        index, "workflow_list", "WORKFLOW_LIST", "Workflow metadata returned.", buildJsonObject {
            put("workflows", buildJsonArray {
                repository.listAll().forEach { loaded ->
                    add(buildJsonObject {
                        put("workflow_id", loaded.entity.id)
                        put("name", loaded.entity.name.take(80))
                        put("enabled", loaded.entity.enabled)
                        put("trigger", loaded.definition.trigger::class.simpleName.orEmpty())
                        put("action_count", loaded.definition.actions.size)
                        put("frozen_capability_count", loaded.definition.capabilitySnapshot.size)
                        put("last_status", loaded.entity.lastRunStatus ?: "NEVER")
                    })
                }
            })
        },
    )

    private suspend fun upsert(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val parsed = parseDefinition(request, action) as? ParsedDefinition.Value
            ?: return failure(index, action.type, "WORKFLOW_INVALID", "Workflow definition is invalid.")
        val old = repository.getById(parsed.definition.id)
        val definition = parsed.definition.copy(
            authoringAssistantId = old?.definition?.authoringAssistantId ?: request.assistantId,
            capabilitySnapshot = WorkflowCapabilitySnapshot.capture(parsed.definition.actions),
            createdAtMs = old?.definition?.createdAtMs ?: parsed.definition.createdAtMs,
            updatedAtMs = System.currentTimeMillis(),
        )
        val receipt = WorkflowReceipt(definition.id, old?.definition, old?.entity?.enabled ?: false)
        repository.upsert(definition)
        repository.setEnabled(definition.id, definition.enabled)
        return success(index, action.type, if (old == null) "WORKFLOW_CREATED" else "WORKFLOW_UPDATED", if (old == null) "Workflow created with a frozen capability snapshot." else "Workflow updated with a new frozen capability snapshot.", buildJsonObject {
            put("workflow_id", definition.id)
            put("frozen_capability_count", definition.capabilitySnapshot.size)
        }, receipt)
    }

    private suspend fun delete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("workflow_id")!!.trim()
        val old = repository.getById(id)!!
        val receipt = WorkflowReceipt(id, old.definition, old.entity.enabled)
        if (!repository.deleteCascading(id)) return failure(index, action.type, "WORKFLOW_DELETE_FAILED", "Workflow could not be deleted.")
        return success(index, action.type, "WORKFLOW_DELETED", "Workflow and run history deleted.", buildJsonObject {
            put("workflow_id", id)
        }, receipt)
    }

    private suspend fun setEnabled(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("workflow_id")!!.trim()
        val enabled = action.arguments.boolean("enabled")
            ?: return failure(index, action.type, "WORKFLOW_ENABLED_REQUIRED", "enabled must be true or false.")
        val old = repository.getById(id)!!
        val receipt = WorkflowReceipt(id, old.definition, old.entity.enabled)
        repository.setEnabled(id, enabled)
        return success(index, action.type, "WORKFLOW_ENABLED_UPDATED", "Workflow enabled state updated.", buildJsonObject {
            put("workflow_id", id)
            put("enabled", enabled)
        }, receipt)
    }

    private suspend fun runInteractive(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val id = action.arguments.string("workflow_id")!!.trim()
        val loaded = repository.getById(id)!!
        val executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = context.conversationId,
            assistantId = context.assistantId.toString(),
            callOrigin = context.origin,
            capabilitySubject = CapabilitySubject(
                id = request.authoritySubjectId,
                type = SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = context.conversationId.toString(),
            ),
            selectedPrivilegedConversation = true,
        )
        val allowedTools = request.availableTools.filterNot { tool ->
            tool.name.startsWith("owner_") || tool.name == "workflow_run"
        }
        val result = actionRunner.run(
            actions = loaded.definition.actions,
            availableTools = allowedTools,
            invocation = ToolRuntimeInvocation(
                executionContext = executionContext,
                unrestrictedOverride = context.unrestrictedOverride,
            ),
        )
        val status = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
        repository.recordFire(
            workflowId = id,
            firedAtMs = System.currentTimeMillis(),
            status = status,
            durationMs = 0,
            // Tool arguments, paths, commands and remote error bodies are not workflow audit
            // material. Keep only a stable outcome code in the durable run row.
            errorMessage = if (result.success) null else "OWNER_INTERACTIVE_WORKFLOW_FAILED",
        )
        return if (result.success) success(index, action.type, "WORKFLOW_RUN_SUCCEEDED", "Workflow ran with the current Owner authority and tool surface.", buildJsonObject {
            put("workflow_id", id)
            put("status", status.name)
        }) else failure(index, action.type, "WORKFLOW_RUN_FAILED", "Workflow action failed; inspect the underlying execution ledger for redacted facts.")
    }

    private sealed interface ParsedDefinition {
        data class Value(val definition: WorkflowDefinition) : ParsedDefinition
        data class Error(val code: String, val message: String) : ParsedDefinition
    }

    private fun parseDefinition(request: OwnerOperationRequest, action: OwnerAction): ParsedDefinition {
        val element = action.arguments["definition"]
            ?: return ParsedDefinition.Error("WORKFLOW_DEFINITION_REQUIRED", "definition is required.")
        val known = request.availableToolNames.filterNotTo(mutableSetOf()) {
            it.startsWith("owner_") || it == "workflow_run"
        }
        return when (val parsed = WorkflowJson.parse(element.toString(), known)) {
            is WorkflowJson.ParseResult.Ok -> ParsedDefinition.Value(parsed.definition)
            is WorkflowJson.ParseResult.Err -> ParsedDefinition.Error(parsed.error, parsed.detail)
        }
    }

    private data class WorkflowReceipt(
        val workflowId: String,
        val previous: WorkflowDefinition?,
        val previousEnabled: Boolean,
    )

    private fun success(index: Int, type: String, code: String, message: String, data: JsonObject? = null, receipt: Any? = null) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data), receipt)
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()

    private companion object {
        val FIELDS = mapOf(
            "workflow_list" to emptySet(),
            "workflow_create" to setOf("definition"),
            "workflow_update" to setOf("definition"),
            "workflow_delete" to setOf("workflow_id"),
            "workflow_set_enabled" to setOf("workflow_id", "enabled"),
            "workflow_run" to setOf("workflow_id"),
        )
    }
}
