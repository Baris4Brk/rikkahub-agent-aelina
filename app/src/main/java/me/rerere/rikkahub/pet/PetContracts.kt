package me.rerere.rikkahub.pet

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

enum class PetDialogueSessionStatus { ACTIVE, ARCHIVED, SOFT_DELETED }

enum class PetDialogueArchiveReason { DAILY, MANUAL, CAPACITY }

enum class PetDialogueInputKind { TEXT, TOUCH, HANDOFF_RESULT }

enum class PetSummaryState { NONE, PENDING, READY, FAILED }

enum class PetAction {
    IDLE,
    RUNNING_RIGHT,
    RUNNING_LEFT,
    WAVING,
    JUMPING,
    FAILED,
    WAITING,
    RUNNING,
    REVIEW,
}

enum class PetBodyRegion { HEAD, BODY, FEET, UNKNOWN }

enum class PetOverlayGestureAction { LOCAL_FEEDBACK, MODEL_RESPONSE, QUICK_MENU, DIALOGUE }

fun petOverlayGestureAction(gesture: String): PetOverlayGestureAction = when (gesture) {
    "tap", "pat" -> PetOverlayGestureAction.MODEL_RESPONSE
    "long_press" -> PetOverlayGestureAction.DIALOGUE
    "double_tap" -> PetOverlayGestureAction.QUICK_MENU
    else -> PetOverlayGestureAction.LOCAL_FEEDBACK
}

enum class PetHandoffMode { CONFIRM, AUTO, SUGGEST_ONLY }

enum class PetHandoffStatus {
    DRAFT,
    CONFIRMED,
    SUBMITTED,
    AUTO_SUBMITTED,
    DISMISSED,
    EXPIRED,
    RESOLVED,
    FAILED,
}

enum class CodexPetVersion { V1, V2 }

@Serializable
data class PetInteractionPayload(
    val type: String,
    val region: PetBodyRegion,
    val count: Int = 1,
    val durationMs: Long = 0,
)

data class PetPersonaProjection(
    val assistantId: Uuid,
    val assistantName: String,
    val personaPrompt: String,
    val petSupplement: String?,
    val revision: Long,
    val truncated: Boolean = false,
)

const val MAX_PET_DIALOGUE_ROUNDS = 20
const val MAX_PET_INPUT_CODE_POINTS = 500
const val MAX_PET_RESPONSE_CODE_POINTS = 96
const val MAX_PET_HANDOFF_RESULT_CODE_POINTS = 1_200
const val MAX_PET_PERSONA_CHARS = 8_000

enum class PetSessionRollAction { NONE, ROLL_EMPTY_DATE, ARCHIVE_DAILY, ARCHIVE_CAPACITY }

object PetSessionPolicy {
    fun beforeAppend(currentDate: String, targetDate: String, turnCount: Int): PetSessionRollAction = when {
        currentDate != targetDate && turnCount == 0 -> PetSessionRollAction.ROLL_EMPTY_DATE
        currentDate != targetDate -> PetSessionRollAction.ARCHIVE_DAILY
        turnCount >= MAX_PET_DIALOGUE_ROUNDS -> PetSessionRollAction.ARCHIVE_CAPACITY
        else -> PetSessionRollAction.NONE
    }
}

object PetAutoHandoffPolicy {
    const val WINDOW_MS = 30L * 60_000L

    fun canSubmit(nowMs: Long, lastSubmittedAtMs: Long?, hasOtherPending: Boolean): Boolean =
        !hasOtherPending && (lastSubmittedAtMs == null || nowMs - lastSubmittedAtMs >= WINDOW_MS)
}
