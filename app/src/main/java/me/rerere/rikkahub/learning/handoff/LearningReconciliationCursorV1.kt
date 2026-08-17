package me.rerere.rikkahub.learning.handoff

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.learning.model.StrictLearningJsonKeyScanner

const val LEARNING_RECONCILIATION_CURSOR_SCHEMA_VERSION = 1
const val MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS = 16 * 1_024

private const val MAX_CURSOR_ID_CHARS = 256
private const val MAX_SCOPE_KIND_CHARS = 64
private const val CURSOR_INTEGRITY_DOMAIN = "learning-reconciliation-cursor-v1"

enum class LearningReconciliationCursorStateV1 {
    RUNNING,
    COMPLETE,
}

enum class LearningReconciliationPhaseV1 {
    COMMAND,
    EXECUTION,
    CONVERSATION_SOURCE,
    MESSAGE_SOURCE,
    FEEDBACK_REVISION,
}

sealed interface LearningReconciliationAfterKeyV1 {
    val phase: LearningReconciliationPhaseV1
    val orderingTimeMs: Long

    data class Command(
        val finishedAtMs: Long,
        val id: String,
    ) : LearningReconciliationAfterKeyV1 {
        override val phase = LearningReconciliationPhaseV1.COMMAND
        override val orderingTimeMs: Long get() = finishedAtMs

        init {
            require(finishedAtMs >= 0L) { "Negative command cursor time" }
            requireCursorId(id, "command id")
        }
    }

    data class Execution(
        val finishedAtMs: Long,
        val id: String,
    ) : LearningReconciliationAfterKeyV1 {
        override val phase = LearningReconciliationPhaseV1.EXECUTION
        override val orderingTimeMs: Long get() = finishedAtMs

        init {
            require(finishedAtMs >= 0L) { "Negative execution cursor time" }
            requireCursorId(id, "execution id")
        }
    }

    data class ConversationSource(
        val updatedAtMs: Long,
        val conversationId: String,
        val scopeKind: String,
        val scopeId: String,
    ) : LearningReconciliationAfterKeyV1 {
        override val phase = LearningReconciliationPhaseV1.CONVERSATION_SOURCE
        override val orderingTimeMs: Long get() = updatedAtMs

        init {
            require(updatedAtMs >= 0L) { "Negative Conversation source cursor time" }
            requireCursorId(conversationId, "conversation id")
            requireScopeKind(scopeKind)
            requireCursorId(scopeId, "scope id")
        }
    }

    data class MessageSource(
        val updatedAtMs: Long,
        val conversationId: String,
        val messageId: String,
        val scopeKind: String,
        val scopeId: String,
    ) : LearningReconciliationAfterKeyV1 {
        override val phase = LearningReconciliationPhaseV1.MESSAGE_SOURCE
        override val orderingTimeMs: Long get() = updatedAtMs

        init {
            require(updatedAtMs >= 0L) { "Negative message source cursor time" }
            requireCursorId(conversationId, "conversation id")
            requireCursorId(messageId, "message id")
            requireScopeKind(scopeKind)
            requireCursorId(scopeId, "scope id")
        }
    }

    data class FeedbackRevision(
        val updatedAtMs: Long,
        val feedbackId: String,
        val sourceRevision: Long,
    ) : LearningReconciliationAfterKeyV1 {
        override val phase = LearningReconciliationPhaseV1.FEEDBACK_REVISION
        override val orderingTimeMs: Long get() = updatedAtMs

        init {
            require(updatedAtMs >= 0L) { "Negative feedback cursor time" }
            requireCursorId(feedbackId, "feedback id")
            require(sourceRevision > 0L) { "Feedback cursor requires a positive revision" }
        }
    }
}

