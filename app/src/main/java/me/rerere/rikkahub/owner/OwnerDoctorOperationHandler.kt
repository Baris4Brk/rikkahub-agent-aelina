package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.tools.ownerToolSchemaUtf8Bytes
import me.rerere.rikkahub.diagnostics.ExecutionConsistencyDoctor
import me.rerere.rikkahub.owner.db.HostLocalServiceDao
import me.rerere.rikkahub.owner.db.HostOperationDao
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.security.SecretPlaintextSessionManager
import me.rerere.rikkahub.security.SecretPlaintextSessionState
import me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks
import me.rerere.rikkahub.ui.pages.setting.doctor.Severity

/** Redacted Owner diagnostics and the narrow repair set allowed by the P2.2 contract. */
class OwnerDoctorOperationHandler(
    private val checks: DoctorChecks,
    private val executionDoctor: ExecutionConsistencyDoctor,
    private val operationDao: HostOperationDao,
    private val serviceDao: HostLocalServiceDao,
    private val serviceSupervisor: OwnerLocalServiceSupervisor,
    private val plaintextSessions: SecretPlaintextSessionManager,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.DOCTOR && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported Doctor action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Doctor action contains an unsupported field.")
        }
        if (action.type == "doctor_repair" && action.arguments.string("repair") !in REPAIRS) {
            return invalid("DOCTOR_REPAIR_UNSUPPORTED", "Only safe reconcile, projection rebuild, retention, and service probes are available.")
        }
        if (action.type == "doctor_recover_operation") {
            val id = action.arguments.string("request_id")?.trim().orEmpty()
            if (id.isBlank() || operationDao.get(id) == null) return invalid("OWNER_OPERATION_NOT_FOUND", "Owner operation does not exist.")
        }
        return OwnerActionValidation(true, "DOCTOR_ACTION_VALID", "Doctor action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "rikkahub_state_get", "doctor_check" -> inspect(index, action.type)
            "doctor_repair" -> repair(index, action)
            "doctor_recover_operation" -> recover(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported Doctor action.")
        }
    }.getOrElse {
        failure(index, action.type, "DOCTOR_FAILED", "Doctor operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ) = if (applied.result.ok) OwnerActionValidation(true, "DOCTOR_ACTION_VERIFIED", "Doctor action completed.")
    else invalid(applied.result.code, applied.result.message)

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ) = OwnerCompensationResult(true, "DOCTOR_NO_COMPENSATION_REQUIRED")

    private suspend fun inspect(index: Int, type: String): OwnerAppliedAction {
        val rows = checks.runAll()
        val execution = executionDoctor.inspect()
        val authority = SecondUserAuthorityRegistry.current()
        val recoverable = operationDao.getRecoverable()
        val services = serviceDao.getEnabled()
        val schemaBytes = ownerToolSchemaUtf8Bytes()
        return success(index, type, "DOCTOR_REPORT", "Redacted Owner runtime diagnostics completed.", buildJsonObject {
            put("authority_active", authority != null)
            put("authority_epoch", authority?.authorityEpoch ?: -1)
            put("plaintext_session_open", plaintextSessions.state.value is SecretPlaintextSessionState.Open)
            put("recoverable_operation_count", recoverable.size)
            put("enabled_service_count", services.size)
            put("owner_tool_family_count", OwnerToolFamily.entries.size)
            put("owner_action_count", OwnerActionRegistry.actionCount())
            put("owner_schema_bytes", schemaBytes.values.sum())
            put("execution_tracking_healthy", execution.healthy)
            put("execution_active_count", execution.activeExecutionCount)
            put("execution_stale_count", execution.staleProbeCount)
            put("redaction_violation_count", execution.redactionViolationCount)
            put("severity_counts", buildJsonObject {
                Severity.entries.forEach { severity -> put(severity.name, rows.count { it.severity == severity }) }
            })
            put("failing_check_ids", buildJsonArray {
                rows.filter { it.severity == Severity.FAIL }.take(32).forEach { add(it.id.take(120)) }
            })
        })
    }

    private suspend fun repair(index: Int, action: OwnerAction): OwnerAppliedAction {
        val repair = action.arguments.string("repair")!!
        val count = when (repair) {
            "reprobe_executions" -> executionDoctor.reprobe().size
            "rebuild_approvals" -> executionDoctor.rebuildApprovalProjection().restored
            "retention_cleanup" -> {
                executionDoctor.runRetentionCleanup()
                1
            }
            "reconcile_services" -> serviceSupervisor.reconcileOnce()
            else -> 0
        }
        return success(index, action.type, "DOCTOR_REPAIR_COMPLETE", "Safe Doctor repair completed.", buildJsonObject {
            put("repair", repair)
            put("affected", count)
        })
    }

    private suspend fun recover(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("request_id")!!.trim()
        val record = operationDao.get(id)!!
        // Never replay an unknown side effect. Re-probe only the external facts that have a
        // stable runtime identity, then report the durable state for user attention.
        executionDoctor.reprobe()
        serviceSupervisor.reconcileOnce()
        val latest = operationDao.get(id) ?: record
        return success(index, action.type, "OWNER_OPERATION_FACTS_RECHECKED", "External facts were re-probed without replaying the operation.", buildJsonObject {
            put("request_id", id)
            put("state", latest.state)
            put("recovery_code", latest.recoveryCode ?: "NONE")
            put("replayed", false)
        })
    }

    private fun success(index: Int, type: String, code: String, message: String, data: JsonObject? = null) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data))
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        val FIELDS = mapOf(
            "rikkahub_state_get" to emptySet(),
            "doctor_check" to emptySet(),
            "doctor_repair" to setOf("repair"),
            "doctor_recover_operation" to setOf("request_id"),
        )
        val REPAIRS = setOf("reprobe_executions", "rebuild_approvals", "retention_cleanup", "reconcile_services")
    }
}
