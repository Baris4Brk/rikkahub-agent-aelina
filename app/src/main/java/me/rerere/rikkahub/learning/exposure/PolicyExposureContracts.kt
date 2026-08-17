package me.rerere.rikkahub.learning.exposure

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope

private const val MAX_EXPOSURE_BUNDLE_POLICIES = 20
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val POLICY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
private val NIL_UUID = Uuid.parse("00000000-0000-0000-0000-000000000000")

/** The seven P2 milestones. Attempt terminal facts are stored separately. */
enum class PolicyExposureState {
    RETRIEVED,
    COMPILED,
    INJECTED,
    HOST_DISPATCHED,
    FIRST_PROGRESS,
    RESPONSE_FINISHED,
    OUTCOME_LINKED,
}

/** Exact identity of one Policy included in the final attributed bundle. */
data class PolicyExposurePolicyRef(
    val policyId: String,
    val policyRevision: Long,
    val artifactSha256: String,
    val scope: LearningScope,
    /** One-based final order in the compiled untrusted Policy section. */
    val rank: Int,
    val estimatedTokens: Int,
    val applicabilityCohortDigest: String,
) {
    init {
        require(policyId.matches(POLICY_ID_PATTERN)) { "Invalid Policy ID" }
        require(policyRevision > 0L) { "Invalid Policy revision" }
        require(artifactSha256.matches(SHA256_PATTERN)) { "Invalid Policy artifact digest" }
        require(rank in 1..MAX_EXPOSURE_BUNDLE_POLICIES) {
            "Policy exposure rank is out of bounds"
        }
        require(estimatedTokens in 1..4_096) {
            "Policy exposure token estimate is out of bounds"
        }
        require(applicabilityCohortDigest.matches(SHA256_PATTERN))
    }

    internal fun canonicalDigest(): String = LearningCanonicalId.digest(
        domainVersion = "policy-exposure-item-v1",
        fields = listOf(
            policyId,
            policyRevision.toString(),
            artifactSha256,
            scope.kind.name,
            scope.storageId,
            rank.toString(),
            estimatedTokens.toString(),
            applicabilityCohortDigest,
        ),
    )

    override fun toString(): String =
        "PolicyExposurePolicyRef(revision=$policyRevision, scope=${scope.kind}, rank=$rank, " +
            "tokens=$estimatedTokens, ids=<redacted>)"
}

/**
 * Immutable, deterministically ordered unit of attribution.
 *
 * Provider outcome and utility are attributed to this bundle. They are never copied onto every
 * member Policy as if an observational multi-Policy request identified an individual effect.
 */
class PolicyExposureBundle private constructor(
    policies: List<PolicyExposurePolicyRef>,
) {
    val policies: List<PolicyExposurePolicyRef> = policies.toList()

    val policySetDigest: String = LearningCanonicalId.digest(
        domainVersion = "policy-exposure-set-v1",
        fields = listOf(this.policies.size.toString()) +
            this.policies.map(PolicyExposurePolicyRef::canonicalDigest),
    )

    override fun equals(other: Any?): Boolean =
        other is PolicyExposureBundle && policies == other.policies

    override fun hashCode(): Int = policies.hashCode()

    override fun toString(): String =
        "PolicyExposureBundle(size=${policies.size}, ids=<redacted>)"

    companion object {
        fun create(policies: List<PolicyExposurePolicyRef>): PolicyExposureBundle {
            require(policies.isNotEmpty()) { "Policy exposure bundle cannot be empty" }
            require(policies.size <= MAX_EXPOSURE_BUNDLE_POLICIES) {
                "Policy exposure bundle is unbounded"
            }
            require(policies.map { it.policyId }.distinct().size == policies.size) {
                "Policy exposure bundle contains duplicate Policy identity"
            }
            require(policies.map { it.rank }.distinct().size == policies.size) {
                "Policy exposure bundle contains duplicate rank"
            }
            require(policies.map { it.scope }.distinct().size == 1) {
                "Policy exposure bundle crosses a scope boundary"
            }
            val ordered = policies.sortedWith(
                compareBy<PolicyExposurePolicyRef> { it.rank }
                    .thenBy { it.policyId }
                    .thenBy { it.policyRevision },
            )
            return PolicyExposureBundle(ordered)
        }
    }
}

/**
 * Durable uniqueness boundary for one Policy-bearing provider attempt.
 *
 * All five required dimensions participate in [reservationId]. A watchdog retry keeps the same
 * stream, Episode, logical run, and bundle, but receives a fresh [attemptOrdinal].
 */
