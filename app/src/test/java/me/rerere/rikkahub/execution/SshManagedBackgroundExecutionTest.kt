package me.rerere.rikkahub.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SshManagedBackgroundExecutionTest {
    @Test
    fun `saved profile background execution is recoverable without persisting secrets`() = runBlocking {
        val ledger = InMemoryLedger()
        val supervisor = FakeSshManagedSupervisor()
        val starter = SshManagedBackgroundStarter(
            supervisor = supervisor,
            ledger = ledger,
            tokenProvider = tokenProvider,
            scope = scope,
        )
        val startable = SshCancelableStartableTool(
            legacyTool = legacyTool("ssh_exec_saved"),
            specResolver = SshExecutionSpecResolver {
                Result.success(savedSpec(background = true))
            },
            backend = SshCancelableExecutionBackend { _, _ ->
                error("finite backend must not run for managed background execution")
            },
            scope = scope,
            managedBackgroundStarter = starter,
        )

        val result = startable.start(buildJsonObject {}, executionContext).awaitResult()
        val record = ledger.list().single()
        val serializedRecord = record.toString()

        assertTrue(result.single().toString().contains("managed_background"))
        assertTrue(result.single().toString().contains(record.executionId))
        assertEquals(ManagedExecutionRuntime.SSH.idPrefix, record.runtime)
        assertEquals("production", record.profileName)
        assertEquals(supervisor.identity.pid, record.pid)
        assertEquals(supervisor.identity.processGroupId, record.processGroupId)
        assertEquals(supervisor.identity.processStartTicks, record.processStartTicks)
        assertFalse(serializedRecord.contains("deploy --token"))
        assertFalse(serializedRecord.contains("credential-secret"))
    }

    @Test
    fun `saved profile managed adapter refuses another conversation`() = runBlocking {
        val ledger = InMemoryLedger()
        val supervisor = FakeSshManagedSupervisor()
        val starter = SshManagedBackgroundStarter(
            supervisor = supervisor,
            ledger = ledger,
            tokenProvider = tokenProvider,
            scope = scope,
        )
        starter.start(savedSpec(background = true), executionContext).awaitResult()
        val record = ledger.list().single()
        val adapter = SshManagedExecutionAdapter(
            ledger = ledger,
            supervisor = supervisor,
            profileResolver = SshSavedConnectionResolver {
                Result.success(savedConnection())
            },
            tokenProvider = tokenProvider,
        )

        val result = adapter.status(
            caller.copy(conversationId = "another-conversation"),
            record.executionId,
        )

        assertEquals("execution_not_found", (result as ManagedExecutionResult.Error).code)
    }

    @Test
    fun `saved profile stop keeps uncertain state when remote identity cannot be confirmed`() = runBlocking {
        val ledger = InMemoryLedger()
        val supervisor = FakeSshManagedSupervisor()
        val starter = SshManagedBackgroundStarter(
            supervisor = supervisor,
            ledger = ledger,
            tokenProvider = tokenProvider,
            scope = scope,
        )
        starter.start(savedSpec(background = true), executionContext).awaitResult()
        val record = ledger.list().single()
        supervisor.stopResult = Result.failure(IllegalStateException("ssh_transport_unavailable"))
        val adapter = SshManagedExecutionAdapter(
            ledger = ledger,
            supervisor = supervisor,
            profileResolver = SshSavedConnectionResolver {
                Result.success(savedConnection())
            },
            tokenProvider = tokenProvider,
        )

        val result = adapter.stop(caller, record.executionId, force = false)
        val snapshot = (result as ManagedExecutionResult.Snapshot).execution

        assertEquals(ManagedExecutionStatus.STOP_REQUESTED, snapshot.status)
        assertTrue(snapshot.alive)
        assertTrue(snapshot.terminationUncertain)
    }

    @Test
    fun `saved profile adapter leaves force escalation to cancellation coordinator`() = runBlocking {
        val ledger = InMemoryLedger()
        val supervisor = FakeSshManagedSupervisor().apply { gracefulStopKeepsRunning = true }
        val starter = SshManagedBackgroundStarter(
            supervisor = supervisor,
            ledger = ledger,
            tokenProvider = tokenProvider,
            scope = scope,
        )
        starter.start(savedSpec(background = true), executionContext).awaitResult()
        val record = ledger.list().single()
        val adapter = SshManagedExecutionAdapter(
            ledger = ledger,
            supervisor = supervisor,
            profileResolver = SshSavedConnectionResolver { Result.success(savedConnection()) },
            tokenProvider = tokenProvider,
        )

        assertTrue(adapter.stop(caller, record.executionId, false) is ManagedExecutionResult.Snapshot)
        assertEquals(listOf(false), supervisor.stopForces)
        assertTrue(adapter.stop(caller, record.executionId, true) is ManagedExecutionResult.Stopped)
        assertEquals(listOf(false, true), supervisor.stopForces)
    }

    @Test
    fun `remote supervisor validates capabilities and identity before process group signal`() {
        val start = AndroidSshManagedSupervisor.startCommand(
            nativeId = "ssh_12345678",
            token = "c".repeat(64),
        )
        val stop = AndroidSshManagedSupervisor.stopCommand(
            nativeId = "ssh_12345678",
            token = "c".repeat(64),
            force = false,
        )

        assertTrue(start.contains("command -v"))
        assertTrue(start.contains("setsid ps sed awk sha256sum"))
        assertTrue(start.contains("/proc/"))
        assertTrue(start.contains("sha256sum"))
        assertTrue(start.contains("start_ticks"))
        assertTrue(stop.indexOf("identity_verified=0") < stop.indexOf("kill -TERM"))
        assertTrue(stop.contains("pgid"))
        assertFalse(start.contains("app_process"))
        assertFalse(start.contains("am broadcast"))
        assertFalse(start.contains("deploy --token"))
    }

    private class FakeSshManagedSupervisor : SshManagedSupervisor {
        var identity = SshSupervisorIdentity(321, 321, 7_777)
        var running = true
        var stopResult: Result<SshSupervisorStatus>? = null
        var gracefulStopKeepsRunning = false
        val stopForces = mutableListOf<Boolean>()

        override suspend fun start(
            connection: SshSavedConnection,
            nativeId: String,
            token: String,
            command: String,
        ): Result<SshSupervisorIdentity> = Result.success(identity)

        override suspend fun status(
            connection: SshSavedConnection,
            nativeId: String,
            token: String,
        ): Result<SshSupervisorStatus> = Result.success(
            SshSupervisorStatus(
                identity = identity,
                state = if (running) "running" else "stopped",
                running = running,
                identityVerified = true,
            )
        )

        override suspend fun stop(
            connection: SshSavedConnection,
            nativeId: String,
            token: String,
            force: Boolean,
        ): Result<SshSupervisorStatus> = stopResult ?: run {
            stopForces += force
            if (force || !gracefulStopKeepsRunning) running = false
            status(connection, nativeId, token)
        }

        override suspend fun logs(
            connection: SshSavedConnection,
            nativeId: String,
            token: String,
            tailBytes: Int,
        ): Result<SshSupervisorLogs> = Result.success(SshSupervisorLogs("out", "", false))
    }

    private class InMemoryLedger : ManagedExecutionLedger {
        private val records = linkedMapOf<String, ManagedExecutionLedgerRecord>()
        override suspend fun list() = records.values.toList()
        override suspend fun upsert(record: ManagedExecutionLedgerRecord) {
            records[record.executionId] = record
        }
        override suspend fun remove(executionId: String) {
            records.remove(executionId)
        }
    }

    private fun legacyTool(name: String) = Tool(
        name = name,
        description = "legacy",
        parameters = { InputSchema.Obj(buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("legacy")) },
    )

    private fun savedConnection() = SshSavedConnection(
        profileName = "production",
        host = "example.test",
        port = 22,
        user = "agent",
        auth = SshAuth(password = "credential-secret"),
        timeoutMs = 30_000,
    )

    private fun savedSpec(background: Boolean) = SshExecutionSpec(
        host = "example.test",
        port = 22,
        user = "agent",
        auth = SshAuth(password = "credential-secret"),
        command = "deploy --token command-secret",
        stdin = null,
        background = background,
        timeoutMs = 30_000,
        savedProfileName = "production",
    )

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tokenProvider = ExecutionTokenProvider { "b".repeat(64) }
        val executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "assistant",
            callOrigin = ToolCallOrigin.LocalChat,
        )
        val caller = ManagedExecutionCaller(
            assistantId = executionContext.assistantId,
            conversationId = executionContext.conversationId.toString(),
            runId = executionContext.runId.toString(),
            origin = executionContext.callOrigin,
            allowedRuntimes = setOf(ManagedExecutionRuntime.SSH),
        )
    }
}
