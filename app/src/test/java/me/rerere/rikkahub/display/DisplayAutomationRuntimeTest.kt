package me.rerere.rikkahub.display

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayAutomationRuntimeTest {
    @Test
    fun `another caller cannot inspect resolve or close a session`() = runBlocking {
        val provisioner = FakeProvisioner(displayId = 8)
        val runtime = DefaultDisplayAutomationRuntime(provisioner)
        val created = runtime.dispatch(DisplayRequest.Create(callerA)) as DisplayResult.Created

        val status = runtime.dispatch(DisplayRequest.Status(callerB, created.session.id))
        val resolve = runtime.dispatch(
            DisplayRequest.Resolve(callerB, created.session.id, DisplayCapability.TREE)
        )
        val close = runtime.dispatch(DisplayRequest.Close(callerB, created.session.id))

        assertEquals("display_session_owner_mismatch", (status as DisplayResult.Error).code)
        assertEquals("display_session_owner_mismatch", (resolve as DisplayResult.Error).code)
        assertEquals("display_session_owner_mismatch", (close as DisplayResult.Error).code)
        assertTrue(provisioner.closed.isEmpty())
    }

    @Test
    fun `expired or unsupported session never resolves to primary display`() = runBlocking {
        var now = 1_000L
        val provisioner = FakeProvisioner(
            displayId = 9,
            capabilities = setOf(DisplayCapability.TREE),
        )
        val runtime = DefaultDisplayAutomationRuntime(
            provisioner = provisioner,
            nowMs = { now },
            idleTimeoutMs = 100L,
        )
        val created = runtime.dispatch(DisplayRequest.Create(callerA)) as DisplayResult.Created
        val unsupported = runtime.dispatch(
            DisplayRequest.Resolve(callerA, created.session.id, DisplayCapability.GESTURE)
        )
        now += 101L
        val expired = runtime.dispatch(
            DisplayRequest.Resolve(callerA, created.session.id, DisplayCapability.TREE)
        )

        assertEquals("display_capability_unavailable", (unsupported as DisplayResult.Error).code)
        assertEquals("display_session_not_active", (expired as DisplayResult.Error).code)
        assertEquals(listOf(9), provisioner.closed)
        assertTrue(runtime.state.value.sessions.none {
            it.lifecycle == DisplaySessionLifecycle.ACTIVE && it.displayId == 0
        })
    }

    @Test
    fun `provisioner returning primary display is rejected and closed`() = runBlocking {
        val provisioner = FakeProvisioner(displayId = 0)
        val runtime = DefaultDisplayAutomationRuntime(provisioner)

        val result = runtime.dispatch(DisplayRequest.Create(callerA))

        assertEquals("display_primary_forbidden", (result as DisplayResult.Error).code)
        assertEquals(listOf(0), provisioner.closed)
        assertTrue(runtime.state.value.sessions.isEmpty())
    }

    @Test
    fun `one active managed display is the default capacity`() = runBlocking {
        val runtime = DefaultDisplayAutomationRuntime(FakeProvisioner(displayId = 7))
        assertTrue(runtime.dispatch(DisplayRequest.Create(callerA)) is DisplayResult.Created)

        val second = runtime.dispatch(DisplayRequest.Create(callerB))

        assertEquals("display_capacity_reached", (second as DisplayResult.Error).code)
    }

    @Test
    fun `provisioner death marks the exact session lost and never remaps it`() = runBlocking {
        val provisioner = FakeProvisioner(displayId = 17)
        val runtime = DefaultDisplayAutomationRuntime(provisioner)
        val created = runtime.dispatch(DisplayRequest.Create(callerA)) as DisplayResult.Created

        runtime.dispatch(DisplayRequest.ProvisionerDied(17))
        val resolve = runtime.dispatch(
            DisplayRequest.Resolve(callerA, created.session.id, DisplayCapability.TREE)
        )

        assertEquals("display_session_not_active", (resolve as DisplayResult.Error).code)
        assertEquals(
            DisplaySessionLifecycle.LOST,
            runtime.state.value.sessions.single().lifecycle,
        )
        assertTrue(runtime.state.value.sessions.none { it.displayId == 0 })
    }

    @Test
    fun `emergency stop closes every active managed display`() = runBlocking {
        val provisioner = FakeProvisioner(displayId = 18)
        val runtime = DefaultDisplayAutomationRuntime(provisioner)
        runtime.dispatch(DisplayRequest.Create(callerA))

        val result = runtime.dispatch(DisplayRequest.EmergencyStop)

        assertTrue(result is DisplayResult.Closed)
        assertEquals(listOf(18), provisioner.closed)
        assertTrue(runtime.state.value.sessions.none {
            it.lifecycle == DisplaySessionLifecycle.ACTIVE
        })
    }

    private class FakeProvisioner(
        private val displayId: Int,
        private val capabilities: Set<DisplayCapability> = setOf(
            DisplayCapability.CREATE,
            DisplayCapability.LAUNCH,
            DisplayCapability.TREE,
            DisplayCapability.SCREENSHOT,
            DisplayCapability.GESTURE,
            DisplayCapability.KEY,
        ),
    ) : DisplayProvisioner {
        val closed = mutableListOf<Int>()

        override suspend fun create(): Result<ProvisionedDisplay> = Result.success(
            ProvisionedDisplay(displayId, capabilities)
        )

        override suspend fun close(displayId: Int) {
            closed += displayId
        }
    }

    private companion object {
        val callerA = DisplayCaller("assistant-a", "conversation-a", "run-a", ToolCallOrigin.LocalChat)
        val callerB = DisplayCaller("assistant-b", "conversation-b", "run-b", ToolCallOrigin.LocalChat)
    }
}
