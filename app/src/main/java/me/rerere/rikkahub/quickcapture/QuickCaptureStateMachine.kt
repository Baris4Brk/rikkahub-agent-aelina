package me.rerere.rikkahub.quickcapture

/**
 * Small, platform-free transition table used by the overlay coordinator. Keeping this explicit
 * prevents a late callback (for example an Accessibility bitmap after timeout) from reviving a
 * completed or cancelled capture session.
 */
object QuickCaptureStateMachine {
    fun allows(from: QuickCaptureStage, to: QuickCaptureStage): Boolean = when (from) {
        QuickCaptureStage.IDLE -> to == QuickCaptureStage.VALIDATING_TARGET
        QuickCaptureStage.VALIDATING_TARGET -> to in setOf(
            QuickCaptureStage.HIDING_OVERLAY,
            QuickCaptureStage.COLLECTING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.HIDING_OVERLAY -> to in setOf(
            QuickCaptureStage.CAPTURING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.CAPTURING -> to in setOf(
            QuickCaptureStage.SELECTING_REGION,
            QuickCaptureStage.PERSISTING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.SELECTING_REGION -> to in setOf(
            QuickCaptureStage.PERSISTING,
            QuickCaptureStage.COLLECTING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.PERSISTING -> to in setOf(
            QuickCaptureStage.SUBMITTING,
            QuickCaptureStage.COLLECTING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.COLLECTING -> to in setOf(
            QuickCaptureStage.HIDING_OVERLAY,
            QuickCaptureStage.SUBMITTING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.SUBMITTING -> to in setOf(
            QuickCaptureStage.QUEUED,
            QuickCaptureStage.RUNNING,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.QUEUED -> to in setOf(
            QuickCaptureStage.RUNNING,
            QuickCaptureStage.WAITING_APPROVAL,
            QuickCaptureStage.COMPLETED,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.RUNNING -> to in setOf(
            QuickCaptureStage.WAITING_APPROVAL,
            QuickCaptureStage.COMPLETED,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.WAITING_APPROVAL -> to in setOf(
            QuickCaptureStage.RUNNING,
            QuickCaptureStage.COMPLETED,
            QuickCaptureStage.FAILED,
            QuickCaptureStage.IDLE,
        )
        QuickCaptureStage.COMPLETED -> to == QuickCaptureStage.IDLE || to == QuickCaptureStage.VALIDATING_TARGET
        QuickCaptureStage.FAILED -> to in setOf(QuickCaptureStage.IDLE, QuickCaptureStage.VALIDATING_TARGET)
    }
}

/** A tap on the bubble always means capture; opening a conversation is an explicit menu action. */
enum class QuickCaptureSingleTapAction {
    CAPTURE_SINGLE,
    CAPTURE_FOR_BATCH,
    RESET_AND_CAPTURE,
    IGNORE_WHILE_BUSY,
}

fun decideQuickCaptureSingleTap(stage: QuickCaptureStage): QuickCaptureSingleTapAction = when (stage) {
    QuickCaptureStage.IDLE -> QuickCaptureSingleTapAction.CAPTURE_SINGLE
    QuickCaptureStage.COLLECTING -> QuickCaptureSingleTapAction.CAPTURE_FOR_BATCH
    QuickCaptureStage.COMPLETED,
    QuickCaptureStage.FAILED,
    -> QuickCaptureSingleTapAction.RESET_AND_CAPTURE
    QuickCaptureStage.VALIDATING_TARGET,
    QuickCaptureStage.HIDING_OVERLAY,
    QuickCaptureStage.CAPTURING,
    QuickCaptureStage.SELECTING_REGION,
    QuickCaptureStage.PERSISTING,
    QuickCaptureStage.SUBMITTING,
    QuickCaptureStage.QUEUED,
    QuickCaptureStage.RUNNING,
    QuickCaptureStage.WAITING_APPROVAL,
    -> QuickCaptureSingleTapAction.IGNORE_WHILE_BUSY
}

sealed interface QuickCaptureBatchDecision {
    data class Accepted(val totalCount: Int, val totalBytes: Long) : QuickCaptureBatchDecision
    data object TooManyImages : QuickCaptureBatchDecision
    data object TooLarge : QuickCaptureBatchDecision
}

/** Pure enforcement for the multi-image bounds, reusable from JVM tests. */
fun decideQuickCaptureBatch(
    existing: List<QuickCaptureAttachment>,
    nextBytes: Long,
): QuickCaptureBatchDecision = when {
    existing.size >= QUICK_CAPTURE_MAX_IMAGES -> QuickCaptureBatchDecision.TooManyImages
    nextBytes < 0L || existing.sumOf { it.sizeBytes } + nextBytes > QUICK_CAPTURE_MAX_TOTAL_BYTES ->
        QuickCaptureBatchDecision.TooLarge
    else -> QuickCaptureBatchDecision.Accepted(
        totalCount = existing.size + 1,
        totalBytes = existing.sumOf { it.sizeBytes } + nextBytes,
    )
}
