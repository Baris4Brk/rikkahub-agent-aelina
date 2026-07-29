package me.rerere.rikkahub.pet.action

import me.rerere.rikkahub.pet.PetAction

enum class PetClipLoopMode { LOOP, ONCE }

/** A renderer-neutral static-sprite clip description. */
data class PetClipBinding(
    val actionId: PetActionId,
    val sheetId: String = BASE_SHEET_ID,
    val row: Int,
    val frames: Int,
    val fps: Int? = null,
    val loopMode: PetClipLoopMode = PetClipLoopMode.LOOP,
    val mirrorX: Boolean = false,
) {
    init {
        require(row >= 0) { "pet_clip_row_invalid" }
        require(frames in 1..8) { "pet_clip_frames_invalid" }
        require(fps == null || fps in 1..30) { "pet_clip_fps_invalid" }
    }

    companion object {
        const val BASE_SHEET_ID = "base"
    }
}

/** Declarative static-sheet geometry. Paths are validated by the profile importer, never the renderer. */
data class PetSheetBinding(
    val sheetId: String,
    val relativePath: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
) {
    init {
        require(sheetId.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) { "pet_sheet_id_invalid" }
        require(frameWidth in 1..2048 && frameHeight in 1..2048) { "pet_sheet_frame_invalid" }
        require(columns in 1..16 && rows in 1..32) { "pet_sheet_grid_invalid" }
    }
}

data class PetCapabilitySet(
    val supportedActions: Set<PetActionId>,
    val supportsDirectionalLook: Boolean = false,
    val supportsAlphaHitTest: Boolean = true,
    val supportsSpeechMotion: Boolean = false,
    val supportsMirroring: Boolean = false,
)

data class PetIdlePoolConfig(
    val weights: Map<PetActionId, Int>,
    val minIntervalMs: Long = 15_000L,
) {
    init {
        require(weights.isNotEmpty()) { "pet_idle_pool_empty" }
        require(weights.values.all { it in 1..100 }) { "pet_idle_pool_weight_invalid" }
        require(minIntervalMs in 5_000L..300_000L) { "pet_idle_pool_interval_invalid" }
    }
}

/** Immutable, already validated runtime profile. JSON DTOs are converted to this type. */
data class PetActionProfile(
    val profileId: String,
    val rendererType: String,
    val bindings: Map<PetActionId, PetClipBinding>,
    val sheets: Map<String, PetSheetBinding> = emptyMap(),
    val aliases: Map<PetActionId, PetActionId> = emptyMap(),
    val fallbacks: Map<PetActionId, List<PetActionId>> = emptyMap(),
    val touchMappings: Map<String, PetActionId> = emptyMap(),
    val idlePool: PetIdlePoolConfig? = null,
    val capabilities: PetCapabilitySet = PetCapabilitySet(bindings.keys),
) {
    companion object {
        fun standard(profileId: String = "builtin.codex.standard"): PetActionProfile {
            val bindings = mapOf(
                CorePetActions.IDLE to PetClipBinding(CorePetActions.IDLE, row = 0, frames = 6),
                CorePetActions.MOVE_RIGHT to PetClipBinding(CorePetActions.MOVE_RIGHT, row = 1, frames = 8),
                CorePetActions.MOVE_LEFT to PetClipBinding(CorePetActions.MOVE_LEFT, row = 2, frames = 8),
                CorePetActions.WAVE to PetClipBinding(CorePetActions.WAVE, row = 3, frames = 4, loopMode = PetClipLoopMode.ONCE),
                CorePetActions.JUMP to PetClipBinding(CorePetActions.JUMP, row = 4, frames = 5, loopMode = PetClipLoopMode.ONCE),
                CorePetActions.FAILURE to PetClipBinding(CorePetActions.FAILURE, row = 5, frames = 8),
                CorePetActions.WAIT to PetClipBinding(CorePetActions.WAIT, row = 6, frames = 6),
                CorePetActions.WORK to PetClipBinding(CorePetActions.WORK, row = 7, frames = 6),
                CorePetActions.REVIEW to PetClipBinding(CorePetActions.REVIEW, row = 8, frames = 6),
            )
            return PetActionProfile(
                profileId = profileId,
                rendererType = "codex_sprite",
                bindings = bindings,
                sheets = mapOf(
                    PetClipBinding.BASE_SHEET_ID to PetSheetBinding(
                        sheetId = PetClipBinding.BASE_SHEET_ID,
                        relativePath = "spritesheet.webp",
                        frameWidth = 192,
                        frameHeight = 208,
                        columns = 8,
                        rows = 9,
                    ),
                ),
                capabilities = PetCapabilitySet(bindings.keys),
            )
        }
    }
}

data class ResolvedPetAction(
    val requestedAction: PetActionId,
    val resolvedAction: PetActionId,
    val clip: PetClipBinding,
    val fallbackPath: List<PetActionId>,
    val legacyAction: PetAction,
)

fun interface PetActionResolver {
    fun resolve(requested: PetActionId, profile: PetActionProfile): ResolvedPetAction
}

/**
 * Profile aliases and fallbacks are trusted only after profile validation. Resolution still has a
 * depth bound and visited set so malformed data can never hang the foreground service.
 */
class DefaultPetActionResolver : PetActionResolver {
    override fun resolve(requested: PetActionId, profile: PetActionProfile): ResolvedPetAction {
        val path = mutableListOf<PetActionId>()
        val visited = linkedSetOf<PetActionId>()
        var candidate = requested
        var depth = 0
        while (depth < MAX_RESOLUTION_DEPTH && visited.add(candidate)) {
            path += candidate
            val alias = profile.aliases[candidate]
            if (alias != null && alias != candidate) {
                candidate = alias
                depth += 1
                continue
            }
            profile.bindings[candidate]?.let { binding ->
                return ResolvedPetAction(
                    requestedAction = requested,
                    resolvedAction = candidate,
                    clip = binding,
                    fallbackPath = path,
                    legacyAction = candidate.toLegacyAction(),
                )
            }
            candidate = profile.fallbacks[candidate]
                ?.firstOrNull { it !in visited }
                ?: CorePetActions.defaultFallbacks(candidate).firstOrNull { it !in visited }
                ?: CorePetActions.IDLE
            depth += 1
        }
        val idle = profile.bindings[CorePetActions.IDLE]
            ?: PetActionProfile.standard().bindings.getValue(CorePetActions.IDLE)
        return ResolvedPetAction(
            requestedAction = requested,
            resolvedAction = CorePetActions.IDLE,
            clip = idle,
            fallbackPath = (path + CorePetActions.IDLE).distinct(),
            legacyAction = PetAction.IDLE,
        )
    }

    private fun PetActionId.toLegacyAction(): PetAction = when (this) {
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

    private companion object {
        const val MAX_RESOLUTION_DEPTH = 12
    }
}
