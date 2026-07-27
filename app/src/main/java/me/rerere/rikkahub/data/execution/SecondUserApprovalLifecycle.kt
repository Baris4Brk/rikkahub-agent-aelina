package me.rerere.rikkahub.data.execution

import android.util.Log
import androidx.room.withTransaction
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository

/** Non-secret ownership captured at the point a second-user generation pauses. */
data class PendingApprovalOwner(
    val runId: String,
    val commandId: String?,
    val conversationId: String,
    val subjectId: String,
    val subjectType: SubjectType,
    val origin: ToolCallOrigin,
)

/** Arguments are inspected in memory to classify risk; they are never persisted here. */
data class PendingApprovalTool(
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
)

enum class PersistedApprovalDecision {
    APPROVED,
    ANSWERED,
    DENIED,
}

sealed interface ApprovalResolutionResult {
    data class Applied(val projection: PendingToolApprovalRecord) : ApprovalResolutionResult
    data class Idempotent(val projection: PendingToolApprovalRecord) : ApprovalResolutionResult
    data class Conflict(val reasonCode: String) : ApprovalResolutionResult
    data object Missing : ApprovalResolutionResult
    data object TrustedAppRequired : ApprovalResolutionResult
}

internal sealed interface ApprovalResolutionPrecondition {
    data object Proceed : ApprovalResolutionPrecondition
    data object Idempotent : ApprovalResolutionPrecondition
    data class Conflict(val reasonCode: String) : ApprovalResolutionPrecondition
    data object TrustedAppRequired : ApprovalResolutionPrecondition
}

internal fun evaluateApprovalResolution(
    currentStatus: ApprovalStatus,
    currentVersion: Long,
    decision: PersistedApprovalDecision,
    expectedVersion: Long?,
    trustedAppApproval: Boolean,
): ApprovalResolutionPrecondition {
    if (decision != PersistedApprovalDecision.DENIED && !trustedAppApproval) {
        return ApprovalResolutionPrecondition.TrustedAppRequired
    }
    val desiredStatus = if (decision == PersistedApprovalDecision.DENIED) {
        ApprovalStatus.DENIED
    } else {
        ApprovalStatus.APPROVED
    }
    if (currentStatus != ApprovalStatus.PENDING) {
        return if (currentStatus == desiredStatus) {
            ApprovalResolutionPrecondition.Idempotent
        } else {
            ApprovalResolutionPrecondition.Conflict("approval_already_resolved")
        }
    }
    if (expectedVersion != null && expectedVersion != currentVersion) {
        return ApprovalResolutionPrecondition.Conflict("approval_version_conflict")
    }
    return ApprovalResolutionPrecondition.Proceed
}

/**
 * Atomic bridge between the executable message graph and the redacted approval/execution ledger.
 *
 * No model argument, command, path, output, answer, denial text, nonce, or expiry is copied into
 * either projection. The conversation graph remains the only source for executable payloads.
 */
