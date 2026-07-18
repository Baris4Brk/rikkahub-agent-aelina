package me.rerere.rikkahub.setup

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

private const val SETUP_MAX_CHANGES = 20
private const val SETUP_MAX_TRANSACTIONS = 100
private const val SETUP_CANCELLED = "SETUP_CANCELLED"
private const val SETUP_INTERNAL_ERROR = "SETUP_INTERNAL_ERROR"

data class SetupOwner(
    val assistantId: String,
    val conversationId: String,
)

enum class SetupAssistantFlag(val wire: String) {
    ENABLE_MEMORY("enable_memory"),
    USE_GLOBAL_MEMORY("use_global_memory"),
    ENABLE_RECENT_CHATS_REFERENCE("enable_recent_chats_reference"),
    STREAM_OUTPUT("stream_output"),
    FAST_PATH_ROUTER_ENABLED("fast_path_router_enabled"),
    ENABLE_WEB_SEARCH("enable_web_search"),
}

enum class SetupAppFlag(val wire: String) {
    DYNAMIC_COLOR("dynamic_color"),
    DEVELOPER_MODE("developer_mode"),
    ENABLE_SUGGESTION("enable_suggestion"),
}

enum class SetupAppModel(val wire: String, val clearable: Boolean) {
    CHAT_MODEL("chat_model", false),
    FAST_MODEL("fast_model", false),
    TITLE_MODEL("title_model", true),
    SUGGESTION_MODEL("suggestion_model", true),
}

sealed interface SetupChange {
    val key: String
    val type: String

    data class AssistantFlag(
        val assistantId: Uuid,
        val flag: SetupAssistantFlag,
        val enabled: Boolean,
    ) : SetupChange {
        override val key: String = "assistant:$assistantId:${flag.wire}"
        override val type: String = "assistant_flag"
    }

    data class AssistantWorkspace(
        val assistantId: Uuid,
        val workspaceId: Uuid?,
    ) : SetupChange {
        override val key: String = "assistant:$assistantId:workspace"
        override val type: String = "assistant_workspace"
    }

    data class AssistantChatModel(
        val assistantId: Uuid,
        val modelId: Uuid?,
    ) : SetupChange {
        override val key: String = "assistant:$assistantId:chat_model"
        override val type: String = "assistant_chat_model"
    }

    data class AssistantTool(
        val assistantId: Uuid,
        val toolType: String,
        val enabled: Boolean,
    ) : SetupChange {
        override val key: String = "assistant:$assistantId:tool:$toolType"
        override val type: String = "assistant_tool"
    }

    data class AssistantSkills(
        val assistantId: Uuid,
        val names: Set<String>,
    ) : SetupChange {
        override val key: String = "assistant:$assistantId:skills"
        override val type: String = "assistant_skills"
    }

    data class AssistantMcpServers(
        val assistantId: Uuid,
        val serverIds: Set<Uuid>,
    ) : SetupChange {
        override val key: String = "assistant:$assistantId:mcp_servers"
        override val type: String = "assistant_mcp_servers"
    }

    data class AppFlag(
        val flag: SetupAppFlag,
        val enabled: Boolean,
    ) : SetupChange {
        override val key: String = "app:${flag.wire}"
        override val type: String = "app_flag"
    }

    data class AppModel(
        val model: SetupAppModel,
        val modelId: Uuid?,
    ) : SetupChange {
        override val key: String = "app:${model.wire}"
        override val type: String = "app_model"
    }
}

sealed interface SetupValue {
    data class Bool(val value: Boolean) : SetupValue
    data class Id(val value: Uuid?) : SetupValue
    data class Names(val value: Set<String>) : SetupValue
    data class Ids(val value: Set<Uuid>) : SetupValue
}

data class SetupPreparedChange(
    val change: SetupChange,
    val key: String,
    val type: String,
    val summary: String,
    val before: SetupValue,
    val after: SetupValue,
) {
    val isNoOp: Boolean get() = before == after
}

sealed interface SetupPrepareResult {
    data class Prepared(val change: SetupPreparedChange) : SetupPrepareResult
    data class Rejected(val code: String, val detail: String) : SetupPrepareResult
}

enum class SetupCasResult {
    Applied,
    Conflict,
}

