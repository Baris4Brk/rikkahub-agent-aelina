package me.rerere.rikkahub.data.authority.source

import kotlin.uuid.Uuid

private const val MAX_SOURCE_AUTHORITY_ID_CHARS = 256

enum class ConversationSourceScopeKind {
    ASSISTANT,
    AUTHORITY_SUBJECT,
}

data class ConversationSourceScope(
    val kind: ConversationSourceScopeKind,
    val id: String,
) {
    init {
        require(id.isSourceAuthorityIdentifier()) { "Invalid Conversation source scope ID" }
        require(
            kind != ConversationSourceScopeKind.ASSISTANT ||
                runCatching { Uuid.parse(id) }.isSuccess,
        ) { "Assistant Conversation source scope requires a UUID" }
    }

    override fun toString(): String = "ConversationSourceScope(kind=$kind, id=<redacted>)"
}

enum class ConversationSourceState {
    ACTIVE,
    SUPERSEDED,
    TOMBSTONED,
}

enum class ConversationSourceChangeKind {
    CREATED,
    UPDATED,
    BRANCH_SELECTED,
    BRANCH_SUPERSEDED,
    DELETED,
    CONVERSATION_DELETED,
}

data class ConversationSourceAuthorityHead(
    val scope: ConversationSourceScope,
    val conversationId: String,
    val assistantIdSnapshot: String,
    val sourceRevision: Long,
    val previousSourceRevision: Long?,
    val sourceState: ConversationSourceState,
    val changeKind: ConversationSourceChangeKind,
    val branchHeadMessageId: String?,
    val branchHeadMessageRevision: Long?,
    val occurredAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(conversationId.isSourceAuthorityIdentifier()) {
            "Invalid Conversation source conversation ID"
        }
        require(assistantIdSnapshot.isSourceAuthorityIdentifier()) {
            "Invalid Conversation source assistant ID"
        }
        requireContiguousSourceRevision(sourceRevision, previousSourceRevision)
        require((branchHeadMessageId == null) == (branchHeadMessageRevision == null)) {
            "Conversation branch head requires an exact ID/revision pair"
        }
        require(branchHeadMessageId == null || branchHeadMessageId.isSourceAuthorityIdentifier()) {
            "Invalid Conversation branch head message ID"
        }
        require(branchHeadMessageRevision == null || branchHeadMessageRevision > 0L) {
            "Invalid Conversation branch head message revision"
        }
        require(occurredAtMs >= 0L && updatedAtMs >= occurredAtMs) {
            "Invalid Conversation source authority time"
        }
        require(sourceState != ConversationSourceState.TOMBSTONED || branchHeadMessageId == null) {
            "A tombstoned Conversation cannot retain a branch head"
        }
    }

    override fun toString(): String =
        "ConversationSourceAuthorityHead(scope=${scope.kind}, revision=$sourceRevision, " +
            "state=$sourceState, branch=${branchHeadMessageId != null}, ids=<redacted>)"
}

data class MessageSourceAuthorityHead(
    val scope: ConversationSourceScope,
    val conversationId: String,
    val messageId: String,
    val messageRole: String,
    val sourceRevision: Long,
    val previousSourceRevision: Long?,
    val sourceState: ConversationSourceState,
    val changeKind: ConversationSourceChangeKind,
    /** Integrity check only. It is deliberately not exposed as a source revision. */
    val payloadIntegritySha256: String?,
    val occurredAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(conversationId.isSourceAuthorityIdentifier()) {
            "Invalid message source conversation ID"
        }
        require(messageId.isSourceAuthorityIdentifier()) { "Invalid message source ID" }
        require(messageRole in setOf("USER", "ASSISTANT", "SYSTEM", "TOOL")) {
            "Invalid message source role"
        }
        requireContiguousSourceRevision(sourceRevision, previousSourceRevision)
        require(payloadIntegritySha256 == null || payloadIntegritySha256.isLowerHexSha256()) {
            "Invalid message source integrity digest"
        }
        require(
            sourceState == ConversationSourceState.TOMBSTONED ||
                payloadIntegritySha256 != null,
        ) { "A live message source requires an integrity digest" }
        require(occurredAtMs >= 0L && updatedAtMs >= occurredAtMs) {
            "Invalid message source authority time"
        }
    }

    override fun toString(): String =
        "MessageSourceAuthorityHead(scope=${scope.kind}, role=$messageRole, " +
            "revision=$sourceRevision, state=$sourceState, digest=${payloadIntegritySha256 != null}, " +
            "ids=<redacted>)"
}

