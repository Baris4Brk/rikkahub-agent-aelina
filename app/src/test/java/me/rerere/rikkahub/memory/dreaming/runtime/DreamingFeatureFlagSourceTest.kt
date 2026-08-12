package me.rerere.rikkahub.memory.dreaming.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamingFeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamingFeatureFlagSourceTest {
    @Test
    fun productionSource_isAlwaysFullyDormant() = runBlocking {
        val flags = DisabledDreamingFeatureFlagSource.flagsFor(SCOPE)

        assertEquals(DreamingFeatureFlags.M1AllOff, flags)
        assertFalse(flags.schemaReady)
        assertFalse(flags.generate)
        assertFalse(flags.shadow)
        assertFalse(flags.use)
    }

    @Test
    fun testsCanInjectScopeSpecificGenerateAndShadow() = runBlocking {
        val source = DreamingFeatureFlagSource { scope ->
            if (scope == SCOPE) {
                DreamingFeatureFlags(schemaReady = true, generate = true, shadow = true)
            } else {
                DreamingFeatureFlags.M1AllOff
            }
        }

        assertTrue(source.flagsFor(SCOPE).generate)
        assertTrue(source.flagsFor(SCOPE).shadow)
        assertFalse(source.flagsFor(DreamScopeId.Global).generate)
    }

    @Test
    fun activeGenerateAndUse_withoutShadow_stillAdmitsSynthesis() {
        val flags = DreamingFeatureFlags(
            schemaReady = true,
            generate = true,
            use = true,
            shadow = false,
        )

        assertTrue(flags.allowsSynthesisGeneration())
    }

    @Test
    fun generateDisabled_rejectsBeforeRuntimeDependencies() {
        val flags = DreamingFeatureFlags(schemaReady = true, generate = false, use = true)

        assertFalse(flags.allowsSynthesisGeneration())
    }

    private companion object {
        val SCOPE = DreamScopeId.requireCanonical("10000000-0000-0000-0000-000000000010")
    }
}