data class LearningReconciliationPhasePositionV1(
    val after: LearningReconciliationAfterKeyV1? = null,
    val coverageFloorMs: Long? = null,
) {
    init {
        require(coverageFloorMs == null || coverageFloorMs >= 0L) {
            "Negative reconciliation coverage floor"
        }
        require(coverageFloorMs == null || after != null) {
            "A reconciliation coverage floor requires an after key"
        }
        if (coverageFloorMs != null) {
            require(coverageFloorMs <= requireNotNull(after).orderingTimeMs) {
                "A reconciliation coverage floor cannot follow its after key"
            }
        }
    }

    val isEmpty: Boolean get() = after == null && coverageFloorMs == null
}

/**
 * Content-free, resumable keyset cursor for one frozen reconciliation authority window.
 *
 * Positions after the active phase are always empty. A phase transition therefore records that
 * the preceding phase reached an empty page without inventing a separate completion flag.
 */
data class LearningReconciliationCursorV1(
    val schemaVersion: Int = LEARNING_RECONCILIATION_CURSOR_SCHEMA_VERSION,
    val state: LearningReconciliationCursorStateV1,
    val phase: LearningReconciliationPhaseV1,
    val streamId: String,
    val frozenHeadSequence: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val command: LearningReconciliationPhasePositionV1 = LearningReconciliationPhasePositionV1(),
    val execution: LearningReconciliationPhasePositionV1 = LearningReconciliationPhasePositionV1(),
    val conversationSource: LearningReconciliationPhasePositionV1 =
        LearningReconciliationPhasePositionV1(),
    val messageSource: LearningReconciliationPhasePositionV1 =
        LearningReconciliationPhasePositionV1(),
    val feedbackRevision: LearningReconciliationPhasePositionV1 =
        LearningReconciliationPhasePositionV1(),
) {
    init {
        require(schemaVersion == LEARNING_RECONCILIATION_CURSOR_SCHEMA_VERSION) {
            "Unsupported reconciliation cursor version"
        }
        require(STREAM_ID.matches(streamId) && streamId != NIL_STREAM_ID) {
            "Invalid reconciliation stream id"
        }
        require(frozenHeadSequence > 0L) { "Frozen reconciliation head must be positive" }
        require(windowStartMs >= 0L && windowEndMs >= windowStartMs && windowEndMs < Long.MAX_VALUE) {
            "Invalid frozen reconciliation window"
        }
        require(state != LearningReconciliationCursorStateV1.COMPLETE ||
            phase == LearningReconciliationPhaseV1.FEEDBACK_REVISION
        ) { "A complete reconciliation cursor must end at feedback revisions" }

        positions().forEachIndexed { index, (expectedPhase, position) ->
            require(position.after == null || position.after.phase == expectedPhase) {
                "Reconciliation after key does not match its phase"
            }
            require(position.after == null || position.after.orderingTimeMs in windowStartMs..windowEndMs) {
                "Reconciliation after key is outside its frozen window"
            }
            require(position.coverageFloorMs == null ||
                position.coverageFloorMs in windowStartMs..windowEndMs
            ) { "Reconciliation coverage is outside its frozen window" }
            if (state == LearningReconciliationCursorStateV1.RUNNING && index > phase.ordinal) {
                require(position.isEmpty) { "A future reconciliation phase must be empty" }
            }
        }
    }

    fun advance(
        after: LearningReconciliationAfterKeyV1,
        observedCoverageFloorMs: Long? = null,
    ): LearningReconciliationCursorV1 {
        require(state == LearningReconciliationCursorStateV1.RUNNING) {
            "A complete reconciliation cursor cannot advance"
        }
        require(after.phase == phase) { "Reconciliation after key is for a different phase" }
        require(after.orderingTimeMs in windowStartMs..windowEndMs) {
            "Reconciliation after key is outside its frozen window"
        }
        require(observedCoverageFloorMs == null ||
            observedCoverageFloorMs in windowStartMs..after.orderingTimeMs
        ) { "Observed coverage is outside the current page" }

        val current = position(phase)
        current.after?.let { previous ->
            require(compareAfterKeys(previous, after) < 0) {
                "Reconciliation after keys must advance strictly"
            }
        }
        val next = current.copy(
            after = after,
            coverageFloorMs = minimumTime(current.coverageFloorMs, observedCoverageFloorMs),
        )
        return withPosition(phase, next)
    }

    /** Moves past an empty page while preserving every completed phase's key and coverage. */
    fun nextPhase(): LearningReconciliationCursorV1 {
        require(state == LearningReconciliationCursorStateV1.RUNNING) {
            "A complete reconciliation cursor has no next phase"
        }
        require(phase != LearningReconciliationPhaseV1.FEEDBACK_REVISION) {
            "Feedback reconciliation completes instead of advancing to another phase"
        }
        return copy(phase = LearningReconciliationPhaseV1.entries[phase.ordinal + 1])
    }

    fun complete(): LearningReconciliationCursorV1 {
        require(state == LearningReconciliationCursorStateV1.RUNNING &&
            phase == LearningReconciliationPhaseV1.FEEDBACK_REVISION
        ) { "Only a running feedback-revision cursor can complete" }
        return copy(state = LearningReconciliationCursorStateV1.COMPLETE)
    }

    private fun position(target: LearningReconciliationPhaseV1): LearningReconciliationPhasePositionV1 =
        when (target) {
            LearningReconciliationPhaseV1.COMMAND -> command
            LearningReconciliationPhaseV1.EXECUTION -> execution
            LearningReconciliationPhaseV1.CONVERSATION_SOURCE -> conversationSource
            LearningReconciliationPhaseV1.MESSAGE_SOURCE -> messageSource
            LearningReconciliationPhaseV1.FEEDBACK_REVISION -> feedbackRevision
        }

    private fun withPosition(
        target: LearningReconciliationPhaseV1,
        position: LearningReconciliationPhasePositionV1,
    ): LearningReconciliationCursorV1 = when (target) {
        LearningReconciliationPhaseV1.COMMAND -> copy(command = position)
        LearningReconciliationPhaseV1.EXECUTION -> copy(execution = position)
        LearningReconciliationPhaseV1.CONVERSATION_SOURCE -> copy(conversationSource = position)
        LearningReconciliationPhaseV1.MESSAGE_SOURCE -> copy(messageSource = position)
        LearningReconciliationPhaseV1.FEEDBACK_REVISION -> copy(feedbackRevision = position)
    }

    private fun positions(): List<Pair<LearningReconciliationPhaseV1, LearningReconciliationPhasePositionV1>> =
        listOf(
            LearningReconciliationPhaseV1.COMMAND to command,
            LearningReconciliationPhaseV1.EXECUTION to execution,
            LearningReconciliationPhaseV1.CONVERSATION_SOURCE to conversationSource,
            LearningReconciliationPhaseV1.MESSAGE_SOURCE to messageSource,
            LearningReconciliationPhaseV1.FEEDBACK_REVISION to feedbackRevision,
        )

    companion object {
        fun initialize(
            streamId: String,
            frozenHeadSequence: Long,
            windowStartMs: Long,
            windowEndMs: Long,
        ): LearningReconciliationCursorV1 = LearningReconciliationCursorV1(
            state = LearningReconciliationCursorStateV1.RUNNING,
            phase = LearningReconciliationPhaseV1.COMMAND,
            streamId = streamId,
            frozenHeadSequence = frozenHeadSequence,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
        )
    }
}

