package me.rerere.rikkahub.setup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus

internal fun buildSetupAuditMetadata(
    transactionId: String,
    changeTypes: List<String>,
): JsonObject = buildJsonObject {
    put("transaction_id", transactionId)
    put("change_types", buildJsonArray {
        changeTypes.distinct().sorted().forEach { add(JsonPrimitive(it)) }
    })
    put("change_count", changeTypes.size)
}

/** AgentRun audit ledger; write failures make the coordinator compensate rather than go unaudited. */
class AgentRunSetupAuditLedger(
    private val repository: AgentRunRepository,
) : SetupAuditLedger {
    override suspend fun open(transactionId: String, changeTypes: List<String>): String =
        repository.open(
            kind = AgentRunKind.Setup,
            domainId = transactionId,
            metadata = buildSetupAuditMetadata(transactionId, changeTypes),
        )

    override suspend fun finish(
        runId: String?,
        status: SetupAuditStatus,
        errorCode: String?,
    ) {
        if (runId == null) return
        repository.markTerminal(
            id = runId,
            status = when (status) {
                SetupAuditStatus.SUCCEEDED -> AgentRunStatus.succeeded
                SetupAuditStatus.FAILED -> AgentRunStatus.failed
                SetupAuditStatus.CANCELLED -> AgentRunStatus.cancelled
            },
            lastError = errorCode,
        )
    }
}