data class PolicyExposureReservationKey(
    val streamId: Uuid,
    val episodeId: EpisodeId,
    val logicalRunId: Uuid,
    val attemptOrdinal: Int,
    val policySetDigest: String,
) {
    init {
        require(streamId != NIL_UUID) { "Learning stream ID cannot be nil" }
        require(logicalRunId != NIL_UUID) { "Logical run ID cannot be nil" }
        require(attemptOrdinal > 0) { "Policy exposure attempt ordinal must be positive" }
        require(policySetDigest.matches(SHA256_PATTERN)) { "Invalid Policy set digest" }
    }

    val reservationId: String = "policy-exposure-v1:" + LearningCanonicalId.digest(
        domainVersion = "policy-exposure-reservation-v1",
        fields = listOf(
            streamId.toString(),
            episodeId.value,
            logicalRunId.toString(),
            attemptOrdinal.toString(),
            policySetDigest,
        ),
    )

    fun nextRetry(): PolicyExposureReservationKey {
        require(attemptOrdinal < Int.MAX_VALUE) { "Policy exposure attempt ordinal overflow" }
        return copy(attemptOrdinal = attemptOrdinal + 1)
    }

    override fun toString(): String =
        "PolicyExposureReservationKey(attempt=$attemptOrdinal, ids=<redacted>)"
}

/** Request to durably reserve one final Policy-bearing provider request. */
data class PolicyExposureReservation(
    val key: PolicyExposureReservationKey,
    val bundle: PolicyExposureBundle,
) {
    init {
        require(key.policySetDigest == bundle.policySetDigest) {
            "Policy exposure key does not identify its bundle"
        }
    }

    fun nextRetry(): PolicyExposureReservation = copy(key = key.nextRetry())

    override fun toString(): String =
        "PolicyExposureReservation(attempt=${key.attemptOrdinal}, bundle=${bundle.policies.size}, " +
            "ids=<redacted>)"
}

/**
 * Validated snapshot returned only after a reservation has succeeded.
 *
 * [observedStates] is a milestone set rather than one vague projected state. This preserves the
 * distinction between an adapter response that finished without meaningful progress, a dispatched
 * attempt that failed, and an outcome that was linked only after authoritative commit.
 */
