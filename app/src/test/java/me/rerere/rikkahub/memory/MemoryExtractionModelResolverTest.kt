package me.rerere.rikkahub.memory

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility

class MemoryExtractionModelResolverTest {
    @Test
    fun `an explicitly missing extraction model does not silently fall back to fast model`() {
        val fastModel = Settings().providers.flatMap { it.models }.first()
        val settings = Settings(
            fastModelId = fastModel.id,
            memoryExtractionModelId = Uuid.random(),
        )

        assertNull(settings.resolveMemoryExtractionModel())
    }

    @Test
    fun `a null extraction model uses the configured fast model`() {
        val fastModel = Settings().providers.flatMap { it.models }.first()
        val settings = Settings(fastModelId = fastModel.id)

        assertEquals(fastModel.id, settings.resolveMemoryExtractionModel()?.id)
    }

    @Test
    fun `reasoning extraction models use a bounded supported effort`() {
        assertEquals(
            ReasoningLevel.LOW,
            memoryExtractionReasoningLevel(Model(abilities = listOf(ModelAbility.REASONING))),
        )
        assertEquals(ReasoningLevel.OFF, memoryExtractionReasoningLevel(Model()))
    }

    @Test
    fun `empty completed provider output becomes an explicit no-signal envelope`() {
        val parsed = MemoryExtractionParser().parse(normalizeMemoryExtractionText("  "))
        assertTrue(parsed is MemoryExtractionParseResult.Success)
        assertTrue((parsed as MemoryExtractionParseResult.Success).envelope.proposals.isEmpty())
    }
}
