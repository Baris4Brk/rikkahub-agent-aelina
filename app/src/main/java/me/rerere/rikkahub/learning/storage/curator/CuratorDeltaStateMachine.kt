package me.rerere.rikkahub.learning.storage.curator

internal object CuratorDeltaStateMachine {
    fun requireTransition(
        expected: CuratorDeltaCandidateEntity,
        next: CuratorDeltaCandidateEntity,
        reason: CuratorDeltaRevisionReason,
        actor: CuratorDeltaRevisionActor,
    ) {
        require(expected.id == next.id)
        require(expected.stateVersion < Long.MAX_VALUE)
        require(next.stateVersion == expected.stateVersion + 1L)
        require(next.updatedAtMs >= expected.updatedAtMs)
        require(next.state != CuratorDeltaStoredState.REDACTED_SOURCE.name) {
            "Source redaction must use the dedicated exact-source operation"
        }
        val from = CuratorDeltaStoredState.valueOf(expected.state)
        val to = CuratorDeltaStoredState.valueOf(next.state)
        requireImmutableCandidateFields(expected, next)
        require(to in allowedTargets(from)) { "Invalid Curator delta state transition" }
        require(reason.matches(to)) { "Revision reason does not match transition" }
        require(actor.matches(reason)) { "Revision actor does not match reason" }
        if (from == CuratorDeltaStoredState.APPROVED && to == CuratorDeltaStoredState.APPLYING) {
            require(expected.applyPlanId == null && next.applyPlanId != null)
            requireNotNull(next.decodeApplyPlanOrNull())
        } else {
            require(next.applyPlanId == expected.applyPlanId &&
                next.applyPlanWire == expected.applyPlanWire &&
                next.applyPlanSha256 == expected.applyPlanSha256) {
                "Only APPROVED to APPLYING may attach an immutable apply plan"
            }
        }
    }

    private fun requireImmutableCandidateFields(
        expected: CuratorDeltaCandidateEntity,
        next: CuratorDeltaCandidateEntity,
    ) {
        require(next.copy(
            stateVersion = expected.stateVersion,
            state = expected.state,
            applyPlanId = expected.applyPlanId,
            applyPlanWire = expected.applyPlanWire,
            applyPlanSha256 = expected.applyPlanSha256,
            conflictCode = expected.conflictCode,
            updatedAtMs = expected.updatedAtMs,
        ) == expected) { "Lifecycle transition changed immutable Curator delta content" }
    }

    private fun allowedTargets(from: CuratorDeltaStoredState): Set<CuratorDeltaStoredState> =
        when (from) {
            CuratorDeltaStoredState.PROPOSED -> setOf(
                CuratorDeltaStoredState.APPROVED,
                CuratorDeltaStoredState.REJECTED,
                CuratorDeltaStoredState.ARCHIVED,
            )
            CuratorDeltaStoredState.APPROVED -> setOf(
                CuratorDeltaStoredState.APPLYING,
                CuratorDeltaStoredState.REJECTED,
                CuratorDeltaStoredState.ARCHIVED,
            )
            CuratorDeltaStoredState.APPLYING -> setOf(
                CuratorDeltaStoredState.APPLIED,
                CuratorDeltaStoredState.APPLY_CONFLICT,
            )
            CuratorDeltaStoredState.APPLIED -> setOf(CuratorDeltaStoredState.ROLLING_BACK)
            CuratorDeltaStoredState.ROLLING_BACK -> setOf(
                CuratorDeltaStoredState.ROLLED_BACK,
                CuratorDeltaStoredState.ROLLBACK_CONFLICT,
            )
            CuratorDeltaStoredState.ROLLBACK_CONFLICT -> setOf(
                CuratorDeltaStoredState.ROLLING_BACK,
                CuratorDeltaStoredState.ARCHIVED,
            )
            CuratorDeltaStoredState.APPLY_CONFLICT,
            CuratorDeltaStoredState.REJECTED,
            CuratorDeltaStoredState.ROLLED_BACK,
            -> setOf(CuratorDeltaStoredState.ARCHIVED)
            CuratorDeltaStoredState.ARCHIVED,
            CuratorDeltaStoredState.REDACTED_SOURCE,
            -> emptySet()
        }

    private fun CuratorDeltaRevisionReason.matches(to: CuratorDeltaStoredState): Boolean = when (to) {
        CuratorDeltaStoredState.APPROVED -> this == CuratorDeltaRevisionReason.USER_APPROVED
        CuratorDeltaStoredState.REJECTED -> this == CuratorDeltaRevisionReason.USER_REJECTED
        CuratorDeltaStoredState.APPLYING -> this == CuratorDeltaRevisionReason.APPLY_STARTED
        CuratorDeltaStoredState.APPLIED -> this == CuratorDeltaRevisionReason.APPLY_COMMITTED
        CuratorDeltaStoredState.APPLY_CONFLICT -> this == CuratorDeltaRevisionReason.APPLY_CONFLICT
        CuratorDeltaStoredState.ROLLING_BACK -> this == CuratorDeltaRevisionReason.ROLLBACK_STARTED
        CuratorDeltaStoredState.ROLLED_BACK -> this == CuratorDeltaRevisionReason.ROLLBACK_COMMITTED
        CuratorDeltaStoredState.ROLLBACK_CONFLICT ->
            this == CuratorDeltaRevisionReason.ROLLBACK_CONFLICT
        CuratorDeltaStoredState.ARCHIVED -> this == CuratorDeltaRevisionReason.ARCHIVED
        CuratorDeltaStoredState.PROPOSED,
        CuratorDeltaStoredState.REDACTED_SOURCE,
        -> false
    }

    private fun CuratorDeltaRevisionActor.matches(reason: CuratorDeltaRevisionReason): Boolean =
        when (reason) {
            CuratorDeltaRevisionReason.USER_APPROVED,
            CuratorDeltaRevisionReason.USER_REJECTED,
            -> this == CuratorDeltaRevisionActor.USER
            CuratorDeltaRevisionReason.APPLY_STARTED,
            CuratorDeltaRevisionReason.APPLY_COMMITTED,
            CuratorDeltaRevisionReason.APPLY_CONFLICT,
            -> this == CuratorDeltaRevisionActor.APPLY_ENGINE
            CuratorDeltaRevisionReason.ROLLBACK_STARTED,
            CuratorDeltaRevisionReason.ROLLBACK_COMMITTED,
            CuratorDeltaRevisionReason.ROLLBACK_CONFLICT,
            -> this == CuratorDeltaRevisionActor.ROLLBACK_ENGINE
            CuratorDeltaRevisionReason.ARCHIVED ->
                this == CuratorDeltaRevisionActor.USER ||
                    this == CuratorDeltaRevisionActor.RETENTION
            CuratorDeltaRevisionReason.CREATED ->
                this == CuratorDeltaRevisionActor.CURATOR_MODEL ||
                    this == CuratorDeltaRevisionActor.USER
            CuratorDeltaRevisionReason.SOURCE_REDACTED ->
                this == CuratorDeltaRevisionActor.PRIVACY
        }
}
