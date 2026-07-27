package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationCommandIdentityTest {
    @Test
    fun `explicit command id remains authoritative`() {
        val commandId = Uuid.random()
        val runId = Uuid.random()

        assertEquals(commandId, resolveGenerationCommandId(commandId, runId))
    }

    @Test
    fun `live run id identifies continuation when command id is absent`() {
        val runId = Uuid.random()

        assertEquals(runId, resolveGenerationCommandId(null, runId))
    }

    @Test
    fun `missing command and run identity remains rejected`() {
        assertNull(resolveGenerationCommandId(null, null))
    }
}
