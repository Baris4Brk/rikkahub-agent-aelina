package me.rerere.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class WorkspaceProcessManager(
    private val workspaceManager: WorkspaceManager,
    private val launcher: ManagedWorkspaceProcessLauncher,
    private val persistence: WorkspaceProcessPersistence,
    private val host: WorkspaceProcessHost = NoOpWorkspaceProcessHost,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val processIdFactory: () -> String = { "wp_${UUID.randomUUID()}" },
) {
    private data class RuntimeEntry(
        val workspaceRoot: String,
        val definition: WorkspaceProcessDefinition,
        val status: WorkspaceProcessStatus,
        val process: Process? = null,
        val hostPid: Long? = null,
        val logPumps: WorkspaceProcessLogPumps? = null,
        val generation: Long = 0L,
    )

    private val mutex = Mutex()
    private val restoreMutex = Mutex()
    private val entries = linkedMapOf<String, RuntimeEntry>()
    private val workspacesPendingDeletion = mutableSetOf<String>()
    private val _summary = MutableStateFlow(WorkspaceProcessSummary())
    val summary: StateFlow<WorkspaceProcessSummary> = _summary.asStateFlow()

    suspend fun start(request: WorkspaceProcessStartRequest): WorkspaceProcessResult {
        val validation = validateStartRequest(request)
        if (validation != null) return validation
        val processId = requireValidWorkspaceProcessId(processIdFactory())
        val now = clockMillis()
        val definition = WorkspaceProcessDefinition(
            id = processId,
            workspaceId = request.workspaceId,
            name = request.name.trim().ifBlank { "process-${processId.takeLast(8)}" },
            command = request.command.trim(),
            cwd = request.cwd.trim(),
            keepAwake = request.keepAwake,
            restartPolicy = request.restartPolicy,
            desiredState = WorkspaceDesiredState.RUNNING,
            createdAt = now,
        )
        mutex.withLock {
            if (request.workspaceId in workspacesPendingDeletion) {
                return failure("WORKSPACE_DELETING", "Workspace is being deleted.")
            }
            if (activeEntryCountLocked() >= MAX_MANAGED_WORKSPACE_PROCESSES) {
                return failure("PROCESS_LIMIT_REACHED", "Managed process limit reached.")
            }
            persistence.write(request.workspaceRoot, definition)
            entries[processId] = RuntimeEntry(
                workspaceRoot = request.workspaceRoot,
                definition = definition,
                status = WorkspaceProcessStatus.STARTING,
            )
            updateSummaryLocked()
        }
        return withContext(NonCancellable) {
            launchReserved(processId, automatic = false)
        }
    }

    suspend fun list(
        workspaceId: String? = null,
        includeStopped: Boolean = false,
    ): List<WorkspaceProcessSnapshot> = mutex.withLock {
        entries.values
            .asSequence()
            .filter { workspaceId == null || it.definition.workspaceId == workspaceId }
            .filter { includeStopped || it.definition.desiredState == WorkspaceDesiredState.RUNNING || it.process?.isAlive == true }
            .map(::snapshot)
            .sortedBy { it.processId }
            .toList()
    }

    suspend fun status(processId: String): WorkspaceProcessResult {
        val validId = runCatching { requireValidWorkspaceProcessId(processId) }.getOrNull()
            ?: return failure("INVALID_ARGUMENTS", "Invalid process id.")
        return mutex.withLock {
            val entry = entries[validId]
                ?: return@withLock failure("PROCESS_NOT_FOUND", "Managed workspace process was not found.")
            success("PROCESS_STATUS", "Workspace process status.", snapshot(entry))
        }
    }

    suspend fun logs(
        processId: String,
        stream: WorkspaceProcessLogStream,
        tailBytes: Int = DEFAULT_WORKSPACE_PROCESS_LOG_TAIL_BYTES,
    ): WorkspaceProcessResult {
        val entry = mutex.withLock {
            entries[processId]
        } ?: return failure("PROCESS_NOT_FOUND", "Managed workspace process was not found.")
        val logs = withContext(Dispatchers.IO) {
            readWorkspaceProcessLogs(
                stdoutFile = persistence.stdoutFile(entry.workspaceRoot, processId),
                stderrFile = persistence.stderrFile(entry.workspaceRoot, processId),
                stream = stream,
                requestedTailBytes = tailBytes,
            )
        }
        return WorkspaceProcessResult(
            ok = true,
            code = "OK",
            message = "Workspace process logs.",
            process = snapshot(entry),
            logs = logs,
        )
    }

    suspend fun stop(
        processId: String,
        force: Boolean = false,
        reason: WorkspaceProcessStopReason = WorkspaceProcessStopReason.USER,
    ): WorkspaceProcessResult {
        val plan = mutex.withLock {
            val current = entries[processId]
                ?: return@withLock null
            val desired = if (reason == WorkspaceProcessStopReason.RESTART) {
                WorkspaceDesiredState.RUNNING
            } else {
                WorkspaceDesiredState.STOPPED
            }
            val updated = current.copy(
                definition = current.definition.copy(desiredState = desired),
                status = WorkspaceProcessStatus.STOPPING,
                generation = current.generation + 1,
            )
            persistence.write(updated.workspaceRoot, updated.definition)
            entries[processId] = updated
            updateSummaryLocked()
            updated
        } ?: return failure("PROCESS_NOT_FOUND", "Managed workspace process was not found.")

        val process = plan.process
        if (process != null && process.isAlive) {
            withContext(Dispatchers.IO) {
                process.destroy()
                if (force || !process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(FORCE_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                }
                plan.logPumps?.awaitClosed()
            }
        }
        val confirmedStopped = process?.isAlive != true
        val result = mutex.withLock {
            val current = entries[processId] ?: plan
            if (current.generation != plan.generation) {
                return@withLock if (current.process?.isAlive == true) {
                    failure(
                        "PROCESS_STATE_CHANGED",
                        "Workspace process changed while it was being stopped.",
                        snapshot(current),
                    )
                } else {
                    success("PROCESS_STOPPED", "Workspace process stopped.", snapshot(current))
                }
            }
            val exitCode = process?.exitCodeOrNull()
            val final = current.copy(
                definition = current.definition.copy(
                    lastExitCode = exitCode ?: current.definition.lastExitCode,
                    lastExitAt = if (process != null) clockMillis() else current.definition.lastExitAt,
                    lastErrorCode = if (confirmedStopped) null else "PROCESS_STOP_FAILED",
                ),
                status = if (confirmedStopped) WorkspaceProcessStatus.STOPPED else WorkspaceProcessStatus.FAILED,
                process = if (confirmedStopped) null else process,
                hostPid = if (confirmedStopped) null else current.hostPid,
                logPumps = if (confirmedStopped) null else current.logPumps,
            )
            persistence.write(final.workspaceRoot, final.definition)
            entries[processId] = final
            updateSummaryLocked()
            if (confirmedStopped) {
                success("PROCESS_STOPPED", "Workspace process stopped.", snapshot(final))
            } else {
                failure("PROCESS_STOP_FAILED", "Unable to confirm workspace process termination.", snapshot(final))
            }
        }
        stopHostIfIdle()
        return result
    }

    suspend fun restart(processId: String): WorkspaceProcessResult {
        val existing = mutex.withLock { entries[processId] }
            ?: return failure("PROCESS_NOT_FOUND", "Managed workspace process was not found.")
        if (mutex.withLock { existing.definition.workspaceId in workspacesPendingDeletion }) {
            return failure("WORKSPACE_DELETING", "Workspace is being deleted.", snapshot(existing))
        }
        if (existing.process?.isAlive == true) {
            val stopped = stop(processId, force = true, reason = WorkspaceProcessStopReason.RESTART)
            if (!stopped.ok) return stopped
        }
        mutex.withLock {
            val current = entries[processId]
                ?: return@withLock
            val reset = current.copy(
                definition = current.definition.copy(
                    desiredState = WorkspaceDesiredState.RUNNING,
                    recentRestartTimestamps = emptyList(),
                    lastErrorCode = null,
                ),
                status = WorkspaceProcessStatus.STARTING,
                process = null,
                hostPid = null,
                logPumps = null,
                generation = current.generation + 1,
            )
            persistence.write(reset.workspaceRoot, reset.definition)
            entries[processId] = reset
            updateSummaryLocked()
        }
        val launched = launchReserved(processId, automatic = false)
        return if (launched.ok) launched.copy(code = "PROCESS_RESTARTED", message = "Workspace process restarted.") else launched
    }

    suspend fun stopAll(
        force: Boolean,
        reason: WorkspaceProcessStopReason,
    ): WorkspaceStopAllResult = stopMatching(force, reason) { true }

    suspend fun stopByWorkspace(
        workspaceId: String,
        force: Boolean,
    ): WorkspaceStopAllResult {
        mutex.withLock { workspacesPendingDeletion += workspaceId }
        val result = stopMatching(force, WorkspaceProcessStopReason.WORKSPACE_DELETE) {
            it.definition.workspaceId == workspaceId
        }
        if (!result.ok) releaseWorkspaceDeletion(workspaceId)
        return result
    }

    suspend fun releaseWorkspaceDeletion(workspaceId: String) {
        mutex.withLock { workspacesPendingDeletion -= workspaceId }
    }

    suspend fun hasDesiredProcesses(validWorkspaces: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            persistence.scan(validWorkspaces).any {
                it.definition.desiredState == WorkspaceDesiredState.RUNNING
            }
        }

    suspend fun reconcileEmergencyStop(validWorkspaces: Map<String, String>): WorkspaceStopAllResult {
        val stopResult = stopAll(
            force = true,
            reason = WorkspaceProcessStopReason.EMERGENCY_STOP,
        )
        restoreMutex.withLock {
            mutex.withLock {
                persistence.scan(validWorkspaces).forEach { stored ->
                    val existing = entries[stored.definition.id]
                    val stopped = (existing?.definition ?: stored.definition).copy(
                        desiredState = WorkspaceDesiredState.STOPPED,
                        lastErrorCode = existing?.definition?.lastErrorCode ?: "EMERGENCY_STOP_ACTIVE",
                    )
                    persistence.write(stored.workspaceRoot, stopped)
                    entries[stopped.id] = if (existing?.process?.isAlive == true) {
                        existing.copy(definition = stopped)
                    } else {
                        RuntimeEntry(
                            workspaceRoot = stored.workspaceRoot,
                            definition = stopped,
                            status = WorkspaceProcessStatus.STOPPED,
                        )
                    }
                }
                updateSummaryLocked()
            }
        }
        if (_summary.value.activeCount == 0) host.stopForegroundHost()
        return stopResult
    }

    suspend fun restoreDesiredProcesses(validWorkspaces: Map<String, String>) = withContext(NonCancellable) {
        restoreMutex.withLock {
            val toRestore = mutableListOf<String>()
            mutex.withLock {
                var remainingSlots = (MAX_MANAGED_WORKSPACE_PROCESSES - activeEntryCountLocked()).coerceAtLeast(0)
                persistence.scan(validWorkspaces)
                    .sortedBy { it.definition.createdAt }
                    .forEach { stored ->
                        val definition = stored.definition
                        if (entries[definition.id] != null) return@forEach
                        if (definition.desiredState == WorkspaceDesiredState.STOPPED) {
                            entries[definition.id] = RuntimeEntry(
                                workspaceRoot = stored.workspaceRoot,
                                definition = definition,
                                status = WorkspaceProcessStatus.STOPPED,
                            )
                            return@forEach
                        }
                        if (definition.restartPolicy == WorkspaceRestartPolicy.NEVER) {
                            val lost = definition.copy(
                                desiredState = WorkspaceDesiredState.STOPPED,
                                lastErrorCode = "PROCESS_LOST",
                            )
                            persistence.write(stored.workspaceRoot, lost)
                            entries[definition.id] = RuntimeEntry(
                                workspaceRoot = stored.workspaceRoot,
                                definition = lost,
                                status = WorkspaceProcessStatus.LOST,
                            )
                        } else if (remainingSlots > 0) {
                            entries[definition.id] = RuntimeEntry(
                                workspaceRoot = stored.workspaceRoot,
                                definition = definition,
                                status = WorkspaceProcessStatus.RECOVERING,
                            )
                            toRestore += definition.id
                            remainingSlots--
                        } else {
                            val limited = definition.copy(
                                desiredState = WorkspaceDesiredState.STOPPED,
                                lastErrorCode = "PROCESS_LIMIT_REACHED",
                            )
                            persistence.write(stored.workspaceRoot, limited)
                            entries[definition.id] = RuntimeEntry(
                                workspaceRoot = stored.workspaceRoot,
                                definition = limited,
                                status = WorkspaceProcessStatus.FAILED,
                            )
                        }
                    }
                updateSummaryLocked()
            }
            toRestore.forEach { processId ->
                launchReserved(processId, automatic = true)
            }
            stopHostIfIdle()
        }
    }

    private suspend fun launchReserved(processId: String, automatic: Boolean): WorkspaceProcessResult {
        val reservation = mutex.withLock {
            val current = entries[processId]
                ?: return@withLock null
            if (current.definition.desiredState != WorkspaceDesiredState.RUNNING) {
                return@withLock current
            }
            if (current.process?.isAlive == true) {
                return@withLock current
            }
            if (current.definition.workspaceId in workspacesPendingDeletion) {
                val stopped = current.copy(
                    definition = current.definition.copy(
                        desiredState = WorkspaceDesiredState.STOPPED,
                        lastErrorCode = "WORKSPACE_DELETING",
                    ),
                    status = WorkspaceProcessStatus.STOPPED,
                    process = null,
                    hostPid = null,
                    logPumps = null,
                    generation = current.generation + 1,
                )
                persistence.write(stopped.workspaceRoot, stopped.definition)
                entries[processId] = stopped
                updateSummaryLocked()
                return@withLock stopped
            }
            if (automatic) {
                val now = clockMillis()
                val recent = current.definition.recentRestartTimestamps.filter { now - it <= RESTART_WINDOW_MILLIS }
                if (recent.size >= MAX_AUTOMATIC_RESTARTS) {
                    val failed = current.copy(
                        definition = current.definition.copy(
                            desiredState = WorkspaceDesiredState.STOPPED,
                            recentRestartTimestamps = recent,
                            lastErrorCode = "RESTART_LOOP_DETECTED",
                        ),
                        status = WorkspaceProcessStatus.FAILED,
                        generation = current.generation + 1,
                    )
                    persistence.write(failed.workspaceRoot, failed.definition)
                    entries[processId] = failed
                    updateSummaryLocked()
                    return@withLock failed
                }
                val recovering = current.copy(
                    definition = current.definition.copy(recentRestartTimestamps = recent + now),
                    status = WorkspaceProcessStatus.RECOVERING,
                    generation = current.generation + 1,
                )
                persistence.write(recovering.workspaceRoot, recovering.definition)
                entries[processId] = recovering
                updateSummaryLocked()
                recovering
            } else {
                current
            }
        } ?: return failure("PROCESS_NOT_FOUND", "Managed workspace process was not found.")

        if (reservation.definition.lastErrorCode == "RESTART_LOOP_DETECTED") {
            stopHostIfIdle()
            return failure("RESTART_LOOP_DETECTED", "Automatic restart limit reached.", snapshot(reservation))
        }
        if (reservation.process?.isAlive == true) {
            return success("PROCESS_ALREADY_RUNNING", "Workspace process is already running.", snapshot(reservation))
        }
        if (reservation.definition.desiredState != WorkspaceDesiredState.RUNNING) {
            return failure("PROCESS_STOPPED", "Workspace process was stopped before launch.", snapshot(reservation))
        }

        val hostResult = host.ensureForegroundHost()
        if (hostResult.isFailure) {
            return failLaunch(
                reservation,
                code = "FOREGROUND_SERVICE_NOT_ALLOWED",
                message = hostResult.exceptionOrNull()?.message ?: "Foreground service could not be started.",
                automatic = automatic,
            )
        }

        val process = try {
            withContext(Dispatchers.IO + NonCancellable) {
                launcher.startManagedProcess(
                    ManagedWorkspaceProcessContext(
                        root = reservation.workspaceRoot,
                        command = reservation.definition.command,
                        cwd = reservation.definition.cwd,
                        filesDir = workspaceManager.filesDir(reservation.workspaceRoot),
                        linuxDir = workspaceManager.linuxDir(reservation.workspaceRoot),
                        tempDir = persistence.processTempDirectory(reservation.workspaceRoot, processId),
                    ),
                )
            }
        } catch (error: Throwable) {
            return failLaunch(
                reservation,
                code = "PROCESS_START_FAILED",
                message = error.message ?: "Workspace process failed to start.",
                automatic = automatic,
            )
        }
        runCatching { process.outputStream.close() }
        val pumps = try {
            withContext(Dispatchers.IO + NonCancellable) {
                WorkspaceProcessLogPumps(
                    stdout = process.inputStream,
                    stderr = process.errorStream,
                    stdoutFile = persistence.stdoutFile(reservation.workspaceRoot, processId),
                    stderrFile = persistence.stderrFile(reservation.workspaceRoot, processId),
                )
            }
        } catch (error: Throwable) {
            discardLaunchedProcess(process, pumps = null)
            return failLaunch(
                reservation,
                code = "PROCESS_LOG_INIT_FAILED",
                message = error.message ?: "Workspace process logs could not be opened.",
                automatic = automatic,
            )
        }
        val committed = try {
            withContext(Dispatchers.IO + NonCancellable) {
                mutex.withLock {
                    val current = entries[processId]
                    if (current == null || current.generation != reservation.generation ||
                        current.definition.desiredState != WorkspaceDesiredState.RUNNING
                    ) {
                        false
                    } else {
                        val now = clockMillis()
                        val running = current.copy(
                            definition = current.definition.copy(
                                lastStartedAt = now,
                                lastErrorCode = null,
                            ),
                            status = WorkspaceProcessStatus.RUNNING,
                            process = process,
                            hostPid = processPid(process),
                            logPumps = pumps,
                        )
                        persistence.write(running.workspaceRoot, running.definition)
                        entries[processId] = running
                        updateSummaryLocked()
                        true
                    }
                }
            }
        } catch (error: Throwable) {
            discardLaunchedProcess(process, pumps)
            return failLaunch(
                reservation,
                code = "PROCESS_STATE_PERSIST_FAILED",
                message = error.message ?: "Workspace process state could not be persisted.",
                automatic = automatic,
            )
        }
        if (!committed) {
            discardLaunchedProcess(process, pumps)
            stopHostIfIdle()
            return failure("PROCESS_STOPPED", "Workspace process was stopped before launch completed.")
        }
        monitorExit(processId, process, pumps)
        return status(processId).copy(code = "PROCESS_STARTED", message = "Workspace process started.")
    }

    private suspend fun failLaunch(
        reservation: RuntimeEntry,
        code: String,
        message: String,
        automatic: Boolean,
    ): WorkspaceProcessResult {
        val failed = mutex.withLock {
            val current = entries[reservation.definition.id] ?: reservation
            if (current.generation != reservation.generation) return@withLock current
            val updated = current.copy(
                definition = current.definition.copy(
                    desiredState = if (automatic) current.definition.desiredState else WorkspaceDesiredState.STOPPED,
                    lastExitAt = clockMillis(),
                    lastErrorCode = code,
                ),
                status = WorkspaceProcessStatus.FAILED,
                process = null,
                hostPid = null,
                logPumps = null,
            )
            persistence.write(updated.workspaceRoot, updated.definition)
            entries[updated.definition.id] = updated
            updateSummaryLocked()
            updated
        }
        if (automatic && failed.definition.desiredState == WorkspaceDesiredState.RUNNING) {
            scope.launch(Dispatchers.IO) {
                delay(RESTART_DELAY_MILLIS)
                launchReserved(failed.definition.id, automatic = true)
            }
        } else {
            stopHostIfIdle()
        }
        return failure(code, message, snapshot(failed))
    }

    private suspend fun discardLaunchedProcess(
        process: Process,
        pumps: WorkspaceProcessLogPumps?,
    ) = withContext(Dispatchers.IO + NonCancellable) {
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(FORCE_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        pumps?.awaitClosed()
    }

    private fun monitorExit(processId: String, process: Process, pumps: WorkspaceProcessLogPumps) {
        scope.launch(Dispatchers.IO) {
            val exitCode = process.waitFor()
            pumps.awaitClosed()
            handleExit(processId, process, exitCode)
        }
    }

    private suspend fun handleExit(processId: String, process: Process, exitCode: Int) {
        var restart = false
        mutex.withLock {
            val current = entries[processId] ?: return
            if (current.process !== process) return
            val now = clockMillis()
            val definition = current.definition.copy(lastExitCode = exitCode, lastExitAt = now)
            val updated = when {
                current.status == WorkspaceProcessStatus.STOPPING -> current.copy(
                    definition = definition,
                    status = WorkspaceProcessStatus.STOPPED,
                    process = null,
                    hostPid = null,
                    logPumps = null,
                )
                definition.desiredState == WorkspaceDesiredState.STOPPED -> current.copy(
                    definition = definition,
                    status = WorkspaceProcessStatus.STOPPED,
                    process = null,
                    hostPid = null,
                    logPumps = null,
                )
                definition.restartPolicy == WorkspaceRestartPolicy.ALWAYS -> current.copy(
                    definition = definition,
                    status = WorkspaceProcessStatus.RECOVERING,
                    process = null,
                    hostPid = null,
                    logPumps = null,
                ).also { restart = true }
                definition.restartPolicy == WorkspaceRestartPolicy.ON_FAILURE && exitCode != 0 -> current.copy(
                    definition = definition,
                    status = WorkspaceProcessStatus.RECOVERING,
                    process = null,
                    hostPid = null,
                    logPumps = null,
                ).also { restart = true }
                else -> current.copy(
                    definition = definition.copy(desiredState = WorkspaceDesiredState.STOPPED),
                    status = WorkspaceProcessStatus.EXITED,
                    process = null,
                    hostPid = null,
                    logPumps = null,
                )
            }
            persistence.write(updated.workspaceRoot, updated.definition)
            entries[processId] = updated
            updateSummaryLocked()
        }
        if (restart) {
            delay(RESTART_DELAY_MILLIS)
            launchReserved(processId, automatic = true)
        } else {
            stopHostIfIdle()
        }
    }

    private suspend fun stopMatching(
        force: Boolean,
        reason: WorkspaceProcessStopReason,
        predicate: (RuntimeEntry) -> Boolean,
    ): WorkspaceStopAllResult {
        val ids = mutex.withLock { entries.values.filter(predicate).map { it.definition.id } }
        val results = coroutineScope {
            ids.map { id -> async { id to stop(id, force, reason) } }.awaitAll()
        }
        val stopped = results.filter { it.second.ok }.map { it.first }
        val failed = results.filterNot { it.second.ok }.map { it.first }
        return WorkspaceStopAllResult(
            ok = failed.isEmpty(),
            code = if (failed.isEmpty()) "PROCESS_STOPPED" else "PROCESS_STOP_FAILED",
            stoppedProcessIds = stopped,
            failedProcessIds = failed,
        )
    }

    private fun validateStartRequest(request: WorkspaceProcessStartRequest): WorkspaceProcessResult? {
        if (request.workspaceId.isBlank() || request.workspaceRoot.isBlank()) {
            return failure("WORKSPACE_NOT_FOUND", "Workspace is required.")
        }
        if (request.command.isBlank() || '\u0000' in request.command || '\u0000' in request.cwd) {
            return failure("INVALID_ARGUMENTS", "Command or working directory is invalid.")
        }
        val command = request.command.trim()
        if (command.matches(Regex("(?is)^\\s*(?:exec\\s+)?nohup(?:\\s|$).*$")) ||
            command.matches(Regex("(?s).*[^&]&\\s*$"))
        ) {
            return failure(
                "BACKGROUND_COMMAND_REJECTED",
                "Run managed programs in the foreground; do not use nohup or a trailing ampersand.",
            )
        }
        if (!workspaceManager.hasRootfs(request.workspaceRoot)) {
            return failure("WORKSPACE_NOT_INITIALIZED", "Workspace Rootfs is not initialized.")
        }
        if (!request.cwd.startsWith('/')) {
            val workingDirectory = File(workspaceManager.filesDir(request.workspaceRoot), request.cwd)
            if (!workingDirectory.isDirectory) {
                return failure("INVALID_CWD", "Workspace working directory does not exist.")
            }
        }
        return null
    }

    private suspend fun stopHostIfIdle() {
        val shouldStop = mutex.withLock {
            entries.values.none { it.definition.desiredState == WorkspaceDesiredState.RUNNING }
        }
        if (shouldStop) host.stopForegroundHost()
    }

    private fun activeEntryCountLocked(): Int = entries.values.count {
        it.status in setOf(
            WorkspaceProcessStatus.STARTING,
            WorkspaceProcessStatus.RUNNING,
            WorkspaceProcessStatus.RECOVERING,
            WorkspaceProcessStatus.STOPPING,
        )
    }

    private fun updateSummaryLocked() {
        _summary.value = WorkspaceProcessSummary(
            activeCount = entries.values.count { it.process?.isAlive == true },
            keepAwakeCount = entries.values.count { it.process?.isAlive == true && it.definition.keepAwake },
            recoveringCount = entries.values.count { it.status == WorkspaceProcessStatus.RECOVERING },
            desiredRunningCount = entries.values.count {
                it.definition.desiredState == WorkspaceDesiredState.RUNNING
            },
        )
    }

    private fun snapshot(entry: RuntimeEntry): WorkspaceProcessSnapshot = WorkspaceProcessSnapshot(
        processId = entry.definition.id,
        workspaceId = entry.definition.workspaceId,
        name = entry.definition.name,
        status = entry.status,
        hostPid = entry.hostPid,
        alive = entry.process?.isAlive == true,
        startedAt = entry.definition.lastStartedAt,
        restartPolicy = entry.definition.restartPolicy,
        desiredState = entry.definition.desiredState,
        keepAwake = entry.definition.keepAwake,
        lastExitCode = entry.definition.lastExitCode,
        lastExitAt = entry.definition.lastExitAt,
        lastErrorCode = entry.definition.lastErrorCode,
    )

    private fun success(
        code: String,
        message: String,
        process: WorkspaceProcessSnapshot? = null,
    ) = WorkspaceProcessResult(true, code, message, process)

    private fun failure(
        code: String,
        message: String,
        process: WorkspaceProcessSnapshot? = null,
    ) = WorkspaceProcessResult(false, code, message, process)

    private fun Process.exitCodeOrNull(): Int? = runCatching { exitValue() }.getOrNull()

    /** Android API 26 does not expose Process.pid(), so keep PID diagnostic-only. */
    private fun processPid(process: Process): Long? = runCatching {
        generateSequence(process.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz ->
                runCatching { clazz.getDeclaredField("pid").apply { isAccessible = true } }.getOrNull()
            }
            .firstOrNull()
            ?.get(process)
            ?.let { it as? Number }
            ?.toLong()
    }.getOrNull()

    companion object {
        private const val STOP_GRACE_MILLIS = 2_000L
        private const val FORCE_STOP_GRACE_MILLIS = 1_000L
        private const val RESTART_DELAY_MILLIS = 250L
        private const val RESTART_WINDOW_MILLIS = 5 * 60 * 1_000L
        private const val MAX_AUTOMATIC_RESTARTS = 3
    }
}
