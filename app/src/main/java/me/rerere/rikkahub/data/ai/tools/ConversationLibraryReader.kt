package me.rerere.rikkahub.data.ai.tools

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.LightConversationEntity
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

const val MAX_CONVERSATIONS_PER_LIST = 50
const val MAX_MESSAGES_PER_READ = 50
const val MAX_TEXT_PER_CONVERSATION_MESSAGE = 4_000
const val MAX_TOTAL_TEXT_PER_CONVERSATION_RESULT = 40_000
const val MAX_CONVERSATION_SEARCH_RESULTS = 20
const val MAX_CONVERSATION_READ_CALLS_PER_COMMAND = 4
const val MAX_CONVERSATION_SEARCH_CALLS_PER_COMMAND = 6

enum class ConversationReadOperation { LIST, READ, SEARCH }

data class ConversationReadAccessRequest(
    val assistantId: Uuid,
    val privilegedConversationId: Uuid,
    val commandId: Uuid,
    val origin: ToolCallOrigin,
    val selectedPrivilegedConversation: Boolean,
    val historyReadEnabled: Boolean,
    val deviceUnlocked: Boolean,
    val operation: ConversationReadOperation,
)

sealed interface ConversationReadAccessDecision {
    data object Allowed : ConversationReadAccessDecision
    data class Denied(val code: String, val message: String) : ConversationReadAccessDecision
}

object SecondUserConversationAccessPolicy {
    private val allowedOrigins = setOf(
        ToolCallOrigin.LocalChat,
        ToolCallOrigin.SystemAssistant,
        ToolCallOrigin.QuickCapture,
    )

    fun evaluate(request: ConversationReadAccessRequest): ConversationReadAccessDecision = when {
        !request.selectedPrivilegedConversation -> ConversationReadAccessDecision.Denied(
            "PRIVILEGED_SESSION_REQUIRED",
            "Conversation history can only be read from the selected second-user conversation.",
        )
        !request.historyReadEnabled -> ConversationReadAccessDecision.Denied(
            "HISTORY_READ_DISABLED",
            "Enable cross-conversation history reading for this second user.",
        )
        !request.deviceUnlocked -> ConversationReadAccessDecision.Denied(
            "DEVICE_LOCKED",
            "Unlock the device before reading conversation history.",
        )
        request.origin !in allowedOrigins -> ConversationReadAccessDecision.Denied(
            "LOCAL_ORIGIN_REQUIRED",
            "Conversation history is unavailable from remote or keyguard surfaces.",
        )
        else -> ConversationReadAccessDecision.Allowed
    }
}

class ConversationReadBudget(val commandId: Uuid) {
    private val reads = AtomicInteger(0)
    private val searches = AtomicInteger(0)

    fun consume(operation: ConversationReadOperation): Boolean = when (operation) {
        ConversationReadOperation.LIST -> true
        ConversationReadOperation.READ -> reads.incrementAndGet() <= MAX_CONVERSATION_READ_CALLS_PER_COMMAND
        ConversationReadOperation.SEARCH -> searches.incrementAndGet() <= MAX_CONVERSATION_SEARCH_CALLS_PER_COMMAND
    }
}

data class ConversationSummary(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val title: String,
    val createAt: Instant,
    val updateAt: Instant,
    val isPinned: Boolean,
)

data class ConversationVisibleMessage(
    val conversationId: Uuid,
    val nodeId: Uuid,
    val messageId: Uuid,
    val role: String,
    val text: String,
    val createdAt: String,
    val truncated: Boolean,
)

data class ConversationMessageWindow(
    val conversationId: Uuid,
    val title: String,
    val messages: List<ConversationVisibleMessage>,
    val nextBeforeNodeIndex: Int?,
    val hasMore: Boolean,
    val truncated: Boolean,
    val totalCharacters: Int,
)

