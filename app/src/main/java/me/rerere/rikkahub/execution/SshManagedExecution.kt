package me.rerere.rikkahub.execution

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import me.rerere.rikkahub.data.ai.tools.local.isUsable
import me.rerere.rikkahub.data.repository.SshHostRepository

internal data class SshSavedConnection(
    val profileName: String,
    val host: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
    val timeoutMs: Int,
)

internal fun interface SshSavedConnectionResolver {
    suspend fun resolve(profileName: String): Result<SshSavedConnection>
}

internal class RepositorySshSavedConnectionResolver(
    private val repository: SshHostRepository,
) : SshSavedConnectionResolver {
    override suspend fun resolve(profileName: String): Result<SshSavedConnection> = runCatching {
        val host = repository.getByName(profileName) ?: error("ssh_saved_profile_not_found")
        val auth = SshAuth(host.password, host.privateKey, host.passphrase)
        require(auth.isUsable()) { "ssh_saved_credentials_missing" }
        SshSavedConnection(
            profileName = host.name,
            host = host.host,
            port = host.port,
            user = host.user,
            auth = auth,
            timeoutMs = DEFAULT_TIMEOUT_MS,
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
    }
}

internal data class SshSupervisorIdentity(
    val pid: Long,
    val processGroupId: Long,
    val processStartTicks: Long,
)

internal data class SshSupervisorStatus(
    val identity: SshSupervisorIdentity,
    val state: String,
    val running: Boolean,
    val identityVerified: Boolean,
    val exitCode: Int? = null,
)

internal data class SshSupervisorLogs(
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

internal interface SshManagedSupervisor {
    suspend fun start(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
        command: String,
    ): Result<SshSupervisorIdentity>

    suspend fun status(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
    ): Result<SshSupervisorStatus>

    suspend fun stop(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
        force: Boolean,
    ): Result<SshSupervisorStatus>

    suspend fun logs(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
        tailBytes: Int,
    ): Result<SshSupervisorLogs>
}

internal class SshManagedBackgroundStarter(
    private val supervisor: SshManagedSupervisor,
    private val ledger: ManagedExecutionLedger,
    private val tokenProvider: ExecutionTokenProvider,
    private val scope: CoroutineScope,
) {
    suspend fun start(
        spec: SshExecutionSpec,
        context: ToolExecutionContext,
    ): ToolExecutionHandle {
        val profileName = spec.savedProfileName
            ?: return rejected(context, "ssh_managed_requires_saved_profile")
        val nativeId = "ssh_${UUID.randomUUID().toString().replace("-", "")}" 
        val executionId = managedExecutionId(ManagedExecutionRuntime.SSH, nativeId)
        val token = tokenProvider.tokenFor(nativeId)
        val connection = SshSavedConnection(
            profileName = profileName,
            host = spec.host,
            port = spec.port,
            user = spec.user,
            auth = spec.auth,
            timeoutMs = spec.timeoutMs,
        )
        val identity = supervisor.start(connection, nativeId, token, spec.command)
            .getOrElse { failure ->
                return rejected(context, failure.message ?: "ssh_managed_start_failed")
            }
        val now = System.currentTimeMillis()
        ledger.upsert(
            ManagedExecutionLedgerRecord(
                executionId = executionId,
                runtime = ManagedExecutionRuntime.SSH.idPrefix,
                nativeId = nativeId,
                ownerAssistantId = context.assistantId,
                ownerConversationId = context.conversationId.toString(),
                ownerOrigin = context.callOrigin.name,
                status = ManagedExecutionStatus.RUNNING.name,
                profileName = profileName,
                pid = identity.pid,
                processGroupId = identity.processGroupId,
                processStartTicks = identity.processStartTicks,
                tokenHash = managedSha256(token),
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
        return LegacyToolExecutionHandle(
            executionId = executionId,
            result = scope.async {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("mode", "managed_background")
                    put("execution_id", executionId)
                    put("profile", profileName)
                }.toString()))
            },
        )
    }

    private fun rejected(
        context: ToolExecutionContext,
        code: String,
    ): ToolExecutionHandle = LegacyToolExecutionHandle(
        executionId = "ssh-managed-rejected-${context.runId}",
        result = scope.async {
            listOf(UIMessagePart.Text(buildJsonObject { put("error", code) }.toString()))
        },
    )
}

internal class SshManagedExecutionAdapter(
    private val ledger: ManagedExecutionLedger,
    private val supervisor: SshManagedSupervisor,
    private val profileResolver: SshSavedConnectionResolver,
    private val tokenProvider: ExecutionTokenProvider,
) : ManagedExecutionAdapter {
    override val runtime: ManagedExecutionRuntime = ManagedExecutionRuntime.SSH

    override suspend fun list(
        caller: ManagedExecutionCaller,
        includeStopped: Boolean,
    ): List<ManagedExecutionSnapshot> = ownedRecords(caller).mapNotNull { record ->
        refresh(record).getOrNull()
    }.filter { includeStopped || it.alive }

    override suspend fun status(
        caller: ManagedExecutionCaller,
        executionId: String,
    ): ManagedExecutionResult {
        val record = ownedRecord(caller, executionId)
            ?: return ManagedExecutionResult.Error("execution_not_found")
        return refresh(record).fold(
            onSuccess = ManagedExecutionResult::Snapshot,
            onFailure = { ManagedExecutionResult.Error(it.message ?: "execution_status_failed") },
        )
    }

    override suspend fun logs(
        caller: ManagedExecutionCaller,
        executionId: String,
        tailBytes: Int,
    ): ManagedExecutionResult {
        val record = ownedRecord(caller, executionId)
            ?: return ManagedExecutionResult.Error("execution_not_found")
        val connection = resolveConnection(record).getOrElse {
            return ManagedExecutionResult.Error(it.message ?: "ssh_saved_profile_unavailable")
        }
        val snapshot = refresh(record, connection).getOrElse {
            return ManagedExecutionResult.Error(it.message ?: "execution_status_failed")
        }
        val logs = supervisor.logs(
            connection,
            record.nativeId,
            verifiedToken(record),
            tailBytes.coerceIn(1, MAX_LOG_BYTES),
        ).getOrElse {
            return ManagedExecutionResult.Error(it.message ?: "execution_logs_failed")
        }
        return ManagedExecutionResult.Logs(
            execution = snapshot,
            logs = ManagedExecutionLogs(logs.stdout, logs.stderr, logs.truncated),
        )
    }

    override suspend fun stop(
        caller: ManagedExecutionCaller,
        executionId: String,
        force: Boolean,
    ): ManagedExecutionResult {
        val record = ownedRecord(caller, executionId)
            ?: return ManagedExecutionResult.Error("execution_not_found")
        return stopRecord(record, force)
    }

    override suspend fun emergencyStop(): List<ManagedExecutionSnapshot> = ledger.list()
        .filter { it.runtime == runtime.idPrefix }
        .map { record ->
            when (val result = stopRecord(record, force = true)) {
                is ManagedExecutionResult.Stopped -> result.execution
                is ManagedExecutionResult.Snapshot -> result.execution
                else -> record.toSshSnapshot(
                    ManagedExecutionStatus.UNKNOWN,
                    alive = true,
                    uncertain = true,
                )
            }
        }

    private suspend fun stopRecord(
        record: ManagedExecutionLedgerRecord,
        force: Boolean,
    ): ManagedExecutionResult {
        val connection = resolveConnection(record).getOrElse {
            return uncertain(record)
        }
        val token = runCatching { verifiedToken(record) }.getOrElse {
            return uncertain(record)
        }
        ledger.setManagedStatus(record.executionId, ManagedExecutionStatus.STOP_REQUESTED)
        var status = supervisor.stop(connection, record.nativeId, token, force).getOrElse {
            return uncertain(record)
        }
        if (!force && status.running) {
            for (attempt in 0 until STOP_POLL_ATTEMPTS) {
                delay(STOP_POLL_INTERVAL_MS)
                status = supervisor.status(connection, record.nativeId, token).getOrDefault(status)
                if (!status.running) break
            }
            if (status.running) {
                status = supervisor.stop(connection, record.nativeId, token, force = true)
                    .getOrDefault(status)
            }
        }
        if (!record.matches(status.identity) || status.running || !status.identityVerified) {
            return uncertain(record)
        }
        ledger.setManagedStatus(record.executionId, ManagedExecutionStatus.STOPPED)
        return ManagedExecutionResult.Stopped(
            record.toSshSnapshot(ManagedExecutionStatus.STOPPED, alive = false)
        )
    }

    private suspend fun refresh(record: ManagedExecutionLedgerRecord): Result<ManagedExecutionSnapshot> =
        resolveConnection(record).fold(
            onSuccess = { refresh(record, it) },
            onFailure = { Result.failure(it) },
        )

    private suspend fun refresh(
        record: ManagedExecutionLedgerRecord,
        connection: SshSavedConnection,
    ): Result<ManagedExecutionSnapshot> = runCatching {
        val status = supervisor.status(connection, record.nativeId, verifiedToken(record)).getOrThrow()
        require(record.matches(status.identity)) { "execution_identity_mismatch" }
        val mapped = when (status.state) {
            "starting" -> ManagedExecutionStatus.STARTING
            "running" -> ManagedExecutionStatus.RUNNING
            "stop_requested" -> ManagedExecutionStatus.STOP_REQUESTED
            "exited" -> ManagedExecutionStatus.EXITED
            "stopped" -> ManagedExecutionStatus.STOPPED
            "failed" -> ManagedExecutionStatus.FAILED
            else -> ManagedExecutionStatus.UNKNOWN
        }
        ledger.setManagedStatus(record.executionId, mapped)
        record.toSshSnapshot(
            status = mapped,
            alive = status.running && status.identityVerified,
            uncertain = status.running && !status.identityVerified,
            exitCode = status.exitCode,
        )
    }

    private suspend fun resolveConnection(record: ManagedExecutionLedgerRecord): Result<SshSavedConnection> {
        val profileName = record.profileName
            ?: return Result.failure(IllegalStateException("ssh_saved_profile_reference_missing"))
        return profileResolver.resolve(profileName)
    }

    private suspend fun ownedRecords(caller: ManagedExecutionCaller) = ledger.list().filter { record ->
        record.runtime == runtime.idPrefix && record.isSshOwnedBy(caller)
    }

    private suspend fun ownedRecord(
        caller: ManagedExecutionCaller,
        executionId: String,
    ) = ownedRecords(caller).firstOrNull { it.executionId == executionId }

    private fun verifiedToken(record: ManagedExecutionLedgerRecord): String {
        val token = tokenProvider.tokenFor(record.nativeId)
        require(MessageDigest.isEqual(
            managedSha256(token).toByteArray(),
            record.tokenHash.orEmpty().toByteArray(),
        )) { "execution_token_unavailable" }
        return token
    }

    private fun uncertain(record: ManagedExecutionLedgerRecord) = ManagedExecutionResult.Snapshot(
        record.toSshSnapshot(
            status = ManagedExecutionStatus.STOP_REQUESTED,
            alive = true,
            uncertain = true,
        )
    )

    private companion object {
        const val MAX_LOG_BYTES = 256 * 1024
        const val STOP_POLL_ATTEMPTS = 10
        const val STOP_POLL_INTERVAL_MS = 200L
    }
}

private fun ManagedExecutionLedgerRecord.isSshOwnedBy(caller: ManagedExecutionCaller): Boolean =
    ownerAssistantId == caller.assistantId &&
        ownerConversationId == caller.conversationId &&
        ownerOrigin == caller.origin.name

private fun ManagedExecutionLedgerRecord.matches(identity: SshSupervisorIdentity): Boolean =
    pid == identity.pid && processGroupId == identity.processGroupId &&
        processStartTicks == identity.processStartTicks

private fun ManagedExecutionLedgerRecord.toSshSnapshot(
    status: ManagedExecutionStatus,
    alive: Boolean,
    uncertain: Boolean = false,
    exitCode: Int? = null,
) = ManagedExecutionSnapshot(
    executionId = executionId,
    runtime = ManagedExecutionRuntime.SSH,
    name = "SSH ${profileName ?: nativeId.takeLast(8)}",
    status = status,
    alive = alive,
    startedAtMs = createdAtMs,
    lastExitCode = exitCode,
    terminationUncertain = uncertain,
)

private suspend fun ManagedExecutionLedger.setManagedStatus(
    executionId: String,
    status: ManagedExecutionStatus,
) {
    val current = list().firstOrNull { it.executionId == executionId } ?: return
    upsert(current.copy(status = status.name, updatedAtMs = System.currentTimeMillis()))
}

private fun managedSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