/** Content-free representation of a message graph snapshot. */
data class MessageSourceSnapshot(
    val messageId: String,
    val messageRole: String,
    val payloadIntegritySha256: String,
) {
    init {
        require(messageId.isSourceAuthorityIdentifier()) { "Invalid message snapshot ID" }
        require(messageRole in setOf("USER", "ASSISTANT", "SYSTEM", "TOOL")) {
            "Invalid message snapshot role"
        }
        require(payloadIntegritySha256.isLowerHexSha256()) {
            "Invalid message snapshot integrity digest"
        }
    }

    override fun toString(): String =
        "MessageSourceSnapshot(role=$messageRole, digest=<redacted>, id=<redacted>)"
}

/**
 * Exact graph state presented to the source writer inside the Conversation authority transaction.
 *
 * [selectedBranchMessageIds] is ordered from oldest to newest. Messages outside that list remain
 * durable branch alternatives and are recorded as SUPERSEDED, not deleted.
 */
data class ConversationSourceSnapshot(
    val scope: ConversationSourceScope,
    val conversationId: String,
    val assistantIdSnapshot: String,
    val messages: List<MessageSourceSnapshot>,
    val selectedBranchMessageIds: List<String>,
    val occurredAtMs: Long,
    val conversationDeleted: Boolean = false,
) {
    init {
        require(conversationId.isSourceAuthorityIdentifier()) {
            "Invalid Conversation snapshot ID"
        }
        require(assistantIdSnapshot.isSourceAuthorityIdentifier()) {
            "Invalid Conversation snapshot assistant ID"
        }
        require(occurredAtMs >= 0L) { "Negative Conversation snapshot time" }
        require(messages.map(MessageSourceSnapshot::messageId).toSet().size == messages.size) {
            "Duplicate message ID in Conversation source snapshot"
        }
        require(selectedBranchMessageIds.toSet().size == selectedBranchMessageIds.size) {
            "Duplicate selected message ID in Conversation source snapshot"
        }
        val allIds = messages.mapTo(hashSetOf(), MessageSourceSnapshot::messageId)
        require(selectedBranchMessageIds.all(allIds::contains)) {
            "Selected branch contains a message absent from the durable graph"
        }
        require(!conversationDeleted || messages.isEmpty()) {
            "Deleted Conversation snapshot cannot retain messages"
        }
        require(!conversationDeleted || selectedBranchMessageIds.isEmpty()) {
            "Deleted Conversation snapshot cannot retain a selected branch"
        }
    }

    val branchHeadMessageId: String?
        get() = selectedBranchMessageIds.lastOrNull()

    override fun toString(): String =
        "ConversationSourceSnapshot(scope=${scope.kind}, messages=${messages.size}, " +
            "selected=${selectedBranchMessageIds.size}, deleted=$conversationDeleted, " +
            "ids=<redacted>)"
}

