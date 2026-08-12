package me.rerere.rikkahub.learning.model

import kotlin.uuid.Uuid

const val LEARNING_STREAM_INIT_EVENT_ID_V1: String = "learning-stream-init:v1"

/** Increment whenever raw event/schema interpretation changes; persisted rows are reinterpreted. */
const val CURRENT_LEARNING_EVENT_INTERPRETATION_VERSION: Int = 2

/** Content-free protocol failures shared by producers, imported-row decoders and replay. */
enum class LearningEventContractViolation {
    INCOMPATIBLE_STREAM_SENTINEL,
    INVALID_STREAM_SENTINEL,
    MISSING_SOURCE,
    STREAM_MISMATCH,
    INVALID_SOURCE_TYPE,
    SOURCE_TYPE_MISMATCH,
    INVALID_MISSING_REVISION_REASON,
    MISSING_REVISION_REASON_MISMATCH,
    INVALID_TERMINAL_STATE,
    TERMINAL_STATE_REQUIRED,
    TERMINAL_STATE_FORBIDDEN,
    EVENT_SOURCE_MISMATCH,
    COMMAND_CORRELATION_REQUIRED,
    EXECUTION_CORRELATION_REQUIRED,
    SOURCE_TRANSITION_REQUIRED,
    SOURCE_TRANSITION_FORBIDDEN,
    COMMAND_AUTHORITY_REVISION_REQUIRED,
    COMMAND_COMPLETION_REQUIRED,
    MESSAGE_REVISION_PAIR_REQUIRED,
    EXECUTION_TOOL_IDENTITY_INCOMPLETE,
}

/**
 * The single structural contract for the narrow Learning handoff.
 *
 * Unknown business event/source codes may be retained as typed, bounded metadata, but they never
 * satisfy the known event/source matrix and therefore never create work. The stream sentinel is
 * stricter: an incompatible sentinel schema cannot safely define database lineage.
 */
