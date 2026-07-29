package me.rerere.rikkahub.pet

import me.rerere.rikkahub.assistant.SecondUserPresentationStatus
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.PetActionId
import me.rerere.rikkahub.pet.behavior.PetActionSource
import me.rerere.rikkahub.pet.behavior.PetBehaviorIntent
import me.rerere.rikkahub.pet.behavior.PetBehaviorPriority

enum class PetStatusBadge {
    QUESTION,
    FAILURE,
    SERVICE,
}

data class PetPresentationMapping(
    val action: PetActionId,
    val source: PetActionSource,
    val priority: PetBehaviorPriority,
    val badge: PetStatusBadge? = null,
) {
    fun asIntent(): PetBehaviorIntent.Operational = PetBehaviorIntent.Operational(
        action = action,
        source = source,
        priority = priority,
    )
}

/** Maps trusted P0 state to semantic intent; it never asks the renderer for a concrete sprite. */
object PetPresentationMapper {
    fun mapping(status: SecondUserPresentationStatus): PetPresentationMapping = when (status) {
        SecondUserPresentationStatus.SAFETY_BLOCKED -> PetPresentationMapping(
            CorePetActions.FAILURE,
            PetActionSource.SAFETY,
            PetBehaviorPriority.SAFETY,
            PetStatusBadge.FAILURE,
        )
        SecondUserPresentationStatus.WAITING_APPROVAL -> PetPresentationMapping(
            CorePetActions.WAIT,
            PetActionSource.APPROVAL,
            PetBehaviorPriority.APPROVAL,
        )
        SecondUserPresentationStatus.CANCEL_REQUESTED -> PetPresentationMapping(
            CorePetActions.WAIT,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.TERMINATING,
        )
        SecondUserPresentationStatus.TERMINATING,
        SecondUserPresentationStatus.RECOVERING,
        -> PetPresentationMapping(
            CorePetActions.REVIEW,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.TERMINATING,
        )
        SecondUserPresentationStatus.FAILED_RECENTLY -> PetPresentationMapping(
            CorePetActions.FAILURE,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.FAILED,
            PetStatusBadge.FAILURE,
        )
        SecondUserPresentationStatus.TOOL_RUNNING -> PetPresentationMapping(
            CorePetActions.WORK,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.TOOL,
        )
        SecondUserPresentationStatus.MODEL_GENERATING -> PetPresentationMapping(
            CorePetActions.REVIEW,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.MODEL,
        )
        SecondUserPresentationStatus.QUEUED -> PetPresentationMapping(
            CorePetActions.WAIT,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.QUEUED,
        )
        SecondUserPresentationStatus.STALE -> PetPresentationMapping(
            CorePetActions.WAIT,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.QUEUED,
            PetStatusBadge.QUESTION,
        )
        SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING -> PetPresentationMapping(
            CorePetActions.IDLE,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.IDLE,
            PetStatusBadge.SERVICE,
        )
        SecondUserPresentationStatus.SUCCEEDED_RECENTLY,
        SecondUserPresentationStatus.IDLE,
        -> PetPresentationMapping(
            CorePetActions.IDLE,
            PetActionSource.AGENT_OPERATION,
            PetBehaviorPriority.IDLE,
        )
    }

    /** Compatibility for older callers and persisted test fixtures. New code uses [mapping]. */
    fun action(status: SecondUserPresentationStatus): PetAction = when (mapping(status).action) {
        CorePetActions.MOVE_RIGHT -> PetAction.RUNNING_RIGHT
        CorePetActions.MOVE_LEFT -> PetAction.RUNNING_LEFT
        CorePetActions.WAVE -> PetAction.WAVING
        CorePetActions.JUMP -> PetAction.JUMPING
        CorePetActions.FAILURE -> PetAction.FAILED
        CorePetActions.WAIT -> PetAction.WAITING
        CorePetActions.WORK -> PetAction.RUNNING
        CorePetActions.REVIEW -> PetAction.REVIEW
        else -> PetAction.IDLE
    }

    fun bubble(status: SecondUserPresentationStatus): String? = when (status) {
        SecondUserPresentationStatus.SAFETY_BLOCKED -> "安全暂停"
        SecondUserPresentationStatus.WAITING_APPROVAL -> "等待授权"
        SecondUserPresentationStatus.CANCEL_REQUESTED,
        SecondUserPresentationStatus.TERMINATING,
        -> "正在停止"
        SecondUserPresentationStatus.RECOVERING -> "正在恢复状态"
        SecondUserPresentationStatus.STALE -> "状态待确认"
        else -> null
    }
}
