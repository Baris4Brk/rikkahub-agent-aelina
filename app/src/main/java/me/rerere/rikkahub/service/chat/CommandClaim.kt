package me.rerere.rikkahub.service.chat

import kotlin.uuid.Uuid

/** Opaque lease capability. A state mutation returns a new token and invalidates the old one. */
class CommandClaim private constructor(
    val commandId: Uuid,
    val workerId: Uuid,
    val stateVersion: Long,
    val leaseUntilMs: Long,
) {
    init {
        require(commandId.toString() != NIL_UUID) { "Command UUID cannot be nil" }
        require(workerId.toString() != NIL_UUID) { "Worker UUID cannot be nil" }
        require(stateVersion > 0L) { "Claim state version must be positive" }
        require(leaseUntilMs >= 0L) { "Claim lease cannot be negative" }
    }

    override fun toString(): String =
        "CommandClaim(version=$stateVersion, leaseUntilMs=$leaseUntilMs, ids=<redacted>)"

    internal companion object {
        fun create(
            commandId: Uuid,
            workerId: Uuid,
            stateVersion: Long,
            leaseUntilMs: Long,
        ): CommandClaim = CommandClaim(commandId, workerId, stateVersion, leaseUntilMs)
    }
}
