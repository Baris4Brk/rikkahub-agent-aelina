package me.rerere.rikkahub.learning.model

import kotlin.uuid.Uuid

private const val MAX_AUTHORITY_SUBJECT_ID_CHARS = 160
private const val MAX_SOURCE_ID_CHARS = 256
private const val MAX_EVENT_CODE_CHARS = 64

enum class LearningScopeKind {
    ASSISTANT,
    AUTHORITY_SUBJECT,
}

/**
 * Authorization boundary for derived learning data.
 *
 * This is deliberately separate from the memory scope string. In particular, there is no GLOBAL
 * learning scope and `useGlobalMemory` must never widen a policy.
 */
sealed interface LearningScope {
    val kind: LearningScopeKind
    val storageId: String

    data class Assistant(val assistantId: Uuid) : LearningScope {
        override val kind: LearningScopeKind = LearningScopeKind.ASSISTANT
        override val storageId: String = assistantId.toString()

        override fun toString(): String = "LearningScope.Assistant(<redacted>)"
    }

    data class AuthoritySubject(val authoritySubjectId: String) : LearningScope {
        init {
            require(isSafeLearningIdentifier(authoritySubjectId, MAX_AUTHORITY_SUBJECT_ID_CHARS)) {
                "Invalid authority subject identifier"
            }
        }

        override val kind: LearningScopeKind = LearningScopeKind.AUTHORITY_SUBJECT
        override val storageId: String = authoritySubjectId

        override fun toString(): String = "LearningScope.AuthoritySubject(<redacted>)"
    }

    companion object {
        fun parseOrNull(kind: String, id: String): LearningScope? = when (kind) {
            LearningScopeKind.ASSISTANT.name -> runCatching { Assistant(Uuid.parse(id)) }.getOrNull()
            LearningScopeKind.AUTHORITY_SUBJECT.name ->
                runCatching { AuthoritySubject(id) }.getOrNull()
            else -> null
        }
    }
}

data class LearningApplicabilityRequest(
    val assistantId: Uuid,
    val authoritySubjectId: String?,
    val authorityPolicyOptIn: Boolean,
    val grantConsumingAssistantId: Uuid?,
) {
    override fun toString(): String =
        "LearningApplicabilityRequest(authoritySubject=${authoritySubjectId != null}, " +
            "optIn=$authorityPolicyOptIn, grant=${grantConsumingAssistantId != null}, " +
            "ids=<redacted>)"
}

object LearningScopePolicy {
    fun isCandidateAllowed(
        policyScope: LearningScope,
        request: LearningApplicabilityRequest,
    ): Boolean = when (policyScope) {
        is LearningScope.Assistant -> policyScope.assistantId == request.assistantId
        is LearningScope.AuthoritySubject ->
            request.authorityPolicyOptIn &&
                request.authoritySubjectId == policyScope.authoritySubjectId &&
                request.grantConsumingAssistantId == request.assistantId
    }
}

enum class LearningSourceKind {
    COMMAND,
    CONVERSATION,
    CONVERSATION_MESSAGE,
    EXECUTION_EVENT,
    TOOL_EXPERIENCE,
    USER_FEEDBACK,
    WORKFLOW_TRIAL,
    UNKNOWN,
}

enum class MissingSourceRevisionReason {
    AUTHORITY_HAS_NO_REVISION,
    LEGACY_IMPORT,
    RETENTION_GAP,
    UNKNOWN,
}

data class LearningSourceRef(
    val sourceKind: LearningSourceKind,
    val sourceId: String,
    val sourceRevision: Long?,
    val missingRevisionReason: MissingSourceRevisionReason?,
    val databaseStreamId: Uuid,
    val scope: LearningScope,
    val occurredAtMs: Long,
) {
    init {
        require(isSafeLearningIdentifier(sourceId, MAX_SOURCE_ID_CHARS)) {
            "Invalid learning source identifier"
        }
        require(sourceRevision == null || sourceRevision >= 0L) { "Negative source revision" }
        require((sourceRevision == null) == (missingRevisionReason != null)) {
            "A missing revision requires exactly one reason"
        }
        require(occurredAtMs >= 0L) { "Negative event time" }
    }

    val eligibleForPersistentPolicyEvidence: Boolean
        get() = sourceRevision != null && sourceKind in setOf(
            LearningSourceKind.CONVERSATION_MESSAGE,
            LearningSourceKind.TOOL_EXPERIENCE,
            LearningSourceKind.USER_FEEDBACK,
            LearningSourceKind.WORKFLOW_TRIAL,
        )

    override fun toString(): String =
        "LearningSourceRef(kind=$sourceKind, revisionKnown=${sourceRevision != null}, " +
            "scope=${scope.kind}, occurredAtMs=$occurredAtMs, ids=<redacted>)"
}

