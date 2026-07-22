package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.memory.MemoryMutationResult

/**
 * The small, user-visible result vocabulary shared by manual memory mutations and the snackbar.
 *
 * Internal mutation rejection codes explain why storage declined a write, but they are not a
 * successful action and must never fall through to the generic success message.
 */
internal enum class MemoryMutationUiFeedback(
    val actionMessageCode: String,
) {
    APPLIED("applied"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    FAILED("failed"),
}

internal fun MemoryMutationResult.toMemoryMutationUiFeedback(): MemoryMutationUiFeedback = when (this) {
    is MemoryMutationResult.Applied -> MemoryMutationUiFeedback.APPLIED
    MemoryMutationResult.NotFound -> MemoryMutationUiFeedback.NOT_FOUND
    MemoryMutationResult.Conflict -> MemoryMutationUiFeedback.CONFLICT
    is MemoryMutationResult.Rejected -> MemoryMutationUiFeedback.FAILED
}
