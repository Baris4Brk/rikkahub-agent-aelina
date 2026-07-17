package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedToolSurfaceTest {
    @Test
    fun `privileged surface classifies every implemented local option exactly once`() {
        val declaredOptions = LocalToolOption::class.sealedSubclasses
            .mapNotNull { it.objectInstance }
            .toSet()
        val privilegedOptions = LocalToolOption.PRIVILEGED_IMPLEMENTED

        assertEquals(privilegedOptions.toSet().size, privilegedOptions.size)
        assertEquals(
            "declared=${declaredOptions.mapNotNull { it::class.simpleName }.sorted()} " +
                "privileged=${privilegedOptions.mapNotNull { it::class.simpleName }.sorted()}",
            declaredOptions,
            privilegedOptions.toSet(),
        )
    }
}