object LearningEventContract {
    fun violationOrNull(
        streamId: Uuid,
        eventCode: LearningEventCode,
        source: LearningSourceRef?,
        sourceTypeCode: String?,
        missingRevisionReasonCode: String?,
        terminalStateCode: String?,
        correlation: LearningCorrelation,
    ): LearningEventContractViolation? {
        val knownType = eventCode.knownType
        if (knownType == LearningEventType.STREAM_INIT) {
            if (
                eventCode.schemaVersion != 1 ||
                eventCode.decodeState != LearningEventDecodeState.KNOWN
            ) {
                return LearningEventContractViolation.INCOMPATIBLE_STREAM_SENTINEL
            }
            return if (
                source == null &&
                sourceTypeCode == null &&
                missingRevisionReasonCode == null &&
                terminalStateCode == null &&
                correlation == LearningCorrelation()
            ) {
                null
            } else {
                LearningEventContractViolation.INVALID_STREAM_SENTINEL
            }
        }

        val businessSource = source ?: return LearningEventContractViolation.MISSING_SOURCE
        if (businessSource.databaseStreamId != streamId) {
            return LearningEventContractViolation.STREAM_MISMATCH
        }
        val transitionFieldsPresent = correlation.previousSourceRevision != null ||
            correlation.sourceStateCode != null
        if (
            eventCode.schemaVersion >= 2 &&
            (correlation.messageId == null) != (correlation.messageRevision == null)
        ) {
            return LearningEventContractViolation.MESSAGE_REVISION_PAIR_REQUIRED
        }
        if (knownType == LearningEventType.SOURCE_INVALIDATED &&
            eventCode.decodeState == LearningEventDecodeState.KNOWN
        ) {
            if (
                correlation.previousSourceRevision == null ||
                correlation.sourceStateCode == null ||
                businessSource.sourceRevision == null ||
                correlation.previousSourceRevision >= businessSource.sourceRevision
            ) {
                return LearningEventContractViolation.SOURCE_TRANSITION_REQUIRED
            }
        } else if (
            eventCode.decodeState == LearningEventDecodeState.KNOWN && transitionFieldsPresent
        ) {
            return LearningEventContractViolation.SOURCE_TRANSITION_FORBIDDEN
        }
        val rawSourceType = sourceTypeCode
            ?: return LearningEventContractViolation.INVALID_SOURCE_TYPE
        if (!rawSourceType.isSafeLearningCode()) {
            return LearningEventContractViolation.INVALID_SOURCE_TYPE
        }
        if (
            businessSource.sourceKind != LearningSourceKind.UNKNOWN &&
            rawSourceType != businessSource.sourceKind.name
        ) {
            return LearningEventContractViolation.SOURCE_TYPE_MISMATCH
        }
        if (
            businessSource.sourceKind == LearningSourceKind.UNKNOWN &&
            LearningSourceKind.entries.any { it != LearningSourceKind.UNKNOWN && it.name == rawSourceType }
        ) {
            return LearningEventContractViolation.SOURCE_TYPE_MISMATCH
        }

        val missingReasonViolation = validateMissingRevisionReason(
            source = businessSource,
            rawCode = missingRevisionReasonCode,
        )
        if (missingReasonViolation != null) return missingReasonViolation

        if (terminalStateCode != null && !terminalStateCode.isSafeLearningCode()) {
            return LearningEventContractViolation.INVALID_TERMINAL_STATE
        }

        // An unknown/incompatible business event is retained without guessing its newer semantics.
        if (eventCode.decodeState != LearningEventDecodeState.KNOWN || knownType == null) return null

        val allowedTerminalStates = TERMINAL_STATES[knownType]
        if (allowedTerminalStates == null) {
            if (terminalStateCode != null) {
                return LearningEventContractViolation.TERMINAL_STATE_FORBIDDEN
            }
        } else {
            if (terminalStateCode == null) {
                return LearningEventContractViolation.TERMINAL_STATE_REQUIRED
            }
            if (terminalStateCode !in allowedTerminalStates) {
                return LearningEventContractViolation.INVALID_TERMINAL_STATE
            }
        }

        // A future source type is safe to retain, but cannot satisfy a current semantic matrix.
        if (businessSource.sourceKind == LearningSourceKind.UNKNOWN) return null
        if (!isExpectedSource(knownType, businessSource.sourceKind)) {
            return LearningEventContractViolation.EVENT_SOURCE_MISMATCH
        }
        return when (knownType) {
            LearningEventType.COMMAND_ADMITTED,
            LearningEventType.COMMAND_WAITING_APPROVAL,
            LearningEventType.COMMAND_TERMINAL -> if (
                correlation.commandId != businessSource.sourceId ||
                correlation.conversationId == null ||
                correlation.lineageId == null ||
                correlation.branchAnchorMessageId == null
            ) {
                LearningEventContractViolation.COMMAND_CORRELATION_REQUIRED
            } else if (
                eventCode.schemaVersion >= 2 &&
                (correlation.branchAnchorMessageRevision == null ||
                    correlation.conversationSourceRevision == null)
            ) {
                LearningEventContractViolation.COMMAND_AUTHORITY_REVISION_REQUIRED
            } else if (
                eventCode.schemaVersion >= 2 &&
                knownType != LearningEventType.COMMAND_ADMITTED &&
                correlation.completionKindCode == null
            ) {
                LearningEventContractViolation.COMMAND_COMPLETION_REQUIRED
            } else if (
                (correlation.messageId == null) != (correlation.messageRevision == null)
            ) {
                LearningEventContractViolation.MESSAGE_REVISION_PAIR_REQUIRED
            } else {
                null
            }

            LearningEventType.EXECUTION_TERMINAL -> when {
                correlation.executionId == null ->
                    LearningEventContractViolation.EXECUTION_CORRELATION_REQUIRED
                eventCode.schemaVersion >= 2 &&
                    listOf(
                        correlation.toolCallId,
                        correlation.toolName,
                        correlation.toolSchemaFingerprint,
                    ).any { it == null } ->
                    LearningEventContractViolation.EXECUTION_TOOL_IDENTITY_INCOMPLETE
                else -> null
            }

            else -> null
        }
    }

