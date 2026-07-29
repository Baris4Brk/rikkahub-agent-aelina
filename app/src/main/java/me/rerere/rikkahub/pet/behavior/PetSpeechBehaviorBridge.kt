package me.rerere.rikkahub.pet.behavior

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.tts.PersistentTtsLibrary
import me.rerere.rikkahub.tts.TtsPlaybackState

/**
 * Connects only scoped second-user/pet audio to the desktop pet. Ordinary chat UI narration
 * uses a different playback path and intentionally never reaches this bridge.
 */
class PetSpeechBehaviorBridge(
    scope: CoroutineScope,
    private val library: PersistentTtsLibrary,
    private val behavior: PetBehaviorOrchestrator,
) : Closeable {
    private var ownerKey: String? = null
    private val observation: Job = scope.launch {
        library.playbackState.collectLatest(::onPlaybackState)
    }

    fun setOwnerKey(nextOwnerKey: String?) {
        ownerKey = nextOwnerKey
        onPlaybackState(library.playbackState.value)
    }

    private fun onPlaybackState(state: TtsPlaybackState) {
        val activeOwner = ownerKey
        if (state is TtsPlaybackState.Speaking && activeOwner != null && state.ownerKey == activeOwner) {
            behavior.submit(
                PetBehaviorIntent.Operational(
                    action = CorePetActions.SPEAKING,
                    source = PetActionSource.SPEECH,
                    priority = PetBehaviorPriority.SPEAKING,
                ),
            )
        } else {
            behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.SPEECH))
        }
    }

    override fun close() {
        observation.cancel()
        behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.SPEECH))
    }
}
