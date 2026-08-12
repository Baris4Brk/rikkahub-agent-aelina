package me.rerere.rikkahub.owner

import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerMemoryScopeBindingTest {
    private val assistantId = "11bd1f5d-c96e-4ec9-92ec-509577f84dc9"

    @Test
    fun `assistant effective scope uses assistant repository identity`() {
        val scope = OwnerMemoryScopeBinding.effective(
            assistantId = assistantId,
            useGlobalMemory = false,
        )

        assertEquals("assistant", scope.wireValue)
        assertEquals(assistantId, scope.repositoryScopeId)
        assertTrue(scope.matchesClaim("assistant"))
        assertFalse(scope.matchesClaim("global"))
        assertFalse(scope.matchesClaim(null))
    }

    @Test
    fun `global effective scope uses the repository global identity`() {
        val scope = OwnerMemoryScopeBinding.effective(
            assistantId = assistantId,
            useGlobalMemory = true,
        )

        assertEquals("global", scope.wireValue)
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, scope.repositoryScopeId)
        assertTrue(scope.matchesClaim("global"))
        assertFalse(scope.matchesClaim("assistant"))
    }

    @Test
    fun `configuration flip invalidates the scope claim returned by the old list`() {
        val listedScope = OwnerMemoryScopeBinding.effective(
            assistantId = assistantId,
            useGlobalMemory = false,
        ).wireValue
        val currentScope = OwnerMemoryScopeBinding.effective(
            assistantId = assistantId,
            useGlobalMemory = true,
        )

        assertFalse(currentScope.matchesClaim(listedScope))
    }

    @Test
    fun `scope claim is canonical and case sensitive`() {
        val scope = OwnerMemoryScopeBinding.effective(
            assistantId = assistantId,
            useGlobalMemory = true,
        )

        assertFalse(scope.matchesClaim("GLOBAL"))
        assertFalse(scope.matchesClaim(" global "))
        assertEquals(setOf("assistant", "global"), OwnerMemoryScopeBinding.WIRE_VALUES)
    }
}
