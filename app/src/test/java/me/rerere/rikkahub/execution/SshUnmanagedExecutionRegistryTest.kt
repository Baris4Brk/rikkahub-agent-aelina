package me.rerere.rikkahub.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.SshCancellationHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshUnmanagedExecutionRegistryTest {
    @Test
    fun `temporary task is owner isolated and absent after process registry loss`() = runBlocking {
        val fixture = fixture()
        fixture.registry.register(EXECUTION_ID, OWNER, fixture.started)

        assertTrue(fixture.registry.status(CALLER, EXECUTION_ID) is ManagedExecutionResult.Snapshot)
        assertEquals(
            "execution_not_found",
            (fixture.registry.status(CALLER.copy(conversationId = "other"), EXECUTION_ID)
                as ManagedExecutionResult.Error).code,
        )
        val freshRegistry = SshUnmanagedExecutionRegistry(scope)
        assertEquals(
            "execution_unsupported",
            (freshRegistry.status(CALLER, EXECUTION_ID) as ManagedExecutionResult.Error).code,
        )
        fixture.result.complete(successResult())
        Unit
    }

    @Test
    fun `temporary task uses graceful then force and confirms exact process`() = runBlocking {
        val fixture = fixture(confirmOnlyForce = true)
        fixture.registry.register(EXECUTION_ID, OWNER, fixture.started)

        val graceful = fixture.registry.stop(CALLER, EXECUTION_ID, force = false)
        val forced = fixture.registry.stop(CALLER, EXECUTION_ID, force = true)

        assertTrue(graceful is ManagedExecutionResult.Snapshot)
        assertTrue(forced is ManagedExecutionResult.Stopped)
        assertEquals(listOf(false, true), fixture.forces)
        assertEquals(
            fixture.started.identity.processStartTicks.toString(),
            (forced as ManagedExecutionResult.Stopped).execution.runtimeInstanceMarker,
        )
        fixture.result.complete(successResult())
        Unit
    }

    private fun fixture(confirmOnlyForce: Boolean = false): Fixture {
        val forces = mutableListOf<Boolean>()
        val result = CompletableDeferred<List<UIMessagePart>>()
        val started = StartedSshExecution(
            identity = RemoteSshProcessIdentity(41, 41, 9_001),
            result = result,
            hooks = SshCancellationHooks(
                closeChannel = {},
                terminateRemoteProcessGroup = { force -> forces += force; true },
                awaitRemoteExit = { !confirmOnlyForce || forces.lastOrNull() == true },
            ),
        )
        return Fixture(
            registry = SshUnmanagedExecutionRegistry(scope, nowMs = { 123L }),
            started = started,
            result = result,
            forces = forces,
        )
    }

    private fun successResult() = listOf(UIMessagePart.Text("{\"success\":true}"))

    private data class Fixture(
        val registry: SshUnmanagedExecutionRegistry,
        val started: StartedSshExecution,
        val result: CompletableDeferred<List<UIMessagePart>>,
        val forces: MutableList<Boolean>,
    )

    private companion object {
        const val EXECUTION_ID = "ssh:unmanaged_12345678"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val OWNER = SshUnmanagedOwner("assistant", "conversation", ToolCallOrigin.LocalChat)
        val CALLER = OWNER.caller("run")
    }
}
