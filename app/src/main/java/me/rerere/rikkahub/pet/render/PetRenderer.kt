package me.rerere.rikkahub.pet.render

import java.io.Closeable
import me.rerere.rikkahub.pet.action.PetCapabilitySet
import me.rerere.rikkahub.pet.action.ResolvedPetAction
import me.rerere.rikkahub.pet.behavior.PetBehaviorState
import me.rerere.rikkahub.pet.overlay.PetSpriteView

/** Fixed renderer surface. P2 intentionally exposes only static sprite implementations. */
interface PetRenderer : Closeable {
    val rendererType: String
    val capabilities: PetCapabilitySet

    /** The single drawing entrypoint used by [PetBehaviorOrchestrator]-backed views. */
    fun render(state: PetBehaviorState)
}

/**
 * Standard Codex atlas adapter. It intentionally owns the only call to [PetSpriteView.setResolvedAction]
 * so state sources cannot restart an animation by racing each other.
 */
class CodexSpriteRendererBridge(
    private val view: PetSpriteView,
    override val capabilities: PetCapabilitySet,
) : PetRenderer {
    override val rendererType: String = "codex_sprite"
    private var lastAction: ResolvedPetAction? = null

    override fun render(state: PetBehaviorState) {
        val next = state.displayedAction
        if (sameClip(lastAction, next)) return
        lastAction = next
        view.setResolvedAction(next)
    }

    override fun close() = Unit

    private fun sameClip(a: ResolvedPetAction?, b: ResolvedPetAction): Boolean =
        a?.clip == b.clip && a.resolvedAction == b.resolvedAction
}

/** P2 composite sprites use the same safe static-sprite contract; no code or network plugins. */
class CompositeSpriteRendererBridge(
    private val delegate: CodexSpriteRendererBridge,
) : PetRenderer by delegate {
    override val rendererType: String = "composite_sprite"
}

object PetRendererFactory {
    fun createCodexSprite(
        view: PetSpriteView,
        capabilities: PetCapabilitySet,
    ): PetRenderer = CodexSpriteRendererBridge(view, capabilities)

    fun createCompositeSprite(
        view: PetSpriteView,
        capabilities: PetCapabilitySet,
    ): PetRenderer = CompositeSpriteRendererBridge(CodexSpriteRendererBridge(view, capabilities))
}
