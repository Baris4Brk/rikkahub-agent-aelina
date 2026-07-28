package me.rerere.rikkahub.pet

import me.rerere.rikkahub.assistant.SecondUserPresentationStatus

object PetPresentationMapper {
    fun action(status: SecondUserPresentationStatus): PetAction = when (status) {
        SecondUserPresentationStatus.SAFETY_BLOCKED,
        SecondUserPresentationStatus.FAILED_RECENTLY,
        -> PetAction.FAILED
        SecondUserPresentationStatus.WAITING_APPROVAL -> PetAction.REVIEW
        SecondUserPresentationStatus.CANCEL_REQUESTED,
        SecondUserPresentationStatus.TERMINATING,
        SecondUserPresentationStatus.RECOVERING,
        SecondUserPresentationStatus.STALE,
        -> PetAction.WAITING
        SecondUserPresentationStatus.TOOL_RUNNING,
        SecondUserPresentationStatus.MODEL_GENERATING,
        SecondUserPresentationStatus.QUEUED,
        -> PetAction.RUNNING
        SecondUserPresentationStatus.SUCCEEDED_RECENTLY -> PetAction.WAVING
        SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING,
        SecondUserPresentationStatus.IDLE,
        -> PetAction.IDLE
    }

    fun bubble(status: SecondUserPresentationStatus): String? = when (status) {
        SecondUserPresentationStatus.SAFETY_BLOCKED -> "已安全暂停"
        SecondUserPresentationStatus.WAITING_APPROVAL -> "等待授权"
        SecondUserPresentationStatus.CANCEL_REQUESTED,
        SecondUserPresentationStatus.TERMINATING,
        -> "正在停止"
        SecondUserPresentationStatus.RECOVERING -> "正在恢复状态"
        SecondUserPresentationStatus.STALE -> "状态待确认"
        else -> null
    }
}
