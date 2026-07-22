package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.memory.MemoryMutationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryMutationFeedbackTest {
    @Test
    fun `a rejected manual memory mutation is presented as a failure`() {
        assertEquals(
            MemoryMutationUiFeedback.FAILED,
            MemoryMutationResult.Rejected("memory_mutation_invalid").toMemoryMutationUiFeedback(),
        )
    }
}