class PolicyExposureReceipt private constructor(
    val reservation: PolicyExposureReservation,
    observedStates: Set<PolicyExposureState>,
    val stateVersion: Long,
    val terminalOutcome: ProviderAttemptTerminalOutcome?,
) {
    val observedStates: Set<PolicyExposureState> = observedStates.toSet()

    init {
        require(stateVersion >= 0L) { "Negative Policy exposure state version" }
        validateMilestones(this.observedStates, stateVersion, terminalOutcome)
    }

    val latestState: PolicyExposureState
        get() = checkNotNull(observedStates.maxByOrNull { it.ordinal })

    val canAttributeUsage: Boolean
        get() = PolicyExposureState.HOST_DISPATCHED in observedStates

    val canAttributeOutcome: Boolean
        get() = PolicyExposureState.OUTCOME_LINKED in observedStates

    val canAttributeObservedUtility: Boolean
        get() = canAttributeOutcome && (
            PolicyExposureState.FIRST_PROGRESS in observedStates ||
                PolicyExposureState.RESPONSE_FINISHED in observedStates
            )

    fun hasObserved(state: PolicyExposureState): Boolean = state in observedStates

    internal fun withState(state: PolicyExposureState): PolicyExposureReceipt =
        PolicyExposureReceipt(
            reservation = reservation,
            observedStates = observedStates + state,
            stateVersion = stateVersion + 1L,
            terminalOutcome = terminalOutcome,
        )

    internal fun withTerminal(outcome: ProviderAttemptTerminalOutcome): PolicyExposureReceipt =
        PolicyExposureReceipt(
            reservation = reservation,
            observedStates = observedStates,
            stateVersion = stateVersion + 1L,
            terminalOutcome = outcome,
        )

    override fun equals(other: Any?): Boolean =
        other is PolicyExposureReceipt &&
            reservation == other.reservation &&
            observedStates == other.observedStates &&
            stateVersion == other.stateVersion &&
            terminalOutcome == other.terminalOutcome

    override fun hashCode(): Int {
        var result = reservation.hashCode()
        result = 31 * result + observedStates.hashCode()
        result = 31 * result + stateVersion.hashCode()
        result = 31 * result + (terminalOutcome?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PolicyExposureReceipt(states=${observedStates.sortedBy { it.ordinal }}, " +
            "version=$stateVersion, terminal=$terminalOutcome, " +
            "bundle=${reservation.bundle.policies.size}, ids=<redacted>)"

    companion object {
        fun initial(reservation: PolicyExposureReservation): PolicyExposureReceipt =
            PolicyExposureReceipt(
                reservation = reservation,
                observedStates = setOf(PolicyExposureState.RETRIEVED),
                stateVersion = 0L,
                terminalOutcome = null,
            )

        /** Rehydrates a durable row while applying the same fail-closed invariants. */
        fun restore(
            reservation: PolicyExposureReservation,
            observedStates: Set<PolicyExposureState>,
            stateVersion: Long,
            terminalOutcome: ProviderAttemptTerminalOutcome?,
        ): PolicyExposureReceipt = PolicyExposureReceipt(
            reservation = reservation,
            observedStates = observedStates,
            stateVersion = stateVersion,
            terminalOutcome = terminalOutcome,
        )
    }
}

enum class PolicyExposureRejectionReason {
    STATE_VERSION_MISMATCH,
    MISSING_PREREQUISITE,
    NON_MONOTONIC_STATE,
    ATTEMPT_ALREADY_TERMINAL,
    TERMINAL_CONFLICT,
    INVALID_TERMINAL,
    OUTCOME_LINK_REQUIRES_TERMINAL,
    OUTCOME_LINK_REQUIRES_DISPATCH,
    OUTCOME_ALREADY_LINKED,
}

sealed interface PolicyExposureMutationResult {
    val receipt: PolicyExposureReceipt

    data class Applied(
        override val receipt: PolicyExposureReceipt,
    ) : PolicyExposureMutationResult

    /** The exact milestone/terminal fact was already durably present; no revision was consumed. */
    data class Duplicate(
        override val receipt: PolicyExposureReceipt,
    ) : PolicyExposureMutationResult

    data class Rejected(
        override val receipt: PolicyExposureReceipt,
        val reason: PolicyExposureRejectionReason,
    ) : PolicyExposureMutationResult
}

/** Pure expected-revision state machine used by both Room adapters and JVM tests. */
object PolicyExposureStateMachine {
    fun observe(
        receipt: PolicyExposureReceipt,
        expectedStateVersion: Long,
        state: PolicyExposureState,
    ): PolicyExposureMutationResult {
        if (state in receipt.observedStates) {
            return PolicyExposureMutationResult.Duplicate(receipt)
        }
        if (expectedStateVersion != receipt.stateVersion) {
            return receipt.rejected(PolicyExposureRejectionReason.STATE_VERSION_MISMATCH)
        }
        if (PolicyExposureState.OUTCOME_LINKED in receipt.observedStates) {
            return receipt.rejected(PolicyExposureRejectionReason.OUTCOME_ALREADY_LINKED)
        }
        if (state == PolicyExposureState.OUTCOME_LINKED) {
            if (receipt.terminalOutcome == null) {
                return receipt.rejected(
                    PolicyExposureRejectionReason.OUTCOME_LINK_REQUIRES_TERMINAL,
                )
            }
            if (PolicyExposureState.HOST_DISPATCHED !in receipt.observedStates) {
                return receipt.rejected(
                    PolicyExposureRejectionReason.OUTCOME_LINK_REQUIRES_DISPATCH,
                )
            }
            return PolicyExposureMutationResult.Applied(receipt.withState(state))
        }
        if (receipt.terminalOutcome != null) {
            return receipt.rejected(PolicyExposureRejectionReason.ATTEMPT_ALREADY_TERMINAL)
        }
        val prerequisites = STATE_PREREQUISITES.getValue(state)
        if (!receipt.observedStates.containsAll(prerequisites)) {
            return receipt.rejected(PolicyExposureRejectionReason.MISSING_PREREQUISITE)
        }
        if (state.ordinal <= receipt.latestState.ordinal) {
            return receipt.rejected(PolicyExposureRejectionReason.NON_MONOTONIC_STATE)
        }
        return PolicyExposureMutationResult.Applied(receipt.withState(state))
    }

    fun recordTerminal(
        receipt: PolicyExposureReceipt,
        expectedStateVersion: Long,
        outcome: ProviderAttemptTerminalOutcome,
    ): PolicyExposureMutationResult {
        receipt.terminalOutcome?.let { existing ->
            return if (existing == outcome) {
                PolicyExposureMutationResult.Duplicate(receipt)
            } else {
                receipt.rejected(PolicyExposureRejectionReason.TERMINAL_CONFLICT)
            }
        }
        if (expectedStateVersion != receipt.stateVersion) {
            return receipt.rejected(PolicyExposureRejectionReason.STATE_VERSION_MISMATCH)
        }
        if (PolicyExposureState.INJECTED !in receipt.observedStates) {
            return receipt.rejected(PolicyExposureRejectionReason.INVALID_TERMINAL)
        }
        val terminalIsValid = when (outcome) {
            ProviderAttemptTerminalOutcome.COMPLETED ->
                PolicyExposureState.RESPONSE_FINISHED in receipt.observedStates
            ProviderAttemptTerminalOutcome.STALLED_RETRY ->
                PolicyExposureState.HOST_DISPATCHED in receipt.observedStates &&
                    PolicyExposureState.RESPONSE_FINISHED !in receipt.observedStates
            ProviderAttemptTerminalOutcome.FAILED,
            ProviderAttemptTerminalOutcome.CANCELLED,
            ProviderAttemptTerminalOutcome.STEERING_CANCELLED,
            -> true
        }
        if (!terminalIsValid) {
            return receipt.rejected(PolicyExposureRejectionReason.INVALID_TERMINAL)
        }
        return PolicyExposureMutationResult.Applied(receipt.withTerminal(outcome))
    }
}

private val STATE_PREREQUISITES: Map<PolicyExposureState, Set<PolicyExposureState>> = mapOf(
    PolicyExposureState.RETRIEVED to emptySet(),
    PolicyExposureState.COMPILED to setOf(PolicyExposureState.RETRIEVED),
    PolicyExposureState.INJECTED to setOf(PolicyExposureState.COMPILED),
    PolicyExposureState.HOST_DISPATCHED to setOf(PolicyExposureState.INJECTED),
    PolicyExposureState.FIRST_PROGRESS to setOf(PolicyExposureState.HOST_DISPATCHED),
    // A provider can return an empty/non-meaningful response, so progress is not a prerequisite.
    PolicyExposureState.RESPONSE_FINISHED to setOf(PolicyExposureState.HOST_DISPATCHED),
    // OUTCOME_LINKED has authority/terminal prerequisites handled explicitly by the state machine.
    PolicyExposureState.OUTCOME_LINKED to emptySet(),
)

private fun PolicyExposureReceipt.rejected(
    reason: PolicyExposureRejectionReason,
): PolicyExposureMutationResult.Rejected = PolicyExposureMutationResult.Rejected(this, reason)

private fun validateMilestones(
    states: Set<PolicyExposureState>,
    stateVersion: Long,
    terminalOutcome: ProviderAttemptTerminalOutcome?,
) {
    require(PolicyExposureState.RETRIEVED in states) {
        "Policy exposure receipt is missing RETRIEVED"
    }
    val minimumStateVersion = (states.size - 1).toLong() + if (terminalOutcome == null) 0L else 1L
    require(stateVersion >= minimumStateVersion) {
        "Policy exposure state version predates its durable milestones"
    }
    states.forEach { state ->
        require(states.containsAll(STATE_PREREQUISITES.getValue(state))) {
            "Policy exposure receipt has an invalid milestone graph"
        }
    }
    if (terminalOutcome != null) {
        require(PolicyExposureState.INJECTED in states) {
            "Terminal Policy attempt was never injected"
        }
    }
    if (PolicyExposureState.OUTCOME_LINKED in states) {
        require(terminalOutcome != null) { "Linked Policy outcome has no attempt terminal" }
        require(PolicyExposureState.HOST_DISPATCHED in states) {
            "Linked Policy outcome was never dispatched"
        }
    }
    if (terminalOutcome == ProviderAttemptTerminalOutcome.COMPLETED) {
        require(PolicyExposureState.RESPONSE_FINISHED in states) {
            "Completed Policy attempt has no finished response"
        }
    }
    if (terminalOutcome == ProviderAttemptTerminalOutcome.STALLED_RETRY) {
        require(PolicyExposureState.HOST_DISPATCHED in states) {
            "Stalled Policy attempt was never dispatched"
        }
        require(PolicyExposureState.RESPONSE_FINISHED !in states) {
            "A finished response cannot be a stalled retry"
        }
    }
}