    fun requireValid(
        streamId: Uuid,
        eventCode: LearningEventCode,
        source: LearningSourceRef?,
        sourceTypeCode: String?,
        missingRevisionReasonCode: String?,
        terminalStateCode: String?,
        correlation: LearningCorrelation,
    ) {
        val violation = violationOrNull(
            streamId = streamId,
            eventCode = eventCode,
            source = source,
            sourceTypeCode = sourceTypeCode,
            missingRevisionReasonCode = missingRevisionReasonCode,
            terminalStateCode = terminalStateCode,
            correlation = correlation,
        )
        require(violation == null) { "Invalid learning event contract: $violation" }
    }

    private fun validateMissingRevisionReason(
        source: LearningSourceRef,
        rawCode: String?,
    ): LearningEventContractViolation? {
        if (rawCode != null && !rawCode.isSafeLearningCode()) {
            return LearningEventContractViolation.INVALID_MISSING_REVISION_REASON
        }
        if ((source.sourceRevision == null) != (rawCode != null)) {
            return LearningEventContractViolation.MISSING_REVISION_REASON_MISMATCH
        }
        val typedReason = source.missingRevisionReason
        if (
            typedReason != null &&
            typedReason != MissingSourceRevisionReason.UNKNOWN &&
            rawCode != typedReason.name
        ) {
            return LearningEventContractViolation.MISSING_REVISION_REASON_MISMATCH
        }
        return null
    }

    private fun isExpectedSource(
        eventType: LearningEventType,
        sourceKind: LearningSourceKind,
    ): Boolean = when (eventType) {
        LearningEventType.STREAM_INIT -> false
        LearningEventType.COMMAND_ADMITTED,
        LearningEventType.COMMAND_WAITING_APPROVAL,
        LearningEventType.COMMAND_TERMINAL -> sourceKind == LearningSourceKind.COMMAND

        LearningEventType.EXECUTION_TERMINAL -> sourceKind == LearningSourceKind.EXECUTION_EVENT
        LearningEventType.USER_FEEDBACK_RECORDED -> sourceKind == LearningSourceKind.USER_FEEDBACK
        LearningEventType.SOURCE_INVALIDATED -> sourceKind != LearningSourceKind.UNKNOWN
        LearningEventType.TOOL_SCHEMA_CHANGED -> sourceKind == LearningSourceKind.TOOL_EXPERIENCE
        LearningEventType.WORKFLOW_TRIAL_TERMINAL -> sourceKind == LearningSourceKind.WORKFLOW_TRIAL
    }

    private val TERMINAL_STATES: Map<LearningEventType, Set<String>> = mapOf(
        LearningEventType.COMMAND_TERMINAL to setOf(
            "COMPLETED",
            "FAILED",
            "CANCELLED",
            "MANUAL_CONFIRMATION",
        ),
        LearningEventType.EXECUTION_TERMINAL to setOf(
            "SUCCEEDED",
            "FAILED",
            "CANCELLED",
            "TIMED_OUT",
            "ORPHANED",
            "UNKNOWN",
        ),
        LearningEventType.WORKFLOW_TRIAL_TERMINAL to setOf(
            "SUCCESS",
            "FAILED",
            "SKIPPED_CONDITIONS",
            "SKIPPED_COOLDOWN",
            "SKIPPED_DAILY_CAP",
            "SKIPPED_DISABLED",
        ),
    )
}

internal fun String.isSafeLearningCode(): Boolean =
    matches(Regex("[A-Z][A-Z0-9_]{0,63}"))
