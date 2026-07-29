package me.rerere.rikkahub.pet.behavior

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.DefaultPetActionResolver
import me.rerere.rikkahub.pet.action.PetActionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBehaviorOrchestratorTest {
    @Test
    fun `safety preempts touch and returns to latest operational state`() = runBlocking {
        val fixture = fixture()
        try {
            fixture.orchestrator.submit(
                PetBehaviorIntent.Operational(
                    CorePetActions.WORK,
                    PetActionSource.AGENT_OPERATION,
                    PetBehaviorPriority.TOOL,
                ),
            )
            fixture.orchestrator.submit(
                PetBehaviorIntent.OneShot(
                    CorePetActions.JUMP,
                    PetActionSource.TOUCH,
                    PetBehaviorPriority.TOUCH,
                    minDurationMs = 1L,
                    maxDurationMs = 5_000L,
                ),
            )
            assertEquals(CorePetActions.JUMP, fixture.orchestrator.state.value.displayedAction.requestedAction)

            fixture.orchestrator.submit(
                PetBehaviorIntent.Operational(
                    CorePetActions.FAILURE,
                    PetActionSource.SAFETY,
                    PetBehaviorPriority.SAFETY,
                ),
            )
            assertEquals(CorePetActions.FAILURE, fixture.orchestrator.state.value.displayedAction.requestedAction)

            fixture.orchestrator.submit(PetBehaviorIntent.ClearSource(PetActionSource.SAFETY))
            assertEquals(CorePetActions.WORK, fixture.orchestrator.state.value.displayedAction.requestedAction)
            assertFalse(fixture.orchestrator.state.value.queuedOneShots.any { it.source == PetActionSource.TOUCH })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `duplicate persistent state does not restart animation`() {
        val fixture = fixture()
        try {
            val intent = PetBehaviorIntent.Operational(
                CorePetActions.REVIEW,
                PetActionSource.AGENT_OPERATION,
                PetBehaviorPriority.MODEL,
            )
            fixture.orchestrator.submit(intent)
            fixture.orchestrator.submit(intent)

            assertEquals(CorePetActions.REVIEW, fixture.orchestrator.state.value.displayedAction.requestedAction)
            assertTrue(fixture.traces.any { it.rejectionReason == "duplicate_operational_state" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `success sequence moves from jump to wave only once`() = runBlocking {
        val fixture = fixture()
        try {
            fixture.orchestrator.submit(
                PetBehaviorIntent.Sequence(
                    steps = listOf(
                        PetActionSequenceStep(CorePetActions.JUMP, minDurationMs = 1L, maxDurationMs = 25L),
                        PetActionSequenceStep(CorePetActions.WAVE, minDurationMs = 1L, maxDurationMs = 500L),
                    ),
                    source = PetActionSource.HANDOFF,
                    priority = PetBehaviorPriority.HANDOFF_RESULT,
                ),
            )
            assertEquals(CorePetActions.JUMP, fixture.orchestrator.state.value.displayedAction.requestedAction)
            delay(60L)
            assertEquals(CorePetActions.WAVE, fixture.orchestrator.state.value.displayedAction.requestedAction)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `standard resolver gives speaking a safe fallback`() {
        val resolved = DefaultPetActionResolver().resolve(CorePetActions.SPEAKING, PetActionProfile.standard())
        assertEquals(CorePetActions.WAVE, resolved.resolvedAction)
        assertEquals(CorePetActions.SPEAKING, resolved.requestedAction)
    }

    private fun fixture(): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val traces = mutableListOf<PetActionTrace>()
        return Fixture(
            scope = scope,
            traces = traces,
            orchestrator = PetBehaviorOrchestrator(
                scope = scope,
                traceSink = traces::add,
            ),
        )
    }

    private data class Fixture(
        val scope: CoroutineScope,
        val traces: List<PetActionTrace>,
        val orchestrator: PetBehaviorOrchestrator,
    ) {
        fun close() {
            orchestrator.close()
            scope.cancel()
        }
    }
}