data class SetupDoctorCheck(
    val key: String,
    val ok: Boolean,
    val code: String,
    val detail: String,
)

interface SetupTransactionBackend {
    suspend fun prepare(change: SetupChange): SetupPrepareResult

    suspend fun compareAndSet(
        change: SetupPreparedChange,
        expected: SetupValue,
        update: SetupValue,
    ): SetupCasResult

    suspend fun doctor(change: SetupPreparedChange): SetupDoctorCheck
}

enum class SetupAuditStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

interface SetupAuditLedger {
    suspend fun open(transactionId: String, changeTypes: List<String>): String?
    suspend fun finish(runId: String?, status: SetupAuditStatus, errorCode: String? = null)

    companion object {
        val NONE: SetupAuditLedger = object : SetupAuditLedger {
            override suspend fun open(transactionId: String, changeTypes: List<String>): String? = null
            override suspend fun finish(runId: String?, status: SetupAuditStatus, errorCode: String?) = Unit
        }
    }
}

enum class SetupTransactionStatus {
    PLANNED,
    APPLYING,
    SUCCEEDED,
    ROLLED_BACK,
    PARTIAL_ROLLBACK,
    FAILED,
}

enum class SetupStepStatus {
    PLANNED,
    APPLIED,
    VERIFIED,
    ROLLED_BACK,
    ROLLBACK_CONFLICT,
    FAILED,
}

data class SetupStepView(
    val key: String,
    val type: String,
    val summary: String,
    val noOp: Boolean,
    val status: SetupStepStatus,
    val code: String? = null,
)

data class SetupTransactionView(
    val id: String,
    val status: SetupTransactionStatus,
    val steps: List<SetupStepView>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastErrorCode: String? = null,
)

data class SetupOperationResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val transaction: SetupTransactionView? = null,
    val checks: List<SetupDoctorCheck> = emptyList(),
)

private data class SetupTransactionRecord(
    val id: String,
    val owner: SetupOwner,
    val prepared: List<SetupPreparedChange>,
    val stepStatuses: List<SetupStepStatus>,
    val stepCodes: List<String?>,
    val status: SetupTransactionStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastErrorCode: String? = null,
) {
    fun view(): SetupTransactionView = SetupTransactionView(
        id = id,
        status = status,
        steps = prepared.mapIndexed { index, change ->
            SetupStepView(
                key = change.key,
                type = change.type,
                summary = change.summary,
                noOp = change.isNoOp,
                status = stepStatuses[index],
                code = stepCodes[index],
            )
        },
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        lastErrorCode = lastErrorCode,
    )
}

