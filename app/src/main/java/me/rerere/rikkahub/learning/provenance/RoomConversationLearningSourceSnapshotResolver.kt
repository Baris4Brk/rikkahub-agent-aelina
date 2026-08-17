package me.rerere.rikkahub.learning.provenance

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.authority.source.ConversationSourceSnapshotFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.LearningSourceAuthorityDao
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.learning.jobs.LearningSourceIntegrityResolver
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.privacy.LearningEphemeralScopeRegistry
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Main-database provenance reader for current selected Conversation messages.
 *
 * Authority rows and raw payload rows are copied inside one Room transaction. JSON decoding,
 * digest calculation and text extraction happen afterwards. No body is cached, logged or written.
 */
class RoomConversationLearningSourceSnapshotResolver(
    private val transactions: ConversationLearningSourceTransactionRunner,
    private val authority: LearningSourceAuthorityReadPort,
    private val payloads: ConversationMessagePayloadReadPort,
    private val featureFlags: LearningFeatureFlagSource,
    private val ephemeralRegistry: LearningEphemeralScopeRegistry? = null,
) : LearningSourceSnapshotResolver, LearningSourceIntegrityResolver {
    constructor(
        database: AppDatabase,
        authority: LearningSourceAuthorityDao = database.learningSourceAuthorityDao(),
        conversations: ConversationDAO = database.conversationDao(),
        messageNodes: MessageNodeDAO = database.messageNodeDao(),
        featureFlags: LearningFeatureFlagSource,
        ephemeralRegistry: LearningEphemeralScopeRegistry? = null,
    ) : this(
        transactions = RoomConversationLearningSourceTransactionRunner(database),
        authority = RoomLearningSourceAuthorityReadPort(authority),
        payloads = RoomConversationMessagePayloadReadPort(conversations, messageNodes),
            featureFlags = featureFlags,
            ephemeralRegistry = ephemeralRegistry,
    )
    override suspend fun resolve(
        request: LearningSourceSnapshotRequest,
    ): LearningSourceSnapshotResult {
        if (!learningReadsEnabled()) return snapshotUnavailable(LearningSourceReadFailure.UNAVAILABLE)
        val sourceRevision = request.source.sourceRevision
            ?: return snapshotUnavailable(LearningSourceReadFailure.REVISION_UNKNOWN)
        if (sourceRevision <= 0L) return snapshotUnavailable(LearningSourceReadFailure.REVISION_UNKNOWN)
        if (request.frozenNowMs >= request.expiresAtMs) {
            return snapshotUnavailable(LearningSourceReadFailure.EXPIRED)
        }
        val scope = request.expectedScope.toAuthorityScope()
        val snapshot = transactions.inTransaction {
            when (request.source.sourceKind) {
                LearningSourceKind.CONVERSATION_MESSAGE -> loadMessageSnapshot(
                    expectedScope = request.expectedScope,
                    scopeKind = scope.kind,
                    scopeId = scope.id,
                    messageId = request.source.sourceId,
                    sourceRevision = sourceRevision,
                )
                LearningSourceKind.CONVERSATION -> null
                else -> null
            }
        } ?: return snapshotUnavailable(
            if (request.source.sourceKind in SUPPORTED_SOURCE_KINDS) {
                LearningSourceReadFailure.REVISION_UNKNOWN
            } else {
                LearningSourceReadFailure.UNAVAILABLE
            },
        )
        val decoded = withContext(Dispatchers.Default) { snapshot.decodeAndValidate(request.maxChars) }
        return when (decoded) {
            is DecodedMessage.Available -> {
                val revalidation = revalidate(request.source)
                if (revalidation != null) {
                    decoded.text.fill('\u0000')
                    snapshotUnavailable(revalidation)
                } else {
                    LearningSourceSnapshotResult.Available(
                        LearningEphemeralSourceSnapshot(
                            source = request.source,
                            alias = "E1",
                            text = decoded.text,
                            expiresAtMs = request.expiresAtMs,
                            ephemeralRegistry = ephemeralRegistry,
                        ),
                    )
                }
            }
            is DecodedMessage.Unavailable -> snapshotUnavailable(decoded.reason)
        }
    }

    override suspend fun revalidate(source: LearningSourceRef): LearningSourceReadFailure? {
        if (!learningReadsEnabled()) return LearningSourceReadFailure.UNAVAILABLE
        val revision = source.sourceRevision ?: return LearningSourceReadFailure.REVISION_UNKNOWN
        if (revision <= 0L) return LearningSourceReadFailure.REVISION_UNKNOWN
        val scope = source.scope.toAuthorityScope()
        return when (source.sourceKind) {
            LearningSourceKind.CONVERSATION_MESSAGE -> transactions.inTransaction {
                val row = authority.findMessageAtRevision(
                    scope.kind,
                    scope.id,
                    source.sourceId,
                    revision,
                ) ?: return@inTransaction LearningSourceReadFailure.REVISION_UNKNOWN
                validateMessageAuthority(row, source.scope, revision)?.reason?.let {
                    return@inTransaction it
                }
                val parent = authority.findConversation(
                    scope.kind,
                    scope.id,
                    row.conversationId,
                ) ?: return@inTransaction LearningSourceReadFailure.REVISION_UNKNOWN
                if (parent.sourceState != "ACTIVE" || parent.branchHeadMessageId == null) {
                    return@inTransaction LearningSourceReadFailure.REVISION_MISMATCH
                }
                val selected = payloads.findNodesContainingMessage(
                    row.conversationId,
                    row.messageId,
                )
                val selectedIds = payloads.selectedMessageIds(row.conversationId)
                    ?: return@inTransaction LearningSourceReadFailure.SNAPSHOT_MISMATCH
                if (selected.size != 1 || !selected.single().selectsMessage(row.messageId) ||
                    row.messageId !in selectedIds || parent.branchHeadMessageId != selectedIds.lastOrNull()
                ) {
                    LearningSourceReadFailure.SNAPSHOT_MISMATCH
                } else {
                    null
                }
            }
            else -> LearningSourceReadFailure.UNAVAILABLE
        }
    }

    /** Exact ACTIVE message digest only; non-message and stale events remain unknown. */
    override suspend fun resolveSha256(event: LearningInboxEventEntity): String? {
        if (!learningReadsEnabled()) return null
        if (event.sourceType != LearningSourceKind.CONVERSATION_MESSAGE.name) return null
        if (event.sourceState != "ACTIVE") return null
        val revision = event.sourceRevision?.takeIf { it > 0L } ?: return null
        val scope = LearningScope.parseOrNull(
            event.scopeKind ?: return null,
            event.scopeId ?: return null,
        ) ?: return null
        return transactions.inTransaction {
            val row = authority.findMessageAtRevision(
                scopeKind = scope.kind.name,
                scopeId = scope.storageId,
                messageId = event.sourceId ?: return@inTransaction null,
                sourceRevision = revision,
            ) ?: return@inTransaction null
            if (validateMessageAuthority(row, scope, revision) != null) return@inTransaction null
            val parent = authority.findConversation(
                scope.kind.name,
                scope.storageId,
                row.conversationId,
            ) ?: return@inTransaction null
            if (parent.sourceState != "ACTIVE" || parent.branchHeadMessageId == null) {
                return@inTransaction null
            }
            row.payloadIntegritySha256
        }
    }

    private suspend fun loadMessageSnapshot(
        expectedScope: LearningScope,
        scopeKind: String,
        scopeId: String,
        messageId: String,
        sourceRevision: Long,
    ): RawMessageSnapshot? {
        val message = authority.findMessageAtRevision(
            scopeKind = scopeKind,
            scopeId = scopeId,
            messageId = messageId,
            sourceRevision = sourceRevision,
        ) ?: return null
        val authorityFailure = validateMessageAuthority(
            message,
            expectedScope,
            sourceRevision,
        )
        if (authorityFailure != null) return RawMessageSnapshot.Failed(authorityFailure.reason)
        val parent = authority.findConversation(scopeKind, scopeId, message.conversationId)
        if (parent == null || parent.sourceState != "ACTIVE") {
            return RawMessageSnapshot.Failed(LearningSourceReadFailure.REVISION_UNKNOWN)
        }
        if (!payloads.conversationExists(message.conversationId)) {
            return RawMessageSnapshot.Failed(LearningSourceReadFailure.NOT_FOUND)
        }
        val nodes = payloads.findNodesContainingMessage(message.conversationId, message.messageId)
        if (nodes.size != 1) return RawMessageSnapshot.Failed(
            if (nodes.isEmpty()) LearningSourceReadFailure.NOT_FOUND
            else LearningSourceReadFailure.SNAPSHOT_MISMATCH,
        )
        val selectedIds = payloads.selectedMessageIds(message.conversationId)
            ?: return RawMessageSnapshot.Failed(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        if (message.messageId !in selectedIds || parent.branchHeadMessageId != selectedIds.lastOrNull()) {
            return RawMessageSnapshot.Failed(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        }
        return RawMessageSnapshot.Loaded(message, nodes.single())
    }

    private fun learningReadsEnabled(): Boolean = featureFlags.current().let { flags ->
        flags.isValid && flags.effective.handoff
    }
}

interface ConversationLearningSourceTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

interface LearningSourceAuthorityReadPort {
    suspend fun findConversation(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
    ): me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity?

    suspend fun findMessageAtRevision(
        scopeKind: String,
        scopeId: String,
        messageId: String,
        sourceRevision: Long,
    ): LearningMessageSourceAuthorityEntity?
}

interface ConversationMessagePayloadReadPort {
    suspend fun conversationExists(conversationId: String): Boolean
    suspend fun findNodesContainingMessage(
        conversationId: String,
        messageId: String,
    ): List<MessageNodeEntity>
    suspend fun selectedMessageIds(conversationId: String): List<String>?
}

private class RoomConversationLearningSourceTransactionRunner(
    private val database: AppDatabase,
) : ConversationLearningSourceTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}

private class RoomLearningSourceAuthorityReadPort(
    private val dao: LearningSourceAuthorityDao,
) : LearningSourceAuthorityReadPort {
    override suspend fun findConversation(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
    ) = dao.findConversation(scopeKind, scopeId, conversationId)

    override suspend fun findMessageAtRevision(
        scopeKind: String,
        scopeId: String,
        messageId: String,
        sourceRevision: Long,
    ) = dao.findMessageAtRevision(scopeKind, scopeId, messageId, sourceRevision)
}

private class RoomConversationMessagePayloadReadPort(
    private val conversations: ConversationDAO,
    private val nodes: MessageNodeDAO,
) : ConversationMessagePayloadReadPort {
    override suspend fun conversationExists(conversationId: String): Boolean =
        conversations.existsById(conversationId)

    override suspend fun findNodesContainingMessage(
        conversationId: String,
        messageId: String,
    ): List<MessageNodeEntity> = nodes.findNodesContainingMessage(conversationId, messageId)

    override suspend fun selectedMessageIds(conversationId: String): List<String>? {
        val allNodes = nodes.getNodesOfConversation(conversationId)
        val selected = ArrayList<String>(allNodes.size)
        for (node in allNodes) {
            val messages = runCatching {
                JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
            }.getOrNull() ?: return null
            selected += messages.getOrNull(node.selectIndex)?.id?.toString() ?: return null
        }
        return selected
    }
}

private sealed interface RawMessageSnapshot {
    data class Loaded(
        val authority: LearningMessageSourceAuthorityEntity,
        val node: MessageNodeEntity,
    ) : RawMessageSnapshot

    data class Failed(val reason: LearningSourceReadFailure) : RawMessageSnapshot
}

private sealed interface DecodedMessage {
    data class Available(val text: CharArray) : DecodedMessage
    data class Unavailable(val reason: LearningSourceReadFailure) : DecodedMessage
}

private fun RawMessageSnapshot.decodeAndValidate(maxChars: Int): DecodedMessage = when (this) {
    is RawMessageSnapshot.Failed -> DecodedMessage.Unavailable(reason)
    is RawMessageSnapshot.Loaded -> {
        val messages = runCatching {
            JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
        }.getOrNull() ?: return DecodedMessage.Unavailable(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        if (node.selectIndex !in messages.indices) {
            return DecodedMessage.Unavailable(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        }
        val selected = messages[node.selectIndex]
        if (selected.id.toString() != authority.messageId) {
            return DecodedMessage.Unavailable(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        }
        if (selected.role.name != authority.messageRole) {
            return DecodedMessage.Unavailable(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        }
        val digest = ConversationSourceSnapshotFactory.payloadIntegritySha256(selected)
        if (digest != authority.payloadIntegritySha256) {
            return DecodedMessage.Unavailable(LearningSourceReadFailure.SNAPSHOT_MISMATCH)
        }
        val text = selected.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n", transform = UIMessagePart.Text::text)
        if (text.length > maxChars) {
            DecodedMessage.Unavailable(LearningSourceReadFailure.TOO_LARGE)
        } else {
            DecodedMessage.Available(text.toCharArray())
        }
    }
}

private fun MessageNodeEntity.selectsMessage(messageId: String): Boolean {
    val messages = runCatching {
        JsonInstant.decodeFromString<List<UIMessage>>(this.messages)
    }.getOrNull() ?: return false
    return messages.getOrNull(selectIndex)?.id?.toString() == messageId
}

private data class AuthorityScope(val kind: String, val id: String)

private fun LearningScope.toAuthorityScope(): AuthorityScope = AuthorityScope(kind.name, storageId)

private data class AuthorityFailure(val reason: LearningSourceReadFailure)

private fun validateMessageAuthority(
    row: LearningMessageSourceAuthorityEntity,
    scope: LearningScope,
    revision: Long,
): AuthorityFailure? = when {
    row.scopeKind != scope.kind.name || row.scopeId != scope.storageId ->
        AuthorityFailure(LearningSourceReadFailure.SCOPE_MISMATCH)
    row.sourceRevision != revision -> AuthorityFailure(LearningSourceReadFailure.REVISION_MISMATCH)
    row.sourceState == "TOMBSTONED" -> AuthorityFailure(LearningSourceReadFailure.TOMBSTONED)
    row.sourceState != "ACTIVE" -> AuthorityFailure(LearningSourceReadFailure.REVISION_MISMATCH)
    row.payloadIntegritySha256 == null -> AuthorityFailure(LearningSourceReadFailure.REVISION_UNKNOWN)
    else -> null
}

private fun snapshotUnavailable(reason: LearningSourceReadFailure) =
    LearningSourceSnapshotResult.Unavailable(reason)

private val SUPPORTED_SOURCE_KINDS = setOf(
    LearningSourceKind.CONVERSATION_MESSAGE,
)
