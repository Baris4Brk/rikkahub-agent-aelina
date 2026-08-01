package me.rerere.rikkahub.owner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.owner.db.HostOperationDao
import me.rerere.rikkahub.owner.db.HostOperationEntity
import me.rerere.rikkahub.owner.db.HostOperationEventEntity
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import kotlin.uuid.Uuid

class OwnerOperationExecutor(
    private val dao: HostOperationDao,
    private val handler: OwnerOperationHandler,
    private val isEmergencyStopActive: suspend () -> Boolean,
    private val containsRuntimeSecret: (String) -> Boolean = { false },
    private val fingerprinter: OwnerOperationFingerprinter = Sha256OwnerOperationFingerprinter,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val lanes: OwnerOperationLanes = OwnerOperationLanes(),
) : OwnerOperationGateway {
    override suspend fun execute(
        request: OwnerOperationRequest,
        context: PrivilegedSessionContext,
    ): OwnerOperationResult = lanes.withOperation(request) {
        validateEnvelope(request, context)?.let { return@withOperation it }
        val summary = runCatching { actionSummary(request) }.getOrElse {
            return@withOperation failed(
                request,
                "OWNER_FINGERPRINT_UNAVAILABLE",
                "The secure idempotency fingerprint could not be created.",
            )
        }
        val existing = dao.get(request.requestId)
        if (existing != null) return@withOperation replay(existing, request, summary)

        val now = nowMs()
        val initial = HostOperationEntity(
            requestId = request.requestId,
            authoritySubjectId = request.authoritySubjectId,
            authorityEpoch = request.authorityEpoch,
            assistantId = request.assistantId,
            conversationId = request.conversationId,
            modelId = request.modelId,
            providerId = request.providerId,
            toolFamily = request.family.name,
            actionSummaryJson = summary,
            state = OwnerOperationState.VALIDATING.name,
            stateVersion = 0,
            recoveryCode = null,
            resultCode = null,
            createdAtMs = now,
            updatedAtMs = now,
            completedAtMs = null,
        )
        val inserted = dao.insertOperationWithInitialEvent(
            initial,
            HostOperationEventEntity(
                eventId = Uuid.random().toString(),
                requestId = request.requestId,
                sequence = 0,
                previousState = null,
                nextState = OwnerOperationState.VALIDATING.name,
                actionIndex = null,
                actionType = null,
                reasonCode = "OWNER_REQUEST_ACCEPTED",
                createdAtMs = now,
            ),
        )
        if (!inserted) {
            val concurrent = dao.get(request.requestId)
                ?: return@withOperation failed(request, "OWNER_REQUEST_RACE", "Operation could not be claimed.")
            return@withOperation replay(concurrent, request, summary)
        }

        transition(request.requestId, OwnerOperationState.APPLYING, reasonCode = "ENVELOPE_VALIDATION_COMPLETE")
            ?: return@withOperation failed(request, "OWNER_LEDGER_CONFLICT", "Operation state changed concurrently.")

        val applied = mutableListOf<Pair<OwnerAction, OwnerAppliedAction>>()
        try {
            for ((index, action) in request.actions.withIndex()) {
                revalidateAuthority(request, context)?.let { invalid ->
                    return@withOperation compensateAndFinish(request, context, applied, invalid.code, invalid.message)
                }
                if (isEmergencyStopActive()) {
                    return@withOperation compensateAndFinish(
                        request, context, applied, "EMERGENCY_STOP_ACTIVE", "Emergency Stop interrupted the operation.",
                    )
                }
                val validation = handler.validate(request, action, context)
                if (!validation.ok) {
                    return@withOperation compensateAndFinish(
                        request,
                        context,
                        applied,
                        validation.code,
                        validation.message,
                    )
                }
                val result = handler.apply(index, request, action, context)
                if (!result.result.ok) {
                    return@withOperation compensateAndFinish(
                        request, context, applied, result.result.code, result.result.message,
                    )
                }
                applied += action to result
                transition(
                    request.requestId,
                    OwnerOperationState.APPLYING,
                    actionIndex = index,
                    actionType = action.type,
                    reasonCode = "ACTION_APPLIED",
                ) ?: return@withOperation compensateAndFinish(
                    request, context, applied, "OWNER_LEDGER_CONFLICT", "Operation state changed concurrently.",
                )
            }

            if (isEmergencyStopActive()) {
                return@withOperation compensateAndFinish(
                    request,
                    context,
                    applied,
                    "EMERGENCY_STOP_ACTIVE",
                    "Emergency Stop interrupted the operation before verification.",
                )
            }
            transition(request.requestId, OwnerOperationState.VERIFYING, reasonCode = "APPLY_COMPLETE")
                ?: return@withOperation compensateAndFinish(
                    request, context, applied, "OWNER_LEDGER_CONFLICT", "Operation state changed concurrently.",
                )
            for ((index, pair) in applied.withIndex()) {
                val (action, result) = pair
                revalidateAuthority(request, context)?.let { invalid ->
                    return@withOperation compensateAndFinish(request, context, applied, invalid.code, invalid.message)
                }
                if (isEmergencyStopActive()) {
                    return@withOperation compensateAndFinish(
                        request,
                        context,
                        applied,
                        "EMERGENCY_STOP_ACTIVE",
                        "Emergency Stop interrupted operation verification.",
                    )
                }
                val check = handler.verify(request, action, result, context)
                if (!check.ok) {
                    return@withOperation compensateAndFinish(request, context, applied, check.code, check.message)
                }
                transition(
                    request.requestId,
                    OwnerOperationState.VERIFYING,
                    actionIndex = index,
                    actionType = action.type,
                    reasonCode = "ACTION_VERIFIED",
                ) ?: return@withOperation compensateAndFinish(
                    request, context, applied, "OWNER_LEDGER_CONFLICT", "Operation state changed concurrently.",
                )
            }
            transition(
                request.requestId,
                OwnerOperationState.COMMITTED,
                reasonCode = "OPERATION_COMMITTED",
                resultCode = "OWNER_OPERATION_COMMITTED",
                terminal = true,
            ) ?: return@withOperation compensateAndFinish(
                request, context, applied, "OWNER_LEDGER_CONFLICT", "Operation state changed concurrently.",
            )
            runCatching { dao.trimTerminal() }
            val result = OwnerOperationResult(
                ok = true,
                requestId = request.requestId,
                state = OwnerOperationState.COMMITTED,
                code = "OWNER_OPERATION_COMMITTED",
                message = "All actions were applied and verified.",
                actions = applied.map { it.second.result },
            )
            closeReceipts(applied)
            result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                compensateAndFinish(request, context, applied, "OWNER_OPERATION_CANCELLED", "Operation was cancelled.")
            }
            throw cancelled
        } catch (_: Throwable) {
            compensateAndFinish(request, context, applied, "OWNER_OPERATION_FAILED", "Operation failed inside the host runtime.")
        }
    }

    private suspend fun compensateAndFinish(
        request: OwnerOperationRequest,
        context: PrivilegedSessionContext,
        applied: List<Pair<OwnerAction, OwnerAppliedAction>>,
        code: String,
        message: String,
    ): OwnerOperationResult {
        if (applied.isEmpty()) return terminalFailure(request, code, message)
        transition(request.requestId, OwnerOperationState.COMPENSATING, reasonCode = code)
        var allCompensated = true
        for ((action, result) in applied.asReversed()) {
            val compensation = runCatching {
                handler.compensate(request, action, result, context)
            }.getOrElse { OwnerCompensationResult(false, "COMPENSATION_FAILED") }
            closeReceipt(result)
            allCompensated = allCompensated && compensation.compensated
            transition(
                request.requestId,
                OwnerOperationState.COMPENSATING,
                actionIndex = result.result.index,
                actionType = action.type,
                reasonCode = compensation.code,
            )
        }
        val finalState = if (allCompensated) OwnerOperationState.ROLLED_BACK else OwnerOperationState.PARTIAL
        transition(
            request.requestId,
            finalState,
            recoveryCode = if (allCompensated) null else "MANUAL_FACT_CHECK_REQUIRED",
            resultCode = code,
            reasonCode = code,
            terminal = true,
        )
        return OwnerOperationResult(
            ok = false,
            requestId = request.requestId,
            state = finalState,
            code = if (allCompensated) "OWNER_OPERATION_ROLLED_BACK" else "OWNER_OPERATION_PARTIAL",
            message = message.take(500),
            actions = applied.map { it.second.result },
        )
    }

    private fun closeReceipts(applied: List<Pair<OwnerAction, OwnerAppliedAction>>) {
        applied.forEach { closeReceipt(it.second) }
    }

    private fun closeReceipt(applied: OwnerAppliedAction) {
        (applied.compensationReceipt as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private suspend fun terminalFailure(
        request: OwnerOperationRequest,
        code: String,
        message: String,
    ): OwnerOperationResult {
        transition(
            request.requestId,
            OwnerOperationState.FAILED,
            resultCode = code,
            reasonCode = code,
            terminal = true,
        )
        return failed(request, code, message)
    }

    private suspend fun transition(
        requestId: String,
        next: OwnerOperationState,
        recoveryCode: String? = null,
        resultCode: String? = null,
        actionIndex: Int? = null,
        actionType: String? = null,
        reasonCode: String? = null,
        terminal: Boolean = false,
    ): HostOperationEntity? {
        val current = dao.get(requestId) ?: return null
        val now = nowMs()
        val changed = dao.transition(
            requestId = requestId,
            expectedState = current.state,
            expectedVersion = current.stateVersion,
            nextState = next.name,
            recoveryCode = recoveryCode,
            resultCode = resultCode,
            actionIndex = actionIndex,
            actionType = actionType,
            reasonCode = reasonCode,
            eventId = Uuid.random().toString(),
            createdAtMs = now,
            completedAtMs = now.takeIf { terminal },
        )
        return if (changed) dao.get(requestId) else null
    }

    private fun validateEnvelope(
        request: OwnerOperationRequest,
        context: PrivilegedSessionContext,
    ): OwnerOperationResult? {
        if (!REQUEST_ID.matches(request.requestId)) {
            return failed(request, "OWNER_REQUEST_ID_INVALID", "request_id must be a stable 8-128 character identifier.")
        }
        if (request.actions.size !in 1..20) {
            return failed(request, "OWNER_ACTION_COUNT_INVALID", "An Owner call requires 1-20 actions.")
        }
        if (request.family != OwnerToolFamily.SECRET &&
            request.actions.any { containsRuntimeSecret(it.arguments.toString()) }
        ) {
            return failed(
                request,
                "SECRET_EGRESS_DENIED",
                "Known secret material cannot be stored in ordinary Owner action arguments; use a Vault reference.",
            )
        }
        return revalidateAuthority(request, context)
    }

    private fun revalidateAuthority(
        request: OwnerOperationRequest,
        context: PrivilegedSessionContext,
    ): OwnerOperationResult? {
        val active = SecondUserAuthorityRegistry.current()
        val matches = active != null &&
            active.subjectId == request.authoritySubjectId &&
            active.authorityEpoch == request.authorityEpoch &&
            active.assistantId.toString() == request.assistantId &&
            active.conversationId.toString() == request.conversationId &&
            context.authoritySubjectId == request.authoritySubjectId &&
            context.authorityEpoch == request.authorityEpoch &&
            context.assistantId.toString() == request.assistantId &&
            context.conversationId.toString() == request.conversationId &&
            SecondUserAuthorityRegistry.matches(active.subjectId, context.conversationId, context.origin)
        return if (matches) null else failed(
            request,
            "SECOND_USER_AUTHORITY_STALE",
            "The Owner authority, epoch, conversation, or trusted local origin no longer matches.",
        )
    }

    private fun replay(
        existing: HostOperationEntity,
        request: OwnerOperationRequest,
        summary: String,
    ): OwnerOperationResult {
        if (existing.authoritySubjectId != request.authoritySubjectId ||
            existing.toolFamily != request.family.name ||
            existing.actionSummaryJson != summary
        ) {
            return failed(request, "OWNER_REQUEST_ID_CONFLICT", "request_id already belongs to a different operation.")
        }
        val state = runCatching { OwnerOperationState.valueOf(existing.state) }
            .getOrDefault(OwnerOperationState.NEEDS_ATTENTION)
        return OwnerOperationResult(
            ok = state == OwnerOperationState.COMMITTED,
            requestId = request.requestId,
            state = state,
            code = existing.resultCode ?: if (state == OwnerOperationState.COMMITTED) {
                "OWNER_OPERATION_ALREADY_COMMITTED"
            } else {
                "OWNER_OPERATION_ALREADY_RECORDED"
            },
            message = "The idempotent request already exists; no action was replayed.",
            replayed = true,
        )
    }

    private fun actionSummary(request: OwnerOperationRequest): String =
        kotlinx.serialization.json.buildJsonObject {
            put("request_fingerprint", fingerprinter.fingerprint(request))
            put("actions", buildJsonArray {
                request.actions.forEachIndexed { index, action ->
                    addJsonObject {
                        put("index", index)
                        put("type", action.type.take(80))
                        put("risk", action.risk.name)
                    }
                }
            })
        }.toString()

    private fun failed(request: OwnerOperationRequest, code: String, message: String) =
        OwnerOperationResult(
            ok = false,
            requestId = request.requestId,
            state = OwnerOperationState.FAILED,
            code = code,
            message = message.take(500),
        )

    private companion object {
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
    }
}

class OwnerOperationBootRecovery(
    private val dao: HostOperationDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Never replays a side effect. It leaves a durable fact-check marker for domain recovery. */
    suspend fun recover(): Int {
        var count = 0
        for (record in dao.getRecoverable()) {
            if (dao.transition(
                    requestId = record.requestId,
                    expectedState = record.state,
                    expectedVersion = record.stateVersion,
                    nextState = OwnerOperationState.NEEDS_ATTENTION.name,
                    recoveryCode = "PROCESS_RESTART_FACT_CHECK_REQUIRED",
                    resultCode = record.resultCode,
                    actionIndex = null,
                    actionType = null,
                    reasonCode = "PROCESS_RESTART_NO_BLIND_REPLAY",
                    eventId = Uuid.random().toString(),
                    createdAtMs = nowMs(),
                    completedAtMs = nowMs(),
                )
            ) count++
        }
        return count
    }
}

/** Opens exactly one cross-pillar parent ledger row for each new idempotent Owner request. */
class AgentRunOwnerOperationGateway(
    private val delegate: OwnerOperationGateway,
    private val operations: HostOperationDao,
    private val runs: AgentRunRepository,
    private val lanes: OwnerOperationLanes = OwnerOperationLanes(),
) : OwnerOperationGateway {
    override suspend fun execute(
        request: OwnerOperationRequest,
        context: PrivilegedSessionContext,
    ): OwnerOperationResult = lanes.withRequestId(request.requestId) {
        if (operations.get(request.requestId) != null) {
            return@withRequestId delegate.execute(request, context)
        }
        val runId = runs.open(
            kind = AgentRunKind.OwnerHost,
            domainId = request.requestId,
            metadata = kotlinx.serialization.json.buildJsonObject {
                put("family", request.family.name)
                put("action_count", request.actions.size)
            },
        )
        val result = try {
            delegate.execute(request, context)
        } catch (cancelled: CancellationException) {
            runs.markTerminal(runId, AgentRunStatus.cancelled, "owner_operation_cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            runs.markTerminal(runId, AgentRunStatus.failed, "owner_operation_failed")
            throw failure
        }
        val terminal = when (result.state) {
            OwnerOperationState.COMMITTED -> AgentRunStatus.succeeded
            OwnerOperationState.ROLLED_BACK -> AgentRunStatus.cancelled
            else -> AgentRunStatus.failed
        }
        runs.markTerminal(runId, terminal, result.code.takeIf { !result.ok })
        result
    }
}