class SetupTransactionCoordinator(
    private val backend: SetupTransactionBackend,
    private val auditLedger: SetupAuditLedger,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val transactions = ConcurrentHashMap<String, SetupTransactionRecord>()
    private val operationLock = Mutex()

    suspend fun plan(
        owner: SetupOwner,
        changes: List<SetupChange>,
    ): SetupOperationResult {
        if (owner.assistantId.isBlank() || owner.conversationId.isBlank()) {
            return rejected("SETUP_OWNER_REQUIRED", "assistant and conversation are required")
        }
        if (changes.size !in 1..SETUP_MAX_CHANGES) {
            return rejected("SETUP_CHANGE_COUNT_INVALID", "setup requires 1-$SETUP_MAX_CHANGES changes")
        }
        val duplicateKeys = changes.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        if (duplicateKeys.isNotEmpty()) {
            return rejected("SETUP_DUPLICATE_FIELD", "each configuration field may appear only once")
        }

        val prepared = ArrayList<SetupPreparedChange>(changes.size)
        for (change in changes) {
            when (val result = backend.prepare(change)) {
                is SetupPrepareResult.Prepared -> prepared += result.change
                is SetupPrepareResult.Rejected -> return rejected(result.code, result.detail)
            }
        }

        val now = nowMs()
        val record = SetupTransactionRecord(
            id = Uuid.random().toString(),
            owner = owner,
            prepared = prepared,
            stepStatuses = List(prepared.size) { SetupStepStatus.PLANNED },
            stepCodes = List(prepared.size) { null },
            status = SetupTransactionStatus.PLANNED,
            createdAtMs = now,
            updatedAtMs = now,
        )
        transactions[record.id] = record
        trimTransactions()
        return SetupOperationResult(
            ok = true,
            code = "SETUP_PLANNED",
            message = "Typed setup plan created; no configuration was changed.",
            transaction = record.view(),
        )
    }

    suspend fun apply(
        owner: SetupOwner,
        transactionId: String,
    ): SetupOperationResult = operationLock.withLock {
        var record = transactions[transactionId]
            ?.takeIf { it.owner == owner }
            ?: return@withLock rejected(
                "SETUP_TRANSACTION_NOT_FOUND",
                "setup transaction is not visible to this owner",
            )
        if (record.status == SetupTransactionStatus.SUCCEEDED) {
            return@withLock SetupOperationResult(
                true,
                "SETUP_ALREADY_APPLIED",
                "Setup transaction was already applied.",
                record.view(),
            )
        }
        if (record.status != SetupTransactionStatus.PLANNED) {
            return@withLock SetupOperationResult(
                false,
                "SETUP_TRANSACTION_NOT_APPLICABLE",
                "Only a planned setup transaction can be applied.",
                record.view(),
            )
        }

        record = record.copy(
            status = SetupTransactionStatus.APPLYING,
            updatedAtMs = nowMs(),
        ).also { transactions[it.id] = it }
        val appliedIndices = mutableListOf<Int>()
        var auditRunId: String? = null

        try {
            auditRunId = auditLedger.open(record.id, record.prepared.map { it.type })
            for ((index, change) in record.prepared.withIndex()) {
                if (!change.isNoOp) {
                    val cas = backend.compareAndSet(change, change.before, change.after)
                    if (cas != SetupCasResult.Applied) {
                        record = record.failed(index, "SETUP_CONFLICT", nowMs())
                            .also { transactions[it.id] = it }
                        record = rollback(record, appliedIndices, "SETUP_CONFLICT")
                        auditLedger.finish(auditRunId, SetupAuditStatus.FAILED, "SETUP_CONFLICT")
                        return@withLock SetupOperationResult(
                            false,
                            rollbackCode(record),
                            rollbackMessage(record),
                            record.view(),
                        )
                    }
                    appliedIndices += index
                    record = record.withStep(index, SetupStepStatus.APPLIED, null, nowMs())
                        .also { transactions[it.id] = it }
                }

                val check = backend.doctor(change)
                if (!check.ok) {
                    record = record.failed(index, check.code, nowMs())
                        .also { transactions[it.id] = it }
                    record = rollback(record, appliedIndices, check.code)
                    auditLedger.finish(auditRunId, SetupAuditStatus.FAILED, check.code)
                    return@withLock SetupOperationResult(
                        false,
                        rollbackCode(record),
                        rollbackMessage(record),
                        record.view(),
                        listOf(check),
                    )
                }
                record = record.withStep(index, SetupStepStatus.VERIFIED, check.code, nowMs())
                    .also { transactions[it.id] = it }
            }

            record = record.copy(
                status = SetupTransactionStatus.SUCCEEDED,
                updatedAtMs = nowMs(),
            ).also { transactions[it.id] = it }
            auditLedger.finish(auditRunId, SetupAuditStatus.SUCCEEDED)
            SetupOperationResult(
                true,
                "SETUP_APPLIED",
                "All typed changes were applied and verified.",
                record.view(),
            )
        } catch (cancelled: CancellationException) {
            record = withContext(NonCancellable) {
                compensate(
                    record = record,
                    appliedIndices = appliedIndices,
                    failureCode = SETUP_CANCELLED,
                    auditRunId = auditRunId,
                    auditStatus = SetupAuditStatus.CANCELLED,
                )
            }
            throw cancelled
        } catch (_: Throwable) {
            record = withContext(NonCancellable) {
                compensate(
                    record = record,
                    appliedIndices = appliedIndices,
                    failureCode = SETUP_INTERNAL_ERROR,
                    auditRunId = auditRunId,
                    auditStatus = SetupAuditStatus.FAILED,
                )
            }
            SetupOperationResult(
                false,
                rollbackCode(record),
                rollbackMessage(record),
                record.view(),
            )
        }
    }

    private suspend fun compensate(
        record: SetupTransactionRecord,
        appliedIndices: List<Int>,
        failureCode: String,
        auditRunId: String?,
        auditStatus: SetupAuditStatus,
    ): SetupTransactionRecord {
        val failed = record.copy(
            status = SetupTransactionStatus.FAILED,
            updatedAtMs = nowMs(),
            lastErrorCode = failureCode,
        ).also { transactions[it.id] = it }
        val rolledBack = rollback(failed, appliedIndices, failureCode)
        auditLedger.finish(auditRunId, auditStatus, failureCode)
        return rolledBack
    }

    suspend fun verify(
        owner: SetupOwner,
        transactionId: String,
    ): SetupOperationResult = operationLock.withLock {
        val record = transactions[transactionId]
            ?.takeIf { it.owner == owner }
            ?: return@withLock rejected(
                "SETUP_TRANSACTION_NOT_FOUND",
                "setup transaction is not visible to this owner",
            )
        val checks = record.prepared.map { backend.doctor(it) }
        val ok = checks.all { it.ok }
        SetupOperationResult(
            ok = ok,
            code = if (ok) "SETUP_VERIFIED" else "SETUP_VERIFY_FAILED",
            message = if (ok) {
                "Every targeted field and referenced resource is ready."
            } else {
                "One or more targeted fields or referenced resources do not match the plan."
            },
            transaction = record.view(),
            checks = checks,
        )
    }

    private suspend fun rollback(
        failedRecord: SetupTransactionRecord,
        appliedIndices: List<Int>,
        failureCode: String,
    ): SetupTransactionRecord {
        var record = failedRecord
        var complete = true
        for (index in appliedIndices.asReversed()) {
            val change = record.prepared[index]
            when (backend.compareAndSet(change, change.after, change.before)) {
                SetupCasResult.Applied -> {
                    record = record.withStep(
                        index,
                        SetupStepStatus.ROLLED_BACK,
                        "ROLLED_BACK",
                        nowMs(),
                    )
                }
                SetupCasResult.Conflict -> {
                    complete = false
                    record = record.withStep(
                        index,
                        SetupStepStatus.ROLLBACK_CONFLICT,
                        "ROLLBACK_CONFLICT",
                        nowMs(),
                    )
                }
            }
            transactions[record.id] = record
        }
        return record.copy(
            status = if (complete) {
                SetupTransactionStatus.ROLLED_BACK
            } else {
                SetupTransactionStatus.PARTIAL_ROLLBACK
            },
            updatedAtMs = nowMs(),
            lastErrorCode = failureCode,
        ).also { transactions[it.id] = it }
    }

    private fun rollbackCode(record: SetupTransactionRecord): String =
        if (record.status == SetupTransactionStatus.ROLLED_BACK) {
            "SETUP_ROLLED_BACK"
        } else {
            "SETUP_PARTIAL_ROLLBACK"
        }

    private fun rollbackMessage(record: SetupTransactionRecord): String =
        if (record.status == SetupTransactionStatus.ROLLED_BACK) {
            "Setup failed; every applied field was safely rolled back."
        } else {
            "Setup failed and at least one field changed concurrently; conflicting fields were preserved."
        }

    private fun rejected(code: String, message: String) =
        SetupOperationResult(false, code, message)

    private fun trimTransactions() {
        if (transactions.size <= SETUP_MAX_TRANSACTIONS) return
        transactions.values
            .sortedBy { it.updatedAtMs }
            .take(transactions.size - SETUP_MAX_TRANSACTIONS)
            .forEach { transactions.remove(it.id, it) }
    }
}

private fun SetupTransactionRecord.withStep(
    index: Int,
    status: SetupStepStatus,
    code: String?,
    nowMs: Long,
): SetupTransactionRecord {
    val statuses = stepStatuses.toMutableList().also { it[index] = status }
    val codes = stepCodes.toMutableList().also { it[index] = code }
    return copy(stepStatuses = statuses, stepCodes = codes, updatedAtMs = nowMs)
}

private fun SetupTransactionRecord.failed(
    index: Int,
    code: String,
    nowMs: Long,
): SetupTransactionRecord = withStep(index, SetupStepStatus.FAILED, code, nowMs).copy(
    status = SetupTransactionStatus.FAILED,
    lastErrorCode = code,
)
