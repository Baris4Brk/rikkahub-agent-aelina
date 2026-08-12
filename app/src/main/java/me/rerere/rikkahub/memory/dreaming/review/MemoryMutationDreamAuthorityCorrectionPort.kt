package me.rerere.rikkahub.memory.dreaming.review

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import me.rerere.rikkahub.memory.dreaming.store.DreamObserverStore

/**
 * Writes a user correction through the existing authoritative Memory transaction. Dream rows are
 * deliberately not touched here: the repository performs that second, fenced phase only after
 * this port reports the committed Memory revision and its observed authority epoch.
 */
class MemoryMutationDreamAuthorityCorrectionPort(
    private val mutationCoordinator: MemoryMutationCoordinator,
    private val observerStore: DreamObserverStore,
) : DreamAuthorityCorrectionPort {
    override suspend fun create(
        request: DreamAuthorityCorrectionRequest,
    ): DreamAuthorityCorrectionResult {
        val originAssistantId = request.capturedOriginAssistantId
            ?: return DreamAuthorityCorrectionResult.Rejected("dream_correction_origin_missing")
        val mutation = mutationCoordinator.mutate(
            MemoryMutationCommand.Create(
                scopeId = request.scopeId.value,
                title = request.title,
                content = request.content,
                kind = request.kind,
                tags = request.tags,
                confidence = request.confidence,
                expiresAtMs = request.expiresAtEpochMs,
                approvalSource = request.approvalSource,
                sourceType = request.sourceType,
                originAssistantId = originAssistantId,
            ),
        )
        return when (mutation) {
            is MemoryMutationResult.Applied -> {
                val state = try {
                    observerStore.readScopeState(request.scopeId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (state == null) {
                    DreamAuthorityCorrectionResult.AppliedRebuildPending(
                        memoryId = mutation.memoryId,
                        revision = mutation.revision,
                    )
                } else {
                    DreamAuthorityCorrectionResult.Applied(
                        memoryId = mutation.memoryId,
                        revision = mutation.revision,
                        resultingMemoryEpoch = state.memoryEpoch,
                    )
                }
            }

            MemoryMutationResult.Conflict -> DreamAuthorityCorrectionResult.Conflict
            MemoryMutationResult.NotFound -> DreamAuthorityCorrectionResult.NotFound
            is MemoryMutationResult.Rejected -> DreamAuthorityCorrectionResult.Rejected(mutation.code)
        }
    }
}
