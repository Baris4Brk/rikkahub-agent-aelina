package me.rerere.rikkahub.data.ai

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation

/**
 * Freezes only the historical boundary of one live tool turn.
 *
 * The current user message and the growing assistant/tool message always come from [project]'s
 * live input. No reasoning, tool input, tool output, approval state, or runtime result is copied
 * into the frozen prefix. This keeps continuation requests append-only without making runtime
 * execution or security state stale.
 */
internal class ToolLoopContinuationSnapshot private constructor(
    val turnUserMessageId: Uuid,
    val frozenPrefix: List<UIMessage>,
    private val frozenTurnUser: UIMessage,
    private val ordinaryMessageLimit: Int,
) {
    fun project(liveMessages: List<UIMessage>): ToolLoopSnapshotProjection {
        val matchingTurnIndices = liveMessages.indices.filter { index ->
            liveMessages[index].id == turnUserMessageId
        }
        if (matchingTurnIndices.size != 1) {
            return ToolLoopSnapshotProjection.Invalid(
                if (matchingTurnIndices.isEmpty()) {
                    ToolLoopSnapshotInvalidation.TURN_BOUNDARY_MISSING
                } else {
                    ToolLoopSnapshotInvalidation.TURN_BOUNDARY_DUPLICATED
                },
            )
        }

        val turnIndex = matchingTurnIndices.single()
        // Re-run only the call-1 history selection (never the growing assistant/tool tail) and
        // compare the exact provider-visible prefix. This catches same-id edits, insertions,
        // deletions, and reordering without treating changes to history that call 1 never sent as
        // an invalidation. Manual-compression layouts are intentionally supported even when their
        // retained prefix is not a simple suffix of the persisted conversation.
        val selectedCallOneContext = liveMessages
            .take(turnIndex + 1)
            .selectOrdinaryChatContext(ordinaryMessageLimit)
        val selectedTurnIndex = selectedCallOneContext.indexOfLast {
            it.id == turnUserMessageId
        }
        val liveSelectedPrefix = if (selectedTurnIndex >= 0) {
            selectedCallOneContext.take(selectedTurnIndex)
        } else {
            emptyList()
        }
        if (selectedTurnIndex < 0 || liveSelectedPrefix != frozenPrefix) {
            return ToolLoopSnapshotProjection.Invalid(
                ToolLoopSnapshotInvalidation.HISTORICAL_PREFIX_CHANGED,
            )
        }
        if (selectedCallOneContext[selectedTurnIndex] != frozenTurnUser) {
            return ToolLoopSnapshotProjection.Invalid(
                ToolLoopSnapshotInvalidation.TURN_BOUNDARY_CHANGED,
            )
        }
        val newerOrdinaryUserExists = liveMessages
            .subList(turnIndex + 1, liveMessages.size)
            .any(UIMessage::isOrdinaryUserMessage)
        if (newerOrdinaryUserExists) {
            return ToolLoopSnapshotProjection.Invalid(
                ToolLoopSnapshotInvalidation.NEWER_USER_TURN,
            )
        }

        val liveTail = liveMessages.subList(turnIndex, liveMessages.size)
        val liveTailIds = liveTail.mapTo(hashSetOf(), UIMessage::id)
        if (frozenPrefix.any { it.id in liveTailIds }) {
            return ToolLoopSnapshotProjection.Invalid(
                ToolLoopSnapshotInvalidation.PREFIX_ID_CONFLICT,
            )
        }
        return ToolLoopSnapshotProjection.Valid(frozenPrefix + liveTail)
    }

    companion object {
        /**
         * Captures the history selection used by the first provider call of this user turn.
         *
         * A continuation is normally captured after an assistant/tool message has already been
         * appended. Excluding that live tail before applying the user's ordinary message limit is
         * essential: at a limit boundary, re-running the limit on the larger call-2 list can move
         * the history boundary even though call 1 sent a different prefix.
         */
        fun capture(
            liveMessages: List<UIMessage>,
            ordinaryMessageLimit: Int = 0,
        ): ToolLoopContinuationSnapshot? {
            val turnIndex = liveMessages.indexOfLast(UIMessage::isOrdinaryUserMessage)
            if (turnIndex < 0) return null
            val turnUser = liveMessages[turnIndex]
            val selected = liveMessages
                .take(turnIndex + 1)
                .selectOrdinaryChatContext(ordinaryMessageLimit)
            val selectedTurnIndex = selected.indexOfLast { it.id == turnUser.id }
            if (selectedTurnIndex < 0) return null
            return ToolLoopContinuationSnapshot(
                turnUserMessageId = turnUser.id,
                frozenPrefix = selected.take(selectedTurnIndex),
                frozenTurnUser = turnUser,
                ordinaryMessageLimit = ordinaryMessageLimit,
            )
        }
    }
}

internal sealed interface ToolLoopSnapshotProjection {
    data class Valid(val messages: List<UIMessage>) : ToolLoopSnapshotProjection
    data class Invalid(val reason: ToolLoopSnapshotInvalidation) : ToolLoopSnapshotProjection
}

internal enum class ToolLoopSnapshotInvalidation {
    TURN_BOUNDARY_MISSING,
    TURN_BOUNDARY_DUPLICATED,
    TURN_BOUNDARY_CHANGED,
    NEWER_USER_TURN,
    PREFIX_ID_CONFLICT,
    HISTORICAL_PREFIX_CHANGED,
}

private fun UIMessage.isOrdinaryUserMessage(): Boolean =
    role == MessageRole.USER && annotations.none { it is UIMessageAnnotation.Steering }