data class ConversationSearchHit(
    val conversationId: Uuid,
    val nodeId: Uuid,
    val messageId: Uuid,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

data class ConversationSearchWindow(
    val conversationId: Uuid,
    val title: String,
    val hits: List<ConversationSearchHit>,
)

class ConversationLibraryReader(
    private val repository: ConversationRepository,
) {
    suspend fun listRecent(excludeConversationId: Uuid, limit: Int): List<ConversationSummary> =
        repository.listRecentConversationSummaries(excludeConversationId, limit)
            .mapNotNull(LightConversationEntity::toSummaryOrNull)

    suspend fun readRecent(
        conversationId: Uuid,
        limit: Int,
        beforeNodeIndex: Int?,
    ): ConversationMessageWindow {
        val summary = repository.getConversationSummaryById(conversationId)
            ?: throw ConversationReaderException("CONVERSATION_NOT_FOUND", "The conversation does not exist.")
        val wanted = limit.coerceIn(1, MAX_MESSAGES_PER_READ)
        val newestFirst = mutableListOf<ConversationVisibleMessage>()
        var cursor = beforeNodeIndex
        var lastScanned: Int? = null
        var totalChars = 0
        var truncated = false
        var exhausted = false

        while (newestFirst.size < wanted && totalChars < MAX_TOTAL_TEXT_PER_CONVERSATION_RESULT && !exhausted) {
            val rows = repository.getRecentNodeEntitiesBefore(conversationId, cursor, 64)
            if (rows.isEmpty()) break
            for (row in rows) {
                lastScanned = row.nodeIndex
                cursor = row.nodeIndex
                val message = decodeSelectedVisibleMessage(row) ?: continue
                val rawText = visibleConversationText(message)
                if (rawText.isBlank()) continue
                val perMessage = rawText.take(MAX_TEXT_PER_CONVERSATION_MESSAGE)
                val remaining = MAX_TOTAL_TEXT_PER_CONVERSATION_RESULT - totalChars
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val text = perMessage.take(remaining)
                val messageTruncated = text.length < rawText.length
                newestFirst += ConversationVisibleMessage(
                    conversationId = conversationId,
                    nodeId = Uuid.parse(row.id),
                    messageId = message.id,
                    role = message.role.name.lowercase(),
                    text = text,
                    createdAt = message.createdAt.toString(),
                    truncated = messageTruncated,
                )
                totalChars += text.length
                truncated = truncated || messageTruncated
                if (newestFirst.size >= wanted || totalChars >= MAX_TOTAL_TEXT_PER_CONVERSATION_RESULT) break
            }
            exhausted = rows.size < 64
        }

        val next = lastScanned
        val hasMore = next?.let { repository.hasNodeBefore(conversationId, it) } ?: false
        return ConversationMessageWindow(
            conversationId = conversationId,
            title = summary.title,
            messages = newestFirst.asReversed(),
            nextBeforeNodeIndex = next,
            hasMore = hasMore,
            truncated = truncated || (hasMore && totalChars >= MAX_TOTAL_TEXT_PER_CONVERSATION_RESULT),
            totalCharacters = totalChars,
        )
    }

    suspend fun search(
        conversationId: Uuid,
        query: String,
        limit: Int,
    ): ConversationSearchWindow {
        val summary = repository.getConversationSummaryById(conversationId)
            ?: throw ConversationReaderException("CONVERSATION_NOT_FOUND", "The conversation does not exist.")
        val normalized = query.trim()
        if (normalized.isEmpty()) throw ConversationReaderException("EMPTY_QUERY", "query must not be empty.")
        if (normalized.length > 256) throw ConversationReaderException("QUERY_TOO_LONG", "query exceeds 256 characters.")
        val wanted = limit.coerceIn(1, MAX_CONVERSATION_SEARCH_RESULTS)
        val accepted = mutableListOf<ConversationSearchHit>()
        var offset = 0
        while (accepted.size < wanted && offset < 200) {
            val candidates = repository.searchMessagesInConversation(
                keyword = normalized,
                conversationId = conversationId,
                sort = MessageSearchSort.RELEVANCE,
                limit = 50,
                offset = offset,
            )
            if (candidates.isEmpty()) break
            val selectedByNode = repository.getNodeEntitiesByIds(
                conversationId,
                candidates.map { it.nodeId },
            ).associate { row -> row.id to decodeSelectedVisibleMessage(row)?.id?.toString() }
            candidates.forEach { hit ->
                if (selectedByNode[hit.nodeId] == hit.messageId && accepted.size < wanted) {
                    accepted += ConversationSearchHit(
                        conversationId = conversationId,
                        nodeId = Uuid.parse(hit.nodeId),
                        messageId = Uuid.parse(hit.messageId),
                        title = hit.title,
                        updateAt = hit.updateAt,
                        snippet = hit.snippet.take(1_000),
                    )
                }
            }
            offset += candidates.size
            if (candidates.size < 50) break
        }
        return ConversationSearchWindow(conversationId, summary.title, accepted)
    }
}

class ConversationReaderException(val code: String, override val message: String) : IllegalArgumentException(message)

private fun LightConversationEntity.toSummaryOrNull(): ConversationSummary? = runCatching {
    ConversationSummary(
        conversationId = Uuid.parse(id),
        assistantId = Uuid.parse(assistantId),
        title = title,
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
        isPinned = isPinned,
    )
}.getOrNull()

internal fun decodeSelectedVisibleMessage(
    entity: me.rerere.rikkahub.data.db.entity.MessageNodeEntity,
): UIMessage? {
    val decoded = runCatching { JsonInstant.decodeFromString<List<UIMessage>>(entity.messages) }.getOrNull()
        ?: return null
    return decoded.getOrNull(entity.selectIndex)?.takeIf { message ->
        message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT
    }
}

internal fun visibleConversationText(message: UIMessage): String = message.parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }
    .trim()
