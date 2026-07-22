package me.rerere.rikkahub.memory

interface MemoryMutationCoordinator {
    suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult
}

class DefaultMemoryMutationCoordinator(
    private val store: MemoryProcessingStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MemoryMutationCoordinator {
    override suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult =
        store.mutate(command, nowMs())
}
