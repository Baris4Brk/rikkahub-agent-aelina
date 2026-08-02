package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface CommandOutcome {
    data object Completed : CommandOutcome
    data object Cancelled : CommandOutcome
    data class Superseded(val byCommandId: Uuid) : CommandOutcome
    data class Rejected(val reason: String) : CommandOutcome
    data class Conflict(val reason: String) : CommandOutcome
    data class NotApplied(val reason: String) : CommandOutcome
    data class Failed(val error: Throwable) : CommandOutcome
    data class SkippedDependencyFailed(val dependencyId: Uuid) : CommandOutcome
}

/**
 * A failure whose durable representation is deliberately stable and safe to show in Doctor/UI.
 * Implementations must never place prompt text, tool arguments, provider bodies, or credentials
 * in either field.
 */
interface DurableCommandFailure {
    val durableErrorCode: String
    val durableErrorMessage: String
}

class StableCommandException(
    override val durableErrorCode: String,
    override val durableErrorMessage: String,
) : IllegalStateException(durableErrorMessage), DurableCommandFailure

sealed interface SubmitResult {
    data class Accepted(val commandId: Uuid) : SubmitResult
    data class QueueFull(val limit: Int) : SubmitResult
    data class RuntimeUnavailable(val reason: String) : SubmitResult
    data class Rejected(val reason: String) : SubmitResult
}

data class CommandEnvelope<C : ChatCommand>(
    val id: Uuid = Uuid.random(),
    val conversationId: Uuid,
    val command: C,
    val origin: CommandOrigin,
    val createdAt: Instant = Clock.System.now(),
    val sequence: Long,
    val expiresAt: Instant? = null,
    val dedupeKey: String? = null,
    val dependencies: List<CommandDependency> = emptyList(),
    val result: CompletableDeferred<CommandOutcome> = CompletableDeferred(),
)