class SecondUserApprovalLifecycle(
    private val database: AppDatabase,
    private val conversationRepository: ConversationRepository,
    private val approvalDao: PendingToolApprovalDao,
    private val executionRepository: ExecutionRepository,
    private val retentionManager: ExecutionRetentionManager,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun findLatest(
        conversationId: String,
        toolCallId: String,
    ): PendingToolApprovalRecord? = approvalDao.getLatestForToolCall(conversationId, toolCallId)

    suspend fun persistPendingBarrier(
        conversation: Conversation,
        owner: PendingApprovalOwner,
        tools: List<PendingApprovalTool>,
    ): List<PendingToolApprovalRecord> {
        require(owner.subjectType == SubjectType.LOCAL_SECOND_USER) {
            "second_user_approval_owner_required"
        }
        require(conversation.id.toString() == owner.conversationId) {
            "approval_conversation_mismatch"
        }
        if (tools.isEmpty()) return emptyList()
        val requestedAt = nowMs()
        val records = database.withTransaction {
            conversationRepository.persistConversationInCurrentTransaction(conversation)
            tools.distinctBy { it.toolCallId }.map { tool ->
                val executionId = ExecutionRecordIds.tool(owner.runId, tool.toolCallId)
                val resolved = ToolCapabilityResolver.resolve(tool.toolName, tool.arguments)
                val projection = PendingToolApprovalRecord(
                    approvalId = approvalId(executionId),
                    executionId = executionId,
                    traceId = owner.runId.take(MAX_ID_CHARS),
                    toolCallId = tool.toolCallId.take(MAX_ID_CHARS),
                    conversationId = owner.conversationId.take(MAX_ID_CHARS),
                    subjectId = owner.subjectId.take(MAX_SUBJECT_CHARS),
                    subjectType = owner.subjectType.name,
                    origin = owner.origin.name,
                    capabilityKey = resolved.capabilities
                        .map { it.value }
                        .sorted()
                        .joinToString(",")
                        .take(MAX_CAPABILITY_CHARS),
                    resourceCategory = resolved.resource.kind.take(MAX_CATEGORY_CHARS),
                    requestedAtMs = requestedAt,
                    stateVersion = 1,
                )
                val inserted = approvalDao.insertIgnore(projection)
                val durableProjection = if (inserted == -1L) {
                    val existing = checkNotNull(approvalDao.getById(projection.approvalId))
                    check(existing.status == ApprovalStatus.PENDING.name) {
                        "approval_already_resolved"
                    }
                    check(existing.executionId == executionId &&
                        existing.conversationId == owner.conversationId &&
                        existing.toolCallId == projection.toolCallId
                    ) { "approval_projection_collision" }
                    existing
                } else {
                    projection
                }

                val execution = executionRepository.open(
                    draft = ExecutionRecordDraft(
                        id = executionId,
                        traceId = owner.runId,
                        commandId = owner.commandId,
                        conversationId = owner.conversationId,
                        subjectId = owner.subjectId,
                        subjectType = owner.subjectType.name,
                        origin = owner.origin.name,
                        capabilityKeys = projection.capabilityKey,
                        resourceSummary = projection.resourceCategory,
                        runtime = runtimeFor(tool.toolName),
                        idempotencyKey = "approval:${projection.approvalId}".take(300),
                        initialStatus = ExecutionStatus.waiting_approval,
                        verificationState = VerificationState.DATABASE_CONFIRMED,
                    ),
                    mutationId = "approval-open:${projection.approvalId}",
                    source = ExecutionStateSource.DATABASE,
                    reasonCode = "approval_pending",
                )
                check(ExecutionStatus.fromWire(execution.status) == ExecutionStatus.waiting_approval) {
                    "approval_execution_not_waiting"
                }
                durableProjection
            }
        }
        refreshSearchProjection(conversation)
        return records
    }

    suspend fun resolve(
        currentConversation: Conversation,
        updatedConversation: Conversation,
        toolCallId: String,
        decision: PersistedApprovalDecision,
        expectedStateVersion: Long?,
        resolutionRequestId: String,
        trustedAppApproval: Boolean,
    ): ApprovalResolutionResult {
        val conversationId = currentConversation.id.toString()
        require(updatedConversation.id == currentConversation.id) { "approval_conversation_mismatch" }
        var committed = false
        val result = database.withTransaction {
            val projection = approvalDao.getLatestForToolCall(conversationId, toolCallId)
                ?: return@withTransaction ApprovalResolutionResult.Missing
            val desiredStatus = if (decision == PersistedApprovalDecision.DENIED) {
                ApprovalStatus.DENIED
            } else {
                ApprovalStatus.APPROVED
            }
            val currentStatus = ApprovalStatus.fromWire(projection.status)
            when (val precondition = evaluateApprovalResolution(
                currentStatus = currentStatus,
                currentVersion = projection.stateVersion,
                decision = decision,
                expectedVersion = expectedStateVersion,
                trustedAppApproval = trustedAppApproval,
            )) {
                ApprovalResolutionPrecondition.Proceed -> Unit
                ApprovalResolutionPrecondition.Idempotent ->
                    return@withTransaction ApprovalResolutionResult.Idempotent(projection)
                is ApprovalResolutionPrecondition.Conflict ->
                    return@withTransaction ApprovalResolutionResult.Conflict(precondition.reasonCode)
                ApprovalResolutionPrecondition.TrustedAppRequired ->
                    return@withTransaction ApprovalResolutionResult.TrustedAppRequired
            }
            val resolutionVersion = projection.stateVersion + 1
            val resolvedAt = nowMs()
            val reasonCode = when (decision) {
                PersistedApprovalDecision.APPROVED -> "approval_granted"
                PersistedApprovalDecision.ANSWERED -> "approval_answered"
                PersistedApprovalDecision.DENIED -> "approval_denied"
            }
            if (approvalDao.resolveCas(
                    approvalId = projection.approvalId,
                    expectedVersion = projection.stateVersion,
                    nextVersion = resolutionVersion,
                    status = desiredStatus.name,
                    resolvedAtMs = resolvedAt,
                    resolutionReason = reasonCode,
                    resolutionRequestId = resolutionRequestId.take(MAX_ID_CHARS),
                ) != 1
            ) {
                return@withTransaction ApprovalResolutionResult.Conflict("approval_cas_conflict")
            }

            val execution = executionRepository.get(projection.executionId)
                ?: error("approval_execution_missing")
            val target = if (decision == PersistedApprovalDecision.DENIED) {
                ExecutionStatus.cancelled
            } else {
                ExecutionStatus.starting
            }
            val executionResult = executionRepository.mutateObserved(
                ExecutionMutation(
                    executionId = execution.id,
                    mutationId = "approval-resolve:${resolutionRequestId.take(MAX_ID_CHARS)}",
                    expectedVersion = execution.stateVersion,
                    source = ExecutionStateSource.USER,
                    reasonCode = reasonCode,
                    targetStatus = target,
                    verificationState = VerificationState.DATABASE_CONFIRMED,
                    cancellationResult = "approval_denied".takeIf {
                        decision == PersistedApprovalDecision.DENIED
                    },
                ),
            )
            checkExecutionMutation(executionResult, target)
            conversationRepository.persistConversationInCurrentTransaction(
                updatedConversation,
                insert = false,
            )
            committed = true
            ApprovalResolutionResult.Applied(
                projection.copy(
                    status = desiredStatus.name,
                    stateVersion = resolutionVersion,
                    resolvedAtMs = resolvedAt,
                    resolutionReason = reasonCode,
                    resolutionRequestId = resolutionRequestId.take(MAX_ID_CHARS),
                ),
            )
        }
        if (committed) {
            refreshSearchProjection(updatedConversation)
            retentionManager.requestCleanup(includeGlobalRetention = true)
        }
        return result
    }

    /**
     * Ends every pending approval for one conversation without replaying any tool. This is used
     * for recovery invalidation, conversation reset/target removal, permission revocation, and
     * Emergency Stop. A missing conversation graph is permitted during recovery.
     */
    suspend fun invalidateConversation(
        conversationId: String,
        conversation: Conversation?,
        reasonCode: String,
        orphaned: Boolean,
        source: ExecutionStateSource,
    ): Conversation? {
        val safeReason = reasonCode.take(160)
        val projections = approvalDao.getPendingForConversation(conversationId)
        val updatedConversation = conversation?.copy(
            messageNodes = conversation.messageNodes.map { node ->
                node.copy(messages = node.messages.map { message ->
                    message.copy(parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.approvalState is ToolApprovalState.Pending) {
                            part.copy(approvalState = ToolApprovalState.Denied(safeReason))
                        } else {
                            part
                        }
                    })
                })
            },
        )
        if (projections.isEmpty() && updatedConversation == conversation) return conversation
        val now = nowMs()
        database.withTransaction {
            projections.forEach { projection ->
                val requestId = "invalidate:${projection.approvalId}:$safeReason".take(MAX_ID_CHARS)
                check(approvalDao.resolveCas(
                    approvalId = projection.approvalId,
                    expectedVersion = projection.stateVersion,
                    nextVersion = projection.stateVersion + 1,
                    status = ApprovalStatus.INVALIDATED.name,
                    resolvedAtMs = now,
                    resolutionReason = safeReason,
                    resolutionRequestId = requestId,
                ) == 1) { "approval_invalidation_cas_conflict" }
                val execution = executionRepository.get(projection.executionId)
                if (execution != null && !ExecutionStatus.fromWire(execution.status).isTerminal) {
                    val target = if (orphaned) ExecutionStatus.orphaned else ExecutionStatus.cancelled
                    checkExecutionMutation(
                        executionRepository.mutateObserved(
                            ExecutionMutation(
                                executionId = execution.id,
                                mutationId = requestId,
                                expectedVersion = execution.stateVersion,
                                source = source,
                                reasonCode = safeReason,
                                targetStatus = target,
                                verificationState = VerificationState.DATABASE_CONFIRMED,
                                cancellationResult = safeReason.takeIf { !orphaned },
                            ),
                        ),
                        target,
                    )
                }
            }
            updatedConversation?.let {
                conversationRepository.persistConversationInCurrentTransaction(it, insert = false)
            }
        }
        updatedConversation?.let { refreshSearchProjection(it) }
        retentionManager.requestCleanup(includeGlobalRetention = true)
        return updatedConversation
    }

    suspend fun invalidateProjection(
        projection: PendingToolApprovalRecord,
        conversation: Conversation?,
        reasonCode: String,
        orphaned: Boolean,
        source: ExecutionStateSource,
    ): Conversation? {
        if (ApprovalStatus.fromWire(projection.status) != ApprovalStatus.PENDING) return conversation
        val safeReason = reasonCode.take(160)
        val updatedConversation = conversation?.copy(
            messageNodes = conversation.messageNodes.map { node ->
                node.copy(messages = node.messages.map { message ->
                    message.copy(parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool &&
                            part.toolCallId == projection.toolCallId &&
                            part.approvalState is ToolApprovalState.Pending
                        ) {
                            part.copy(approvalState = ToolApprovalState.Denied(safeReason))
                        } else {
                            part
                        }
                    })
                })
            },
        )
        val now = nowMs()
        database.withTransaction {
            val requestId = "invalidate:${projection.approvalId}:$safeReason".take(MAX_ID_CHARS)
            check(approvalDao.resolveCas(
                approvalId = projection.approvalId,
                expectedVersion = projection.stateVersion,
                nextVersion = projection.stateVersion + 1,
                status = ApprovalStatus.INVALIDATED.name,
                resolvedAtMs = now,
                resolutionReason = safeReason,
                resolutionRequestId = requestId,
            ) == 1) { "approval_invalidation_cas_conflict" }
            val execution = executionRepository.get(projection.executionId)
            if (execution != null && !ExecutionStatus.fromWire(execution.status).isTerminal) {
                val target = if (orphaned) ExecutionStatus.orphaned else ExecutionStatus.cancelled
                checkExecutionMutation(
                    executionRepository.mutateObserved(
                        ExecutionMutation(
                            executionId = execution.id,
                            mutationId = requestId,
                            expectedVersion = execution.stateVersion,
                            source = source,
                            reasonCode = safeReason,
                            targetStatus = target,
                            verificationState = VerificationState.DATABASE_CONFIRMED,
                            cancellationResult = safeReason.takeIf { !orphaned },
                        ),
                    ),
                    target,
                )
            }
            updatedConversation?.let {
                conversationRepository.persistConversationInCurrentTransaction(it, insert = false)
            }
        }
        updatedConversation?.let { refreshSearchProjection(it) }
        retentionManager.requestCleanup(includeGlobalRetention = true)
        return updatedConversation
    }

    suspend fun invalidateAllPending(
        reasonCode: String,
        orphaned: Boolean,
        source: ExecutionStateSource,
    ): List<Conversation> {
        val conversationIds = approvalDao.getAllPending()
            .map { it.conversationId }
            .distinct()
        return buildList {
            for (conversationId in conversationIds) {
                val conversation = runCatching {
                    conversationRepository.getConversationById(kotlin.uuid.Uuid.parse(conversationId))
                }.getOrNull()
                invalidateConversation(
                    conversationId = conversationId,
                    conversation = conversation,
                    reasonCode = reasonCode,
                    orphaned = orphaned,
                    source = source,
                )?.let(::add)
            }
        }
    }

    private suspend fun refreshSearchProjection(conversation: Conversation) {
        runCatching { conversationRepository.refreshSearchProjection(conversation) }
            .onFailure { error -> Log.w(TAG, "approval FTS refresh failed", error) }
    }

    private fun checkExecutionMutation(
        result: ExecutionMutationResult,
        target: ExecutionStatus,
    ) {
        when (result) {
            is ExecutionMutationResult.Applied -> Unit
            is ExecutionMutationResult.Duplicate -> check(
                ExecutionStatus.fromWire(result.record.status) == target,
            ) { "approval_execution_duplicate_conflict" }
            is ExecutionMutationResult.Terminal -> check(
                ExecutionStatus.fromWire(result.record.status) == target,
            ) { "approval_execution_terminal_conflict" }
            is ExecutionMutationResult.Missing -> error("approval_execution_missing")
            is ExecutionMutationResult.Invalid -> error("approval_execution_transition_invalid")
            is ExecutionMutationResult.Conflict -> error("approval_execution_cas_conflict")
        }
    }

    private fun runtimeFor(toolName: String): ExecutionRuntime = when {
        toolName.startsWith("termux_") || toolName.startsWith("linux_") -> ExecutionRuntime.TERMUX
        toolName.startsWith("ssh_") -> ExecutionRuntime.SSH
        toolName.startsWith("workspace_") -> ExecutionRuntime.WORKSPACE
        toolName.startsWith("mcp__") -> ExecutionRuntime.MCP
        toolName.startsWith("plugin__") -> ExecutionRuntime.PLUGIN
        toolName.startsWith("privileged_") || toolName.startsWith("external_bridge_") ->
            ExecutionRuntime.SHIZUKU
        else -> ExecutionRuntime.LOCAL_TOOL
    }

    private fun approvalId(executionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(executionId.toByteArray())
        return "approval:" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "SecondUserApproval"
        const val MAX_ID_CHARS = 480
        const val MAX_SUBJECT_CHARS = 160
        const val MAX_CAPABILITY_CHARS = 500
        const val MAX_CATEGORY_CHARS = 80
    }
}
