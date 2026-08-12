package me.rerere.rikkahub.data.execution

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase

data class ExecutionOwningMessageAuthority(
    val executionId: String,
    val assistantMessageId: String,
    val assistantMessageRevision: Long,
) {
    init {
        require(executionId.isSafeExecutionBindingId())
        require(assistantMessageId.isSafeExecutionBindingId())
        require(assistantMessageRevision > 0L)
    }

    override fun toString(): String =
        "ExecutionOwningMessageAuthority(revision=$assistantMessageRevision, ids=<redacted>)"
}

sealed interface ExecutionMessageBindResult {
    data class Applied(val record: ExecutionRecord) : ExecutionMessageBindResult
    data class Duplicate(val record: ExecutionRecord) : ExecutionMessageBindResult
    data object Missing : ExecutionMessageBindResult
    data object Conflict : ExecutionMessageBindResult
}

/**
 * Binds a tool execution to the exact assistant-message authority checkpoint. This must run inside
 * the same outer transaction that persists the WAITING/final assistant message. It never guesses
 * a message revision from updateAt or content hash.
 */
class ExecutionMessageAuthorityBinder(
    private val database: AppDatabase,
    private val dao: ExecutionRecordDao,
) {
    suspend fun find(executionId: String): ExecutionRecord? = dao.getById(executionId)

    suspend fun bindInCurrentAuthorityTransaction(
        authority: ExecutionOwningMessageAuthority,
    ): ExecutionMessageBindResult {
        check(database.inTransaction()) { "execution_message_binding_requires_authority_transaction" }
        val current = dao.getById(authority.executionId) ?: return ExecutionMessageBindResult.Missing
        if (current.owningAssistantMessageId != null) {
            return if (
                current.owningAssistantMessageId == authority.assistantMessageId &&
                current.owningAssistantMessageRevision == authority.assistantMessageRevision
            ) {
                ExecutionMessageBindResult.Duplicate(current)
            } else {
                ExecutionMessageBindResult.Conflict
            }
        }
        val updated = dao.bindOwningAssistantMessageIfEmpty(
            id = authority.executionId,
            expectedVersion = current.stateVersion,
            messageId = authority.assistantMessageId,
            messageRevision = authority.assistantMessageRevision,
        )
        if (updated != 1) return ExecutionMessageBindResult.Conflict
        val next = requireNotNull(dao.getById(authority.executionId))
        return ExecutionMessageBindResult.Applied(next)
    }

    suspend fun bind(authority: ExecutionOwningMessageAuthority): ExecutionMessageBindResult =
        database.withTransaction { bindInCurrentAuthorityTransaction(authority) }

    suspend fun requireBoundInCurrentAuthorityTransaction(
        authorities: Collection<ExecutionOwningMessageAuthority>,
    ) {
        authorities.distinctBy(ExecutionOwningMessageAuthority::executionId).forEach { authority ->
            when (bindInCurrentAuthorityTransaction(authority)) {
                is ExecutionMessageBindResult.Applied,
                is ExecutionMessageBindResult.Duplicate,
                -> Unit
                ExecutionMessageBindResult.Missing ->
                    error("execution_message_binding_missing")
                ExecutionMessageBindResult.Conflict ->
                    error("execution_message_binding_conflict")
            }
        }
    }
}

private fun String.isSafeExecutionBindingId(): Boolean =
    length in 1..256 && all { char ->
        char.isLetterOrDigit() || char in charArrayOf('-', '_', '.', ':', '@')
    }
