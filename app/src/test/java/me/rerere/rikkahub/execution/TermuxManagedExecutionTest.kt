package me.rerere.rikkahub.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TermuxManagedExecutionTest {
    @Test
    fun `background start stores only verified identity and returns managed id`() = runBlocking {
        val supervisor = FakeSupervisor()
        val ledger = InMemoryLedger()
        val startable = startable(supervisor, ledger)

        val handle = startable.start(
            buildJsonObject {
                put("command", "python server.py --token top-secret")
                put("background", true)
            },
            executionContext,
        )
        val result = handle.awaitResult().single().toString()
        val record = ledger.list().single()

        assertTrue(result.contains("managed_background"))
        assertTrue(result.contains(record.executionId))
        assertEquals(supervisor.identity.pid, record.pid)
        assertEquals(supervisor.identity.processGroupId, record.processGroupId)
        assertEquals(supervisor.identity.processStartTicks, record.processStartTicks)
        assertNotNull(record.tokenHash)
        assertFalse(record.toString().contains("python server.py"))
        assertFalse(record.toString().contains("top-secret"))
    }

    @Test
    fun `tool cancellation sends term and confirms the same process identity`() = runBlocking {
        val supervisor = FakeSupervisor()
        val handle = startable(supervisor, InMemoryLedger()).start(
            buildJsonObject { put("command", "sleep 120") },
            executionContext,
        )

        handle.requestCancel(ToolCancelReason.USER_STOPPED)
        val termination = handle.awaitTermination(1.seconds)

        assertEquals(ToolTerminationState.StoppedConfirmed, termination)
        assertEquals(listOf(false), supervisor.stopForces)
    }

    @Test
    fun `tool cancellation waits through the grace period before escalating`() = runBlocking {
        val supervisor = FakeSupervisor().apply {
            stopAfterGracefulStatusChecks = 2
        }
        val handle = startable(supervisor, InMemoryLedger()).start(
            buildJsonObject { put("command", "sleep 120") },
            executionContext,
        )

        handle.requestCancel(ToolCancelReason.USER_STOPPED)
        val termination = handle.awaitTermination(250.milliseconds)

        assertEquals(ToolTerminationState.StoppedConfirmed, termination)
        assertEquals(listOf(false), supervisor.stopForces)
    }

    @Test
    fun `tool cancellation remains unknown when stopped identity is not verified`() = runBlocking {
        val supervisor = FakeSupervisor().apply {
            verifyStoppedIdentity = false
        }
        val handle = startable(supervisor, InMemoryLedger()).start(
            buildJsonObject { put("command", "sleep 120") },
            executionContext,
        )

        handle.requestCancel(ToolCancelReason.USER_STOPPED)

        assertEquals(ToolTerminationState.Unknown, handle.awaitTermination(100.milliseconds))
    }

    @Test
    fun `capture timeout confirms termination after forced kill`() = runBlocking {
        val supervisor = FakeSupervisor().apply {
            gracefulStopKeepsRunning = true
        }
        val ledger = InMemoryLedger()
        val handle = startable(supervisor, ledger).start(
            buildJsonObject {
                put("command", "sleep 120")
                put("timeout_seconds", 1)
            },
            executionContext,
        )

        val result = handle.awaitResult().single().toString()

        assertEquals(listOf(false, true), supervisor.stopForces)
        assertEquals("STOPPED", ledger.list().single().status)
        assertTrue(result.contains("\"termination_confirmed\":true"))
    }

    @Test
    fun `pid reuse mismatch is not presented as a valid status`() = runBlocking {
        val supervisor = FakeSupervisor().apply {
            identity = identity.copy(pid = identity.pid + 1)
        }
        val ledger = InMemoryLedger()
        val token = tokenProvider.tokenFor("tx_12345678")
        ledger.upsert(recordFor(token))
        val adapter = TermuxManagedExecutionAdapter(ledger, supervisor, tokenProvider)

        val result = adapter.status(caller, "termux:tx_12345678")

        assertEquals("execution_identity_mismatch", (result as ManagedExecutionResult.Error).code)
    }

    @Test
    fun `managed adapter waits for coordinator before force escalation`() = runBlocking {
        val supervisor = FakeSupervisor().apply { gracefulStopKeepsRunning = true }
        val ledger = InMemoryLedger()
        val token = tokenProvider.tokenFor("tx_12345678")
        ledger.upsert(recordFor(token))
        val adapter = TermuxManagedExecutionAdapter(ledger, supervisor, tokenProvider)

        val graceful = adapter.stop(caller, "termux:tx_12345678", force = false)
        assertTrue(graceful is ManagedExecutionResult.Snapshot)
        assertEquals(listOf(false), supervisor.stopForces)

        val forced = adapter.stop(caller, "termux:tx_12345678", force = true)
        assertTrue(forced is ManagedExecutionResult.Stopped)
        assertEquals(listOf(false, true), supervisor.stopForces)
    }

    @Test
    fun `fixed supervisor verifies identity before signalling process group`() {
        val script = AndroidTermuxManagedSupervisor.SUPERVISOR_SCRIPT

        assertTrue(script.contains("verify_identity"))
        assertTrue(script.indexOf("verify_identity") < script.indexOf("kill -TERM"))
        assertTrue(script.contains("start_ticks"))
        assertTrue(script.contains("pgid"))
        assertTrue(script.contains("token.sha256"))
        assertTrue(script.contains("setsid"))
        assertFalse(script.contains("app_process"))
        assertFalse(script.contains("am broadcast"))
        assertEquals(64, AndroidTermuxManagedSupervisor.SCRIPT_SHA256.length)
    }

    @Test
    fun `supervisor script handles commands that exit before proc metadata is visible`() {
        val script = AndroidTermuxManagedSupervisor.SUPERVISOR_SCRIPT

        assertTrue(script.contains("while [ \"\$n\" -lt 40 ]"))
        assertTrue(script.contains("kill -0 \"\$pid\""))
        assertTrue(script.contains("wait \"\$pid\""))
        assertTrue(script.contains("fallback_pgid"))
        assertTrue(script.contains("fallback_start_ticks=\"0\""))
        assertTrue(script.contains("identity.tmp"))
    }

    private fun startable(
        supervisor: FakeSupervisor,
        ledger: InMemoryLedger,
    ) = TermuxManagedStartableTool(
        legacyTool = Tool(
            name = "termux_run_command",
            description = "legacy",
            parameters = { me.rerere.ai.core.InputSchema.Obj(buildJsonObject {}) },
            execute = { emptyList() },
        ),
        supervisor = supervisor,
        ledger = ledger,
        tokenProvider = tokenProvider,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private class FakeSupervisor : TermuxManagedSupervisor {
        var identity = TermuxSupervisorIdentity("tx_12345678", 101, 101, 8080)
        val stopForces = mutableListOf<Boolean>()
        var gracefulStopKeepsRunning = false
        var verifyStoppedIdentity = true
        var stopAfterGracefulStatusChecks: Int? = null
        private var gracefulStatusChecksRemaining: Int? = null
        private var running = true

        override suspend fun start(
            nativeId: String,
            token: String,
            command: String,
            workingDirectory: String,
        ): Result<TermuxSupervisorIdentity> {
            identity = identity.copy(nativeId = nativeId)
            running = true
            return Result.success(identity)
        }

        override suspend fun status(nativeId: String, token: String): Result<TermuxSupervisorStatus> {
            gracefulStatusChecksRemaining?.let { remaining ->
                if (remaining <= 0) {
                    running = false
                    gracefulStatusChecksRemaining = null
                } else {
                    gracefulStatusChecksRemaining = remaining - 1
                }
            }
            return Result.success(
                TermuxSupervisorStatus(
                    identity = identity.copy(nativeId = nativeId),
                    state = if (running) "running" else "stopped",
                    running = running,
                    identityVerified = running || verifyStoppedIdentity,
                )
            )
        }

        override suspend fun stop(nativeId: String, token: String, force: Boolean): Result<TermuxSupervisorStatus> {
            stopForces += force
            if (!force && stopAfterGracefulStatusChecks != null) {
                gracefulStatusChecksRemaining = stopAfterGracefulStatusChecks
            } else if (force || !gracefulStopKeepsRunning) {
                running = false
            }
            return status(nativeId, token)
        }

        override suspend fun logs(nativeId: String, token: String, tailBytes: Int) =
            Result.success(TermuxSupervisorLogs("", "", false))
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

    private fun recordFor(token: String) = ManagedExecutionLedgerRecord(
        executionId = "termux:tx_12345678",
        runtime = "termux",
        nativeId = "tx_12345678",
        ownerAssistantId = caller.assistantId,
        ownerConversationId = caller.conversationId,
        ownerOrigin = caller.origin.name,
        status = "RUNNING",
        pid = 101,
        processGroupId = 101,
        processStartTicks = 8080,
        tokenHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) },
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private companion object {
        val tokenProvider = ExecutionTokenProvider { "a".repeat(64) }
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
            allowedRuntimes = setOf(ManagedExecutionRuntime.TERMUX),
        )
    }
}