enum class LearningEventType(val firstSchemaVersion: Int, val producesP0Job: Boolean) {
    STREAM_INIT(1, false),
    COMMAND_ADMITTED(1, true),
    COMMAND_WAITING_APPROVAL(1, true),
    COMMAND_TERMINAL(1, true),
    EXECUTION_TERMINAL(1, true),
    USER_FEEDBACK_RECORDED(1, false),
    SOURCE_INVALIDATED(2, true),
    TOOL_SCHEMA_CHANGED(1, false),
    WORKFLOW_TRIAL_TERMINAL(1, false),
}

enum class LearningEventDecodeState {
    KNOWN,
    UNKNOWN_NO_JOB,
    INCOMPATIBLE_SCHEMA,
}

/** Preserves the original event code/version so a newer consumer can reinterpret it later. */
data class LearningEventCode(
    val rawCode: String,
    val schemaVersion: Int,
) {
    init {
        require(schemaVersion > 0) { "Invalid event schema version" }
        require(isSafeEventCode(rawCode)) { "Invalid event code" }
    }

    val knownType: LearningEventType? = LearningEventType.entries.firstOrNull { it.name == rawCode }

    val decodeState: LearningEventDecodeState
        get() = when {
            knownType == null -> LearningEventDecodeState.UNKNOWN_NO_JOB
            schemaVersion < knownType.firstSchemaVersion -> LearningEventDecodeState.INCOMPATIBLE_SCHEMA
            schemaVersion > CURRENT_LEARNING_EVENT_SCHEMA_VERSION ->
                LearningEventDecodeState.INCOMPATIBLE_SCHEMA
            else -> LearningEventDecodeState.KNOWN
        }

    val producesJob: Boolean
        get() = decodeState == LearningEventDecodeState.KNOWN && knownType?.producesP0Job == true

    override fun toString(): String =
        "LearningEventCode(type=$knownType, schema=$schemaVersion, decode=$decodeState, " +
            "raw=<redacted>)"

    companion object {
        const val CURRENT_LEARNING_EVENT_SCHEMA_VERSION = 2
    }
}

data class LearningCorrelation(
    val previousSourceRevision: Long? = null,
    val sourceStateCode: String? = null,
    val conversationId: String? = null,
    val commandId: String? = null,
    val lineageId: String? = null,
    val parentCommandId: String? = null,
    val branchAnchorMessageId: String? = null,
    val branchAnchorMessageRevision: Long? = null,
    val conversationSourceRevision: Long? = null,
    val completionKindCode: String? = null,
    val generationRunId: String? = null,
    val executionId: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolSchemaFingerprint: String? = null,
    val messageId: String? = null,
    val messageRevision: Long? = null,
) {
    init {
        listOfNotNull(
            conversationId,
            commandId,
            lineageId,
            parentCommandId,
            branchAnchorMessageId,
            generationRunId,
            executionId,
            toolCallId,
            toolName,
            toolSchemaFingerprint,
            messageId,
        ).forEach { value ->
            require(isSafeLearningIdentifier(value, MAX_SOURCE_ID_CHARS)) {
                "Invalid learning correlation identifier"
            }
        }
        listOfNotNull(
            branchAnchorMessageRevision,
            conversationSourceRevision,
            messageRevision,
        ).forEach { revision -> require(revision > 0L) { "Invalid learning correlation revision" } }
        require(previousSourceRevision == null || previousSourceRevision > 0L) {
            "Invalid previous learning source revision"
        }
        require(sourceStateCode == null || sourceStateCode.isSafeLearningCodeShape()) {
            "Invalid Learning source state"
        }
        require(completionKindCode == null || completionKindCode.isSafeLearningCodeShape()) {
            "Invalid learning completion kind"
        }
        require((toolName == null) == (toolSchemaFingerprint == null)) {
            "Learning tool identity requires a name/fingerprint pair"
        }
        require(toolSchemaFingerprint == null || toolSchemaFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid learning tool schema fingerprint"
        }
    }

    override fun toString(): String =
        "LearningCorrelation(conversation=${conversationId != null}, command=${commandId != null}, " +
            "lineage=${lineageId != null}, execution=${executionId != null}, ids=<redacted>)"
}

private fun String.isSafeLearningCodeShape(): Boolean =
    matches(Regex("[A-Z][A-Z0-9_]{0,63}"))

private fun isSafeEventCode(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= MAX_EVENT_CODE_CHARS &&
        value.first() in 'A'..'Z' &&
        value.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }

internal fun isSafeLearningIdentifier(value: String, maxChars: Int): Boolean =
    value.isNotEmpty() &&
        value.length <= maxChars &&
        value.all { char ->
            char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == ':' ||
                char == '@'
        }
