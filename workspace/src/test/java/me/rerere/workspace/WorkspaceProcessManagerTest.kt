package me.rerere.workspace

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkspaceProcessManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reservation exposes real id and never launches before startReserved`() = runBlocking {
        val fixture = fixture()
        fixture.launcher.processes += FakeProcess()

        val reserved = fixture.manager.reserveProcessId(fixture.request())

        assertTrue(reserved.ok)
        assertEquals("PROCESS_RESERVED", reserved.code)
        assertEquals("wp_10000000", reserved.process?.processId)
        assertEquals(0, fixture.launcher.launchCount)
        assertEquals(WorkspaceProcessStatus.STARTING, reserved.process?.status)

        val started = fixture.manager.startReserved("wp_10000000")

        assertTrue(started.ok)
        assertEquals("wp_10000000", started.process?.processId)
        assertEquals(1, fixture.launcher.launchCount)
        val process = requireNotNull(started.process)
        assertTrue(process.runtimeInstanceMarker?.endsWith(":${process.startedAt}") == true)
    }

    @Test
    fun `start returns immediately and stop confirms process termination`() = runBlocking {
        val fixture = fixture()
        val process = FakeProcess()
        fixture.launcher.processes += process

        val started = fixture.manager.start(fixture.request())

        assertTrue(started.ok)
        assertEquals("PROCESS_STARTED", started.code)
        assertTrue(started.process?.alive == true)
        assertEquals(1, fixture.host.startCalls)

        val stopped = fixture.manager.stop(started.process!!.processId, force = false)
        assertTrue(stopped.ok)
        assertFalse(process.isAlive)
        assertEquals(WorkspaceDesiredState.STOPPED, stopped.process?.desiredState)
    }

    @Test
    fun `graceful stop never escalates until force is explicitly requested`() = runBlocking {
        val fixture = fixture()
        val process = FakeProcess(stopOnDestroy = false)
        fixture.launcher.processes += process
        val started = fixture.manager.start(fixture.request())

        val graceful = fixture.manager.stop(started.process!!.processId, force = false)

        assertFalse(graceful.ok)
        assertTrue(process.isAlive)
        assertEquals(0, process.forceCalls)

        val forced = fixture.manager.stop(started.process!!.processId, force = true)
        assertTrue(forced.ok)
        assertFalse(process.isAlive)
        assertEquals(1, process.forceCalls)
    }

    @Test
    fun `manual restart keeps process id`() = runBlocking {
        val fixture = fixture()
        fixture.launcher.processes += FakeProcess()
        fixture.launcher.processes += FakeProcess()
        val started = fixture.manager.start(fixture.request())
        val processId = requireNotNull(started.process).processId

        val restarted = fixture.manager.restart(processId)

        assertTrue(restarted.ok)
        assertEquals("PROCESS_RESTARTED", restarted.code)
        assertEquals(processId, restarted.process?.processId)
        assertEquals(2, fixture.launcher.launchCount)
    }

    @Test
    fun `manager enforces active process limit`() = runBlocking {
        val fixture = fixture()
        repeat(MAX_MANAGED_WORKSPACE_PROCESSES) { fixture.launcher.processes += FakeProcess() }
        repeat(MAX_MANAGED_WORKSPACE_PROCESSES) {
            assertTrue(fixture.manager.start(fixture.request(name = "p$it")).ok)
        }

        val rejected = fixture.manager.start(fixture.request(name = "overflow"))

        assertFalse(rejected.ok)
        assertEquals("PROCESS_LIMIT_REACHED", rejected.code)
    }

    @Test
    fun `always policy restarts after natural exit`() = runBlocking {
        val fixture = fixture()
        val first = FakeProcess()
        fixture.launcher.processes += first
        fixture.launcher.processes += FakeProcess()
        val started = fixture.manager.start(
            fixture.request(restartPolicy = WorkspaceRestartPolicy.ALWAYS),
        )

        first.complete(0)

        withTimeout(3_000L) {
            while (fixture.launcher.launchCount < 2) delay(20L)
        }
        assertTrue(fixture.manager.status(started.process!!.processId).process?.alive == true)
    }

    @Test
    fun `never policy is marked lost during restore`() = runBlocking {
        val fixture = fixture()
        assertEquals(WorkspaceProcessManagerState.NOT_STARTED, fixture.manager.initializationState.value)
        val definition = WorkspaceProcessDefinition(
            id = "wp_12345678",
            workspaceId = "workspace-a",
            name = "server",
            command = "python server.py",
            restartPolicy = WorkspaceRestartPolicy.NEVER,
            desiredState = WorkspaceDesiredState.RUNNING,
            createdAt = 1L,
        )
        fixture.persistence.write("root-a", definition)

        fixture.manager.restoreDesiredProcesses(mapOf("workspace-a" to "root-a"))

        assertEquals(WorkspaceProcessManagerState.READY, fixture.manager.initializationState.value)
        val status = fixture.manager.status(definition.id)
        assertEquals(WorkspaceProcessStatus.LOST, status.process?.status)
        assertEquals(WorkspaceDesiredState.STOPPED, status.process?.desiredState)
        assertEquals(0, fixture.launcher.launchCount)
    }

    @Test
    fun `emergency stop disables automatic restart for every managed process`() = runBlocking {
        val fixture = fixture()
        val first = FakeProcess()
        val second = FakeProcess()
        fixture.launcher.processes += first
        fixture.launcher.processes += second
        val one = fixture.manager.start(
            fixture.request(name = "one", restartPolicy = WorkspaceRestartPolicy.ALWAYS),
        )
        val two = fixture.manager.start(
            fixture.request(name = "two", restartPolicy = WorkspaceRestartPolicy.ON_FAILURE),
        )

        val stopped = fixture.manager.stopAll(
            force = true,
            reason = WorkspaceProcessStopReason.EMERGENCY_STOP,
        )

        assertTrue(stopped.ok)
        assertEquals(setOf(one.process!!.processId, two.process!!.processId), stopped.stoppedProcessIds.toSet())
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        delay(1_200L)
        assertEquals(2, fixture.launcher.launchCount)
        assertEquals(0, fixture.manager.summary.value.desiredRunningCount)
    }

    @Test
    fun `workspace stop leaves processes from other workspaces running`() = runBlocking {
        val fixture = fixture()
        val first = FakeProcess()
        val second = FakeProcess()
        fixture.launcher.processes += first
        fixture.launcher.processes += second
        val one = fixture.manager.start(fixture.request(name = "one", workspaceId = "workspace-a"))
        val two = fixture.manager.start(fixture.request(name = "two", workspaceId = "workspace-b"))

        val stopped = fixture.manager.stopByWorkspace("workspace-a", force = true)

        assertTrue(stopped.ok)
        assertEquals(listOf(one.process!!.processId), stopped.stoppedProcessIds)
        assertFalse(first.isAlive)
        assertTrue(second.isAlive)
        assertTrue(fixture.manager.status(two.process!!.processId).process?.alive == true)
    }

    @Test
    fun `managed launch uses the persisted workspace root rather than the workspace id`() = runBlocking {
        val fixture = fixture()
        fixture.launcher.processes += FakeProcess()

        val started = fixture.manager.start(
            fixture.request(workspaceId = "workspace-id-different-from-root"),
        )

        assertTrue(started.ok)
        assertEquals("root-a", fixture.launcher.contexts.single().root)
    }

    @Test
    fun `delayed automatic restart cannot duplicate a manual restart`() = runBlocking {
        val fixture = fixture()
        val first = FakeProcess()
        fixture.launcher.processes += first
        fixture.launcher.processes += FakeProcess()
        fixture.launcher.processes += FakeProcess()
        val started = fixture.manager.start(
            fixture.request(restartPolicy = WorkspaceRestartPolicy.ALWAYS),
        )
        val processId = requireNotNull(started.process).processId
        first.complete(1)
        withTimeout(1_000L) {
            while (fixture.manager.status(processId).process?.status !=
                WorkspaceProcessStatus.RECOVERING
            ) {
                delay(10L)
            }
        }

        val restarted = fixture.manager.restart(processId)
        assertTrue(restarted.ok)
        delay(1_200L)

        assertEquals(2, fixture.launcher.launchCount)
        assertEquals(WorkspaceProcessStatus.RUNNING, fixture.manager.status(processId).process?.status)
    }

    @Test
    fun `emergency stop disables persisted definitions that were not loaded yet`() = runBlocking {
        val fixture = fixture()
        val definition = WorkspaceProcessDefinition(
            id = "wp_12345678",
            workspaceId = "workspace-a",
            name = "server",
            command = "python server.py",
            restartPolicy = WorkspaceRestartPolicy.ALWAYS,
            desiredState = WorkspaceDesiredState.RUNNING,
            createdAt = 1L,
        )
        fixture.persistence.write("root-a", definition)

        val stopped = fixture.manager.reconcileEmergencyStop(mapOf("workspace-a" to "root-a"))

        assertTrue(stopped.ok)
        val stored = fixture.persistence.read("root-a", definition.id)
        assertEquals(WorkspaceDesiredState.STOPPED, stored?.desiredState)
        assertEquals("EMERGENCY_STOP_ACTIVE", stored?.lastErrorCode)
        assertEquals(0, fixture.launcher.launchCount)
    }

    @Test
    fun `workspace deletion blocks new managed starts until a failed file deletion is released`() = runBlocking {
        val fixture = fixture()
        fixture.launcher.processes += FakeProcess()
        assertTrue(fixture.manager.start(fixture.request()).ok)
        assertTrue(fixture.manager.stopByWorkspace("workspace-a", force = true).ok)

        val blocked = fixture.manager.start(fixture.request(name = "late"))
        assertFalse(blocked.ok)
        assertEquals("WORKSPACE_DELETING", blocked.code)

        fixture.manager.releaseWorkspaceDeletion("workspace-a")
        fixture.launcher.processes += FakeProcess()
        assertTrue(fixture.manager.start(fixture.request(name = "retry")).ok)
    }

    @Test
    fun `service recovery does not steal a user start that is still launching`() = runBlocking {
        val fixture = fixture()
        fixture.launcher.processes += FakeProcess()
        fixture.launcher.blockNextStart = true
        val started = async(Dispatchers.Default) {
            fixture.manager.start(
                fixture.request(restartPolicy = WorkspaceRestartPolicy.NEVER),
            )
        }
        assertTrue(fixture.launcher.startEntered.await(1, TimeUnit.SECONDS))

        try {
            fixture.manager.restoreDesiredProcesses(mapOf("workspace-a" to "root-a"))
        } finally {
            fixture.launcher.allowStart.countDown()
        }
        val result = started.await()

        assertTrue(result.ok)
        assertEquals(WorkspaceProcessStatus.RUNNING, result.process?.status)
        assertEquals(1, fixture.launcher.launchCount)
    }

    private fun fixture(): Fixture {
        val workspaceManager = WorkspaceManager(temporaryFolder.newFolder())
        workspaceManager.ensureWorkspace("root-a")
        val linux = workspaceManager.linuxDir("root-a")
        java.io.File(linux, "bin").mkdirs()
        java.io.File(linux, "bin/sh").writeText("fake")
        val persistence = WorkspaceProcessPersistence(workspaceManager)
        val launcher = FakeLauncher()
        val host = FakeHost()
        val manager = WorkspaceProcessManager(
            workspaceManager = workspaceManager,
            launcher = launcher,
            persistence = persistence,
            host = host,
            processIdFactory = processIds().iterator()::next,
        )
        return Fixture(manager, persistence, launcher, host)
    }

    private fun processIds() = sequence {
        var value = 10_000_000
        while (true) yield("wp_${value++}")
    }

    private data class Fixture(
        val manager: WorkspaceProcessManager,
        val persistence: WorkspaceProcessPersistence,
        val launcher: FakeLauncher,
        val host: FakeHost,
    ) {
        fun request(
            name: String = "server",
            workspaceId: String = "workspace-a",
            restartPolicy: WorkspaceRestartPolicy = WorkspaceRestartPolicy.NEVER,
        ) = WorkspaceProcessStartRequest(
            workspaceId = workspaceId,
            workspaceRoot = "root-a",
            name = name,
            command = "python server.py",
            restartPolicy = restartPolicy,
        )
    }

    private class FakeHost : WorkspaceProcessHost {
        var startCalls = 0
        var stopCalls = 0
        override fun ensureForegroundHost(): Result<Unit> {
            startCalls++
            return Result.success(Unit)
        }

        override fun stopForegroundHost() {
            stopCalls++
        }
    }

    private class FakeLauncher : ManagedWorkspaceProcessLauncher {
        val processes = ArrayDeque<FakeProcess>()
        val contexts = mutableListOf<ManagedWorkspaceProcessContext>()
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        @Volatile var blockNextStart = false
        var launchCount = 0
        override fun startManagedProcess(context: ManagedWorkspaceProcessContext): Process {
            launchCount++
            contexts += context
            if (blockNextStart) {
                startEntered.countDown()
                allowStart.await()
                blockNextStart = false
            }
            return processes.removeFirst()
        }
    }

    private class FakeProcess(
        private val stopOnDestroy: Boolean = true,
    ) : Process() {
        private val finished = CountDownLatch(1)
        private val stdin = ByteArrayOutputStream()
        @Volatile private var alive = true
        @Volatile private var code = 0
        var forceCalls = 0

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            finished.await()
            return code
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return code
        }

        override fun destroy() {
            if (stopOnDestroy) complete(143)
        }
        override fun destroyForcibly(): Process = apply {
            forceCalls++
            complete(137)
        }
        override fun isAlive(): Boolean = alive

        fun complete(exitCode: Int) {
            if (!alive) return
            code = exitCode
            alive = false
            finished.countDown()
        }
    }
}
