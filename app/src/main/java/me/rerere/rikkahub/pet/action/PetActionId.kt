package me.rerere.rikkahub.pet.action

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.pet.PetAction

/**
 * Stable semantic action identifier. Profiles may declare additional IDs, but the renderer only
 * plays an ID after [PetActionResolver] has validated it against the active profile.
 */
@Serializable
@JvmInline
value class PetActionId(val value: String) {
    init {
        require(isValid(value)) { "pet_action_id_invalid" }
    }

    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[a-z][a-z0-9_.-]{0,63}")

        fun isValid(value: String): Boolean = FORMAT.matches(value)

        fun parseOrNull(value: String?): PetActionId? =
            value?.takeIf(::isValid)?.let(::PetActionId)
    }
}

/** Core semantic actions always available through a standard Codex profile or a safe fallback. */
object CorePetActions {
    val IDLE = PetActionId("core.idle")
    val MOVE_RIGHT = PetActionId("core.move_right")
    val MOVE_LEFT = PetActionId("core.move_left")
    val WAVE = PetActionId("core.wave")
    val JUMP = PetActionId("core.jump")
    val FAILURE = PetActionId("core.failure")
    val WAIT = PetActionId("core.wait")
    val WORK = PetActionId("core.work")
    val REVIEW = PetActionId("core.review")

    val SUCCESS = PetActionId("semantic.success")
    val SAFETY_BLOCKED = PetActionId("semantic.safety_blocked")
    val SPEAKING = PetActionId("semantic.speaking")
    val TOUCH_HEAD = PetActionId("semantic.touch.head")
    val TOUCH_BODY = PetActionId("semantic.touch.body")
    val TOUCH_FEET = PetActionId("semantic.touch.feet")
    val DIALOGUE_NEUTRAL = PetActionId("semantic.dialogue.neutral")
    val DIALOGUE_GREETING = PetActionId("semantic.dialogue.greeting")
    val DIALOGUE_PLAYFUL = PetActionId("semantic.dialogue.playful")
    val DIALOGUE_CURIOUS = PetActionId("semantic.dialogue.curious")
    val DIALOGUE_COMFORTING = PetActionId("semantic.dialogue.comforting")

    val standard: Set<PetActionId> = setOf(
        IDLE,
        MOVE_RIGHT,
        MOVE_LEFT,
        WAVE,
        JUMP,
        FAILURE,
        WAIT,
        WORK,
        REVIEW,
    )

    fun fromLegacy(action: PetAction): PetActionId = when (action) {
        PetAction.IDLE -> IDLE
        PetAction.RUNNING_RIGHT -> MOVE_RIGHT
        PetAction.RUNNING_LEFT -> MOVE_LEFT
        PetAction.WAVING -> WAVE
        PetAction.JUMPING -> JUMP
        PetAction.FAILED -> FAILURE
        PetAction.WAITING -> WAIT
        PetAction.RUNNING -> WORK
        PetAction.REVIEW -> REVIEW
    }

    /** Existing persisted action strings remain valid after semantic IDs become the default. */
    fun fromLegacyWire(value: String?): PetActionId? = runCatching {
        value?.let(PetAction::valueOf)?.let(::fromLegacy)
    }.getOrNull()

    fun defaultFallbacks(action: PetActionId): List<PetActionId> = when (action) {
        SUCCESS -> listOf(JUMP, WAVE, IDLE)
        SAFETY_BLOCKED -> listOf(FAILURE, IDLE)
        SPEAKING -> listOf(WAVE, REVIEW, IDLE)
        TOUCH_HEAD -> listOf(WAVE, JUMP, IDLE)
        TOUCH_BODY -> listOf(REVIEW, JUMP, IDLE)
        TOUCH_FEET -> listOf(JUMP, IDLE)
        DIALOGUE_GREETING -> listOf(WAVE, REVIEW, IDLE)
        DIALOGUE_PLAYFUL -> listOf(JUMP, WAVE, IDLE)
        DIALOGUE_CURIOUS -> listOf(REVIEW, IDLE)
        DIALOGUE_COMFORTING -> listOf(WAVE, IDLE)
        DIALOGUE_NEUTRAL -> listOf(REVIEW, IDLE)
        else -> if (action == IDLE) emptyList() else listOf(IDLE)
    }
}

/** Model-facing visual intent; it is intentionally not an arbitrary profile action ID. */
@Serializable
enum class PetVisualHint {
    NEUTRAL,
    GREETING,
    PLAYFUL,
    CURIOUS,
    COMFORTING,
}

fun PetVisualHint.toSemanticAction(): PetActionId = when (this) {
    PetVisualHint.NEUTRAL -> CorePetActions.DIALOGUE_NEUTRAL
    PetVisualHint.GREETING -> CorePetActions.DIALOGUE_GREETING
    PetVisualHint.PLAYFUL -> CorePetActions.DIALOGUE_PLAYFUL
    PetVisualHint.CURIOUS -> CorePetActions.DIALOGUE_CURIOUS
    PetVisualHint.COMFORTING -> CorePetActions.DIALOGUE_COMFORTING
}