/** Strict canonical codec. Invalid or pre-V1 persisted cursors return null and must be replayed. */
object LearningReconciliationCursorV1Codec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    fun encode(cursor: LearningReconciliationCursorV1): String {
        val body = encodeBody(cursor)
        val canonical = buildJsonObject {
            body.forEach { (key, value) -> put(key, value) }
            put("integrity_sha256", integritySha256(body.toString()))
        }.toString()
        require(canonical.length <= MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS &&
            canonical.utf8Size() <= MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS
        ) { "Reconciliation cursor exceeds its persistence bound" }
        return canonical
    }

    fun decode(raw: String?): LearningReconciliationCursorV1? {
        if (raw.isNullOrEmpty() || raw.length > MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS ||
            raw.utf8Size() > MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS
        ) {
            return null
        }
        if (StrictLearningJsonKeyScanner.scan(raw) != StrictLearningJsonKeyScanner.Result.VALID) {
            return null
        }
        return try {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
            if (root.keys.toList() != ROOT_KEYS) return null
            val cursor = LearningReconciliationCursorV1(
                schemaVersion = root.requiredInt("schema_version"),
                state = root.requiredEnum<LearningReconciliationCursorStateV1>("state"),
                phase = root.requiredEnum<LearningReconciliationPhaseV1>("phase"),
                streamId = root.requiredString("stream_id"),
                frozenHeadSequence = root.requiredLong("frozen_head_sequence"),
                windowStartMs = root.requiredLong("window_start_ms"),
                windowEndMs = root.requiredLong("window_end_ms"),
                command = root.requiredPosition(
                    "command",
                    LearningReconciliationPhaseV1.COMMAND,
                ),
                execution = root.requiredPosition(
                    "execution",
                    LearningReconciliationPhaseV1.EXECUTION,
                ),
                conversationSource = root.requiredPosition(
                    "conversation_source",
                    LearningReconciliationPhaseV1.CONVERSATION_SOURCE,
                ),
                messageSource = root.requiredPosition(
                    "message_source",
                    LearningReconciliationPhaseV1.MESSAGE_SOURCE,
                ),
                feedbackRevision = root.requiredPosition(
                    "feedback_revision",
                    LearningReconciliationPhaseV1.FEEDBACK_REVISION,
                ),
            )
            val integrity = root.requiredString("integrity_sha256")
            if (!SHA256.matches(integrity) || encode(cursor) != raw) null else cursor
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeBody(cursor: LearningReconciliationCursorV1): JsonObject = buildJsonObject {
        put("schema_version", cursor.schemaVersion)
        put("state", cursor.state.name)
        put("phase", cursor.phase.name)
        put("stream_id", cursor.streamId)
        put("frozen_head_sequence", cursor.frozenHeadSequence)
        put("window_start_ms", cursor.windowStartMs)
        put("window_end_ms", cursor.windowEndMs)
        put("command", encodePosition(cursor.command))
        put("execution", encodePosition(cursor.execution))
        put("conversation_source", encodePosition(cursor.conversationSource))
        put("message_source", encodePosition(cursor.messageSource))
        put("feedback_revision", encodePosition(cursor.feedbackRevision))
    }

    private fun encodePosition(position: LearningReconciliationPhasePositionV1): JsonObject =
        buildJsonObject {
            put("after", position.after?.let(::encodeAfter) ?: JsonNull)
            put("coverage_floor_ms", position.coverageFloorMs?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun encodeAfter(after: LearningReconciliationAfterKeyV1): JsonObject = buildJsonObject {
        when (after) {
            is LearningReconciliationAfterKeyV1.Command -> {
                put("finished_at_ms", after.finishedAtMs)
                put("id", after.id)
            }
            is LearningReconciliationAfterKeyV1.Execution -> {
                put("finished_at_ms", after.finishedAtMs)
                put("id", after.id)
            }
            is LearningReconciliationAfterKeyV1.ConversationSource -> {
                put("updated_at_ms", after.updatedAtMs)
                put("conversation_id", after.conversationId)
                put("scope_kind", after.scopeKind)
                put("scope_id", after.scopeId)
            }
            is LearningReconciliationAfterKeyV1.MessageSource -> {
                put("updated_at_ms", after.updatedAtMs)
                put("conversation_id", after.conversationId)
                put("message_id", after.messageId)
                put("scope_kind", after.scopeKind)
                put("scope_id", after.scopeId)
            }
            is LearningReconciliationAfterKeyV1.FeedbackRevision -> {
                put("updated_at_ms", after.updatedAtMs)
                put("feedback_id", after.feedbackId)
                put("source_revision", after.sourceRevision)
            }
        }
    }

    private fun JsonObject.requiredPosition(
        name: String,
        phase: LearningReconciliationPhaseV1,
    ): LearningReconciliationPhasePositionV1 {
        val value = this[name] as? JsonObject ?: invalidCursor()
        if (value.keys.toList() != POSITION_KEYS) invalidCursor()
        val after = when (val element = value["after"] ?: invalidCursor()) {
            JsonNull -> null
            is JsonObject -> element.requiredAfter(phase)
            else -> invalidCursor()
        }
        return LearningReconciliationPhasePositionV1(
            after = after,
            coverageFloorMs = value["coverage_floor_ms"].optionalLong(),
        )
    }

    private fun JsonObject.requiredAfter(
        phase: LearningReconciliationPhaseV1,
    ): LearningReconciliationAfterKeyV1 = when (phase) {
        LearningReconciliationPhaseV1.COMMAND -> {
            requireKeys(COMMAND_AFTER_KEYS)
            LearningReconciliationAfterKeyV1.Command(
                finishedAtMs = requiredLong("finished_at_ms"),
                id = requiredString("id"),
            )
        }
        LearningReconciliationPhaseV1.EXECUTION -> {
            requireKeys(COMMAND_AFTER_KEYS)
            LearningReconciliationAfterKeyV1.Execution(
                finishedAtMs = requiredLong("finished_at_ms"),
                id = requiredString("id"),
            )
        }
        LearningReconciliationPhaseV1.CONVERSATION_SOURCE -> {
            requireKeys(CONVERSATION_AFTER_KEYS)
            LearningReconciliationAfterKeyV1.ConversationSource(
                updatedAtMs = requiredLong("updated_at_ms"),
                conversationId = requiredString("conversation_id"),
                scopeKind = requiredString("scope_kind"),
                scopeId = requiredString("scope_id"),
            )
        }
        LearningReconciliationPhaseV1.MESSAGE_SOURCE -> {
            requireKeys(MESSAGE_AFTER_KEYS)
            LearningReconciliationAfterKeyV1.MessageSource(
                updatedAtMs = requiredLong("updated_at_ms"),
                conversationId = requiredString("conversation_id"),
                messageId = requiredString("message_id"),
                scopeKind = requiredString("scope_kind"),
                scopeId = requiredString("scope_id"),
            )
        }
        LearningReconciliationPhaseV1.FEEDBACK_REVISION -> {
            requireKeys(FEEDBACK_AFTER_KEYS)
            LearningReconciliationAfterKeyV1.FeedbackRevision(
                updatedAtMs = requiredLong("updated_at_ms"),
                feedbackId = requiredString("feedback_id"),
                sourceRevision = requiredLong("source_revision"),
            )
        }
    }

    private fun JsonObject.requireKeys(expected: List<String>) {
        if (keys.toList() != expected) invalidCursor()
    }

    private fun JsonObject.requiredString(name: String): String =
        (this[name] ?: invalidCursor()).requiredString()

    private fun JsonElement.requiredString(): String {
        val primitive = this as? JsonPrimitive ?: invalidCursor()
        if (!primitive.isString) invalidCursor()
        return primitive.content
    }

    private fun JsonObject.requiredLong(name: String): Long =
        (this[name] ?: invalidCursor()).requiredLong()

    private fun JsonElement.requiredLong(): Long {
        val primitive = this as? JsonPrimitive ?: invalidCursor()
        if (primitive.isString) invalidCursor()
        return primitive.content.toLongOrNull() ?: invalidCursor()
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = requiredLong(name)
        return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: invalidCursor()
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
        val code = requiredString(name)
        return enumValues<T>().singleOrNull { it.name == code } ?: invalidCursor()
    }

    private fun JsonElement?.optionalLong(): Long? = when (this) {
        null -> invalidCursor()
        JsonNull -> null
        else -> requiredLong()
    }
}

private fun compareAfterKeys(
    first: LearningReconciliationAfterKeyV1,
    second: LearningReconciliationAfterKeyV1,
): Int {
    require(first.phase == second.phase) { "Cannot compare reconciliation keys across phases" }
    return when {
        first is LearningReconciliationAfterKeyV1.Command &&
            second is LearningReconciliationAfterKeyV1.Command -> compareTuples(
            first.finishedAtMs.compareTo(second.finishedAtMs),
            first.id.compareTo(second.id),
        )
        first is LearningReconciliationAfterKeyV1.Execution &&
            second is LearningReconciliationAfterKeyV1.Execution -> compareTuples(
            first.finishedAtMs.compareTo(second.finishedAtMs),
            first.id.compareTo(second.id),
        )
        first is LearningReconciliationAfterKeyV1.ConversationSource &&
            second is LearningReconciliationAfterKeyV1.ConversationSource -> compareTuples(
            first.updatedAtMs.compareTo(second.updatedAtMs),
            first.conversationId.compareTo(second.conversationId),
            first.scopeKind.compareTo(second.scopeKind),
            first.scopeId.compareTo(second.scopeId),
        )
        first is LearningReconciliationAfterKeyV1.MessageSource &&
            second is LearningReconciliationAfterKeyV1.MessageSource -> compareTuples(
            first.updatedAtMs.compareTo(second.updatedAtMs),
            first.conversationId.compareTo(second.conversationId),
            first.messageId.compareTo(second.messageId),
            first.scopeKind.compareTo(second.scopeKind),
            first.scopeId.compareTo(second.scopeId),
        )
        first is LearningReconciliationAfterKeyV1.FeedbackRevision &&
            second is LearningReconciliationAfterKeyV1.FeedbackRevision -> compareTuples(
            first.updatedAtMs.compareTo(second.updatedAtMs),
            first.feedbackId.compareTo(second.feedbackId),
            first.sourceRevision.compareTo(second.sourceRevision),
        )
        else -> error("Unreachable reconciliation key comparison")
    }
}

private fun compareTuples(vararg components: Int): Int =
    components.firstOrNull { it != 0 } ?: 0

private fun minimumTime(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}

private fun requireCursorId(value: String, label: String) {
    require(value.isNotEmpty() && value.length <= MAX_CURSOR_ID_CHARS && value == value.trim() &&
        value.none(Char::isISOControl)
    ) { "Invalid reconciliation $label" }
}

private fun requireScopeKind(value: String) {
    require(value.length in 1..MAX_SCOPE_KIND_CHARS && SAFE_SCOPE_KIND.matches(value)) {
        "Invalid reconciliation scope kind"
    }
}

private fun integritySha256(canonicalBody: String): String = MessageDigest.getInstance("SHA-256")
    .digest((CURSOR_INTEGRITY_DOMAIN + '\u0000' + canonicalBody).toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun invalidCursor(): Nothing = throw IllegalArgumentException("Invalid reconciliation cursor")

private val ROOT_KEYS = listOf(
    "schema_version",
    "state",
    "phase",
    "stream_id",
    "frozen_head_sequence",
    "window_start_ms",
    "window_end_ms",
    "command",
    "execution",
    "conversation_source",
    "message_source",
    "feedback_revision",
    "integrity_sha256",
)
private val POSITION_KEYS = listOf("after", "coverage_floor_ms")
private val COMMAND_AFTER_KEYS = listOf("finished_at_ms", "id")
private val CONVERSATION_AFTER_KEYS =
    listOf("updated_at_ms", "conversation_id", "scope_kind", "scope_id")
private val MESSAGE_AFTER_KEYS =
    listOf("updated_at_ms", "conversation_id", "message_id", "scope_kind", "scope_id")
private val FEEDBACK_AFTER_KEYS = listOf("updated_at_ms", "feedback_id", "source_revision")
private val STREAM_ID = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
)
private const val NIL_STREAM_ID = "00000000-0000-0000-0000-000000000000"
private val SAFE_SCOPE_KIND = Regex("[A-Z][A-Z0-9_]{0,63}")
private val SHA256 = Regex("[0-9a-f]{64}")
