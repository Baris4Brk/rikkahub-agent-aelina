package me.rerere.rikkahub.pet.behavior

import me.rerere.rikkahub.pet.action.PetActionId
import me.rerere.rikkahub.pet.action.ResolvedPetAction

enum class PetActionSource {
    SAFETY,
    APPROVAL,
    AGENT_OPERATION,
    DRAG,
    TOUCH,
    SPEECH,
    DIALOGUE,
    HANDOFF,
    AUTONOMOUS,
    DEBUG,
}

enum class PetBehaviorPriority(val rank: Int) {
    IDLE(10),
    AUTONOMOUS(20),
    QUEUED(35),
    MODEL(40),
    TOOL(50),
    HANDOFF_RESULT(58),
    TOUCH(60),
    SPEAKING(65),
    DRAG(70),
    FAILED(80),
    TERMINATING(85),
    APPROVAL(90),
    SAFETY(100),
}

sealed interface PetReturnPolicy {
    data object ResolveLatestOperationalState : PetReturnPolicy
    data object Stay : PetReturnPolicy
}

data class PetActionRequest(
    val requestId: String,
    val requestedAction: PetActionId,
    val source: PetActionSource,
    val priority: PetBehaviorPriority,
    val persistent: Boolean,
    val minDurationMs: Long,
    val maxDurationMs: Long?,
    val returnPolicy: PetReturnPolicy,
    val createdAtMs: Long,
    val parentRequestId: String? = null,
)

data class PetActionSequenceStep(
    val action: PetActionId,
    val minDurationMs: Long = 700L,
    val maxDurationMs: Long = 1_500L,
)

sealed interface PetBehaviorIntent {
    data class Operational(
        val action: PetActionId,
        val source: PetActionSource,
        val priority: PetBehaviorPriority,
    ) : PetBehaviorIntent

    data class OneShot(
        val action: PetActionId,
        val source: PetActionSource,
        val priority: PetBehaviorPriority,
        val minDurationMs: Long,
        val maxDurationMs: Long?,
        val persistent: Boolean = false,
    ) : PetBehaviorIntent

    data class Sequence(
        val steps: List<PetActionSequenceStep>,
        val source: PetActionSource,
        val priority: PetBehaviorPriority,
    ) : PetBehaviorIntent

    data class ClearSource(val source: PetActionSource) : PetBehaviorIntent
    data object ClearTransient : PetBehaviorIntent
}

data class PetBehaviorState(
    val operationalAction: PetActionRequest?,
    val activeOneShot: PetActionRequest?,
    val queuedOneShots: List<PetActionRequest>,
    val displayedAction: ResolvedPetAction,
    val activeProfileId: String,
    val activeRendererType: String,
    val lastTransitionAtMs: Long,
)

data class PetActionTrace(
    val traceId: String,
    val requestedAction: PetActionId,
    val resolvedAction: PetActionId?,
    val source: PetActionSource,
    val priority: PetBehaviorPriority,
    val accepted: Boolean,
    val rejectionReason: String? = null,
    val fallbackPath: List<PetActionId> = emptyList(),
    val createdAtMs: Long,
)