/** Main-database port. Every method is called only while the owning Room transaction is open. */
interface ConversationSourceAuthorityStore {
    suspend fun findConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): ConversationSourceAuthorityHead?

    suspend fun insertConversationInitial(head: ConversationSourceAuthorityHead): Boolean

    suspend fun updateConversationFenced(
        expectedRevision: Long,
        head: ConversationSourceAuthorityHead,
    ): Boolean

    /** Counts only scopes which are not already tombstoned and still require privacy deletion. */
    suspend fun countConversationScopes(conversationId: String): Int

    suspend fun listConversationScopesAfter(
        conversationId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<ConversationSourceAuthorityHead>

    suspend fun findMessage(
        scope: ConversationSourceScope,
        messageId: String,
    ): MessageSourceAuthorityHead?

    suspend fun insertMessageInitial(head: MessageSourceAuthorityHead): Boolean

    suspend fun updateMessageFenced(
        expectedRevision: Long,
        head: MessageSourceAuthorityHead,
    ): Boolean

    suspend fun countMessagesForConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): Int

    suspend fun listMessagesForConversationAfter(
        scope: ConversationSourceScope,
        conversationId: String,
        afterMessageId: String,
        limit: Int,
    ): List<MessageSourceAuthorityHead>
}

enum class SourceAuthorityObjectKind {
    CONVERSATION,
    MESSAGE,
}

data class SourceInvalidationAuthorityEvent(
    val scope: ConversationSourceScope,
    val conversationId: String,
    val objectKind: SourceAuthorityObjectKind,
    val sourceId: String,
    val sourceRevision: Long,
    val previousSourceRevision: Long,
    val conversationSourceRevision: Long,
    val sourceState: ConversationSourceState,
    val changeKind: ConversationSourceChangeKind,
    val occurredAtMs: Long,
) {
    init {
        require(conversationId.isSourceAuthorityIdentifier())
        require(sourceId.isSourceAuthorityIdentifier())
        require(
            objectKind != SourceAuthorityObjectKind.CONVERSATION || sourceId == conversationId,
        ) { "Conversation invalidation source ID must be the Conversation ID" }
        require(previousSourceRevision > 0L && sourceRevision == previousSourceRevision + 1L) {
            "Source invalidation must link adjacent revisions"
        }
        require(conversationSourceRevision > 0L) {
            "Source invalidation requires an exact Conversation source revision"
        }
        require(changeKind != ConversationSourceChangeKind.CREATED) {
            "Initial source creation is not an invalidation"
        }
        require(occurredAtMs >= 0L)
    }

    override fun toString(): String =
        "SourceInvalidationAuthorityEvent(scope=${scope.kind}, revision=$sourceRevision, " +
            "object=$objectKind, state=$sourceState, change=$changeKind, ids=<redacted>)"
}

interface SourceInvalidationAuthorityEventPort {
    /** Returns true only when a new outbox row was inserted by this authority transaction. */
    suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean

    /** Called only after the owning transaction commits successfully. */
    fun dispatchPostCommit(insertedOutbox: Boolean)
}

/** Optional adapter for a source invalidation outbox writer. */
fun interface SourceInvalidationAuthorityEventWriter {
    suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean
}

class DispatchingSourceInvalidationAuthorityEventPort(
    private val writer: SourceInvalidationAuthorityEventWriter,
    private val postCommitWake: () -> Unit,
) : SourceInvalidationAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean =
        writer.appendInCurrentTransaction(event)

    override fun dispatchPostCommit(insertedOutbox: Boolean) {
        if (insertedOutbox) runCatching(postCommitWake)
    }
}

object NoOpSourceInvalidationAuthorityEventPort : SourceInvalidationAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean = false
    override fun dispatchPostCommit(insertedOutbox: Boolean) = Unit
}

class ConversationSourceAuthorityConflictException(
    val reasonCode: String,
) : IllegalStateException(reasonCode)

private fun requireContiguousSourceRevision(revision: Long, previous: Long?) {
    require(revision > 0L) { "Source revision must be positive" }
    require(
        (revision == 1L && previous == null) ||
            (revision > 1L && previous == revision - 1L),
    ) { "Source revisions must be contiguous" }
}

internal fun String.isSourceAuthorityIdentifier(): Boolean =
    length in 1..MAX_SOURCE_AUTHORITY_ID_CHARS && all { char ->
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == '.' ||
            char == ':' ||
            char == '@'
    }

internal fun String.isLowerHexSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
