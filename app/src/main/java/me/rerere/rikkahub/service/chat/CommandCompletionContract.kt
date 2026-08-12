package me.rerere.rikkahub.service.chat

/**
 * Durable meaning of a command checkpoint or terminal transition.
 *
 * This enum belongs to command authority. Derived Learning code may map the stable wire names to
 * its own model, but command storage must never import a Learning type to validate authority rows.
 */
enum class CommandCompletionKind {
    GENERATION_WAITING_APPROVAL,
    GENERATION_FINAL_SAVED,
    FAST_PATH_HANDLED,
    CONTROL_ONLY,
    CENSORED_CANCELLED,
    SUPERSEDED_REGENERATE,
    FAILED_FINAL_SAVE,
    FAILED_OTHER,
    ;

    companion object {
        /** Unknown future values are deliberately not guessed into an existing meaning. */
        fun parseOrNull(raw: String?): CommandCompletionKind? =
            raw?.let { code -> entries.firstOrNull { it.name == code } }
    }
}

enum class CommandCompletionPhase {
    WAITING,
    TERMINAL,
}

/** Exact message authority pair. A digest or wall-clock timestamp is not a revision token. */
data class CommandResultMessageAuthority(
    val messageId: String,
    val messageRevision: Long,
) {
    init {
        require(messageId.isCommandAuthorityIdentifier()) { "Invalid command result message ID" }
        require(messageRevision > 0L) { "Command result message revision must be positive" }
    }

    override fun toString(): String =
        "CommandResultMessageAuthority(revision=$messageRevision, id=<redacted>)"
}

data class CommandCompletionAuthority(
    val kind: CommandCompletionKind,
    val phase: CommandCompletionPhase,
    val commandState: DurableCommandState,
    val resultMessage: CommandResultMessageAuthority?,
) {
    init {
        CommandCompletionContract.requireValid(this)
    }

    override fun toString(): String =
        "CommandCompletionAuthority(kind=$kind, phase=$phase, state=$commandState, " +
            "result=${resultMessage != null})"
}

enum class CommandCompletionViolation {
    WAITING_KIND_REQUIRES_WAITING_PHASE,
    WAITING_PHASE_REQUIRES_WAITING_KIND,
    WAITING_STATE_REQUIRED,
    TERMINAL_STATE_REQUIRED,
    FINAL_SAVED_STATE_INVALID,
    FAST_PATH_STATE_INVALID,
    CONTROL_STATE_INVALID,
    CANCELLED_STATE_REQUIRED,
    FAILED_STATE_REQUIRED,
    RESULT_MESSAGE_REQUIRED,
    RESULT_MESSAGE_FORBIDDEN,
}

object CommandCompletionContract {
    fun violationOrNull(value: CommandCompletionAuthority): CommandCompletionViolation? =
        violationOrNull(
            kind = value.kind,
            phase = value.phase,
            commandState = value.commandState,
            resultMessage = value.resultMessage,
        )

    fun violationOrNull(
        kind: CommandCompletionKind,
        phase: CommandCompletionPhase,
        commandState: DurableCommandState,
        resultMessage: CommandResultMessageAuthority?,
    ): CommandCompletionViolation? {
        if (kind == CommandCompletionKind.GENERATION_WAITING_APPROVAL &&
            phase != CommandCompletionPhase.WAITING
        ) {
            return CommandCompletionViolation.WAITING_KIND_REQUIRES_WAITING_PHASE
        }
        if (phase == CommandCompletionPhase.WAITING &&
            kind != CommandCompletionKind.GENERATION_WAITING_APPROVAL
        ) {
            return CommandCompletionViolation.WAITING_PHASE_REQUIRES_WAITING_KIND
        }
        if (phase == CommandCompletionPhase.WAITING &&
            commandState != DurableCommandState.WAITING_APPROVAL
        ) {
            return CommandCompletionViolation.WAITING_STATE_REQUIRED
        }
        if (phase == CommandCompletionPhase.TERMINAL && !commandState.isTerminal) {
            return CommandCompletionViolation.TERMINAL_STATE_REQUIRED
        }

        val stateViolation = when (kind) {
            CommandCompletionKind.GENERATION_WAITING_APPROVAL -> null
            CommandCompletionKind.GENERATION_FINAL_SAVED -> if (
                commandState !in setOf(DurableCommandState.COMPLETED, DurableCommandState.FAILED)
            ) {
                CommandCompletionViolation.FINAL_SAVED_STATE_INVALID
            } else {
                null
            }
            CommandCompletionKind.FAST_PATH_HANDLED -> if (
                commandState != DurableCommandState.COMPLETED
            ) {
                CommandCompletionViolation.FAST_PATH_STATE_INVALID
            } else {
                null
            }
            CommandCompletionKind.CONTROL_ONLY -> if (
                commandState != DurableCommandState.COMPLETED
            ) {
                CommandCompletionViolation.CONTROL_STATE_INVALID
            } else {
                null
            }
            CommandCompletionKind.CENSORED_CANCELLED,
            CommandCompletionKind.SUPERSEDED_REGENERATE -> if (
                commandState != DurableCommandState.CANCELLED
            ) {
                CommandCompletionViolation.CANCELLED_STATE_REQUIRED
            } else {
                null
            }
            CommandCompletionKind.FAILED_FINAL_SAVE -> if (
                commandState != DurableCommandState.FAILED
            ) {
                CommandCompletionViolation.FAILED_STATE_REQUIRED
            } else {
                null
            }
            CommandCompletionKind.FAILED_OTHER -> if (
                commandState !in setOf(
                    DurableCommandState.FAILED,
                    DurableCommandState.MANUAL_CONFIRMATION,
                )
            ) {
                CommandCompletionViolation.FAILED_STATE_REQUIRED
            } else {
                null
            }
        }
        if (stateViolation != null) return stateViolation

        val resultRequired = kind in setOf(
            CommandCompletionKind.GENERATION_WAITING_APPROVAL,
            CommandCompletionKind.GENERATION_FINAL_SAVED,
            CommandCompletionKind.FAST_PATH_HANDLED,
        )
        return when {
            resultRequired && resultMessage == null ->
                CommandCompletionViolation.RESULT_MESSAGE_REQUIRED
            !resultRequired && resultMessage != null ->
                CommandCompletionViolation.RESULT_MESSAGE_FORBIDDEN
            else -> null
        }
    }

    fun requireValid(value: CommandCompletionAuthority) {
        val violation = violationOrNull(
            kind = value.kind,
            phase = value.phase,
            commandState = value.commandState,
            resultMessage = value.resultMessage,
        )
        require(violation == null) { "Invalid command completion authority: $violation" }
    }
}

internal fun String.isCommandAuthorityIdentifier(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == '.' ||
            char == ':' ||
            char == '@'
    }
