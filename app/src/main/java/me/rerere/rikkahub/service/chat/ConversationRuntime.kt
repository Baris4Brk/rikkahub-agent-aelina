package me.rerere.rikkahub.service.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.selectUnbiased
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

fun interface RuntimeCommandExecutor {
    suspend fun execute(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ): RunOutcome
}

fun interface RuntimeHydrator {
    suspend fun hydrate()
}

fun interface RuntimeRepairer {
    suspend fun repair(runId: Uuid, reason: ToolCancelReason): InterruptCleanupResult

    suspend fun repair(
        runId: Uuid,
        reason: ToolCancelReason,
        toolCancellationResults: Map<String, CancelRequestResult>,
    ): InterruptCleanupResult = repair(runId, reason)
}

class ConversationRuntime(
    appScope: CoroutineScope,
    val conversationId: Uuid,
    private val dispatchers: DispatcherProvider = DispatcherProvider(),
    private val executor: RuntimeCommandExecutor,
    private val hydrator: RuntimeHydrator = RuntimeHydrator { },
    private val repairer: RuntimeRepairer = RuntimeRepairer { _, _ -> InterruptCleanupResult.Completed },
    private val persistenceCoordinator: PersistenceCoordinator = NoOpPersistenceCoordinator(),
    private val durableQueue: DurableCommandQueue? = null,
    private val onBecameIdle: (Uuid) -> Unit = {},
    private val onRunJobChanged: (Job?) -> Unit = {},
    private val onRunStarted: suspend (CommandEnvelope<out ChatCommand>) -> AutoCloseable? = { null },
    private val onPersistSteering: suspend (me.rerere.rikkahub.data.ai.SteeringNote) -> Unit = {},
    private val onRunFinished: (CommandEnvelope<out ChatCommand>, CommandOutcome) -> Unit = { _, _ -> },
    private val onCancellationTimeout: (CommandEnvelope<out EmergencyCommand>, Throwable) -> Unit = { _, _ -> },
    private val hydrationTimeout: Duration = 30.seconds,
    private val cancellationGracePeriod: Duration = 5.seconds,
) {
    private val parentJob = appScope.coroutineContext[Job]
    private val sessionJob = SupervisorJob(parentJob)
    private val sessionScope = CoroutineScope(
        appScope.coroutineContext + sessionJob + dispatchers.runtime +
            CoroutineName("Conversation-$conversationId")
    )

    private val emergencySlot = AtomicReference<CommandEnvelope<out EmergencyCommand>?>(null)
    private val emergencyWakeup = Channel<Unit>(Channel.CONFLATED)
    private val approvalChannel = Channel<CommandEnvelope<ToolApprovalCommand>>(16, BufferOverflow.SUSPEND)
    private val approvalResumeChannel = Channel<CommandEnvelope<ResumeAfterApprovalCommand>>(8, BufferOverflow.SUSPEND)
    private val steeringChannel = Channel<CommandEnvelope<SteerCommand>>(8, BufferOverflow.SUSPEND)
    private val toolControlChannel = Channel<CommandEnvelope<CancelCurrentToolCommand>>(8, BufferOverflow.SUSPEND)
    private val queueControlChannel = Channel<CommandEnvelope<out NormalCommand>>(8, BufferOverflow.SUSPEND)
    private val approvalWakeup = Channel<Unit>(Channel.CONFLATED)
    private val approvalResumeWakeup = Channel<Unit>(Channel.CONFLATED)
    private val steeringWakeup = Channel<Unit>(Channel.CONFLATED)
    private val toolControlWakeup = Channel<Unit>(Channel.CONFLATED)
    private val queueControlWakeup = Channel<Unit>(Channel.CONFLATED)
    private val normalWakeup = Channel<Unit>(Channel.CONFLATED)
    private val completionWakeup = Channel<Unit>(Channel.CONFLATED)
    private val durableWakeup = Channel<Unit>(Channel.CONFLATED)
    private val cancellationTimeoutChannel = Channel<CancellationTimeout>(Channel.BUFFERED)
    private val cancellationTimeoutWakeup = Channel<Unit>(Channel.CONFLATED)

    private var activeRun: ActiveRun? = null
    private var activeEnvelope: CommandEnvelope<out ChatCommand>? = null
    private val softSteeringTarget = AtomicReference<GenerationRunControl?>(null)
    private var pendingAfterCancel: CommandEnvelope<out EmergencyCommand>? = null
    private var pendingStop: CommandEnvelope<StopCommand>? = null
    private var cancellationWatchdog: Job? = null
    private val pendingNormalIndex = PendingNormalIndex()
    private var queuePaused = false
    private val stateRevision = AtomicLong(0L)
    private val acceptanceLock = Any()
    private val acceptedCommands = ConcurrentHashMap<Uuid, CommandEnvelope<out ChatCommand>>()
    private val acceptedDedupeKeys = ConcurrentHashMap<String, Uuid>()
    private val steeringFallbackCommands = ConcurrentHashMap<Uuid, SendMessageCommand>()
    private val terminalizingCommandIds = ConcurrentHashMap.newKeySet<Uuid>()
    private val finishedOutcomes = ConcurrentHashMap<Uuid, CommandOutcome>()

    private val _runtimeState = MutableStateFlow<RuntimeState>(RuntimeState.Hydrating)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()
    private val _hydrationState = MutableStateFlow(HydrationState.NotHydrated)
    val hydrationState: StateFlow<HydrationState> = _hydrationState.asStateFlow()
    private val _queueStatus = MutableStateFlow(QueueStatus(false, 0, null))
    val queueStatus: StateFlow<QueueStatus> = _queueStatus.asStateFlow()
    private val _queuedMessages = MutableStateFlow<List<QueuedMessageUiEntry>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessageUiEntry>> = _queuedMessages.asStateFlow()
    private val _steeringStatus = MutableStateFlow<Map<Uuid, me.rerere.rikkahub.data.ai.SteeringState>>(emptyMap())
    val steeringStatus: StateFlow<Map<Uuid, me.rerere.rikkahub.data.ai.SteeringState>> = _steeringStatus.asStateFlow()
    private val _steeringEntries = MutableStateFlow<Map<Uuid, SteeringUiEntry>>(emptyMap())
    val steeringEntries: StateFlow<Map<Uuid, SteeringUiEntry>> = _steeringEntries.asStateFlow()

    private val eventLoopJob = sessionScope.launch { eventLoop() }

    init {
        durableQueue?.setWakeUpListener { durableWakeup.trySend(Unit) }
        durableQueue?.let { queue ->
            // Room Flow is the durable source-of-truth wake path. Channels are only hints;
            // if a wake signal is lost, this collector still nudges the runtime to scan.
            sessionScope.launch(dispatchers.io + CoroutineName("Conversation-$conversationId-room-flow")) {
                queue.observe(conversationId).collect {
                    durableWakeup.trySend(Unit)
                }
            }
            // Lease expiry is actively recovered even when no Room write occurs.
            sessionScope.launch(dispatchers.io + CoroutineName("Conversation-$conversationId-lease")) {
                while (currentCoroutineContext().isActive) {
                    delay(15.seconds)
                    runSuspendCatching { queue.recoverExpired() }
                    durableWakeup.trySend(Unit)
                }
            }
        }
        eventLoopJob.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                _runtimeState.value = RuntimeState.Fatal(cause)
                rejectAllAcceptedCommands("Conversation runtime crashed")
                sessionJob.cancel(CancellationException("Conversation event loop crashed", cause))
            }
        }
    }

    /** Work that requires this Runtime instance to stay paired with its ConversationSession. */
    val hasRetainedWork: Boolean
        get() = activeRun != null || pendingNormalIndex.size > 0 || acceptedCommands.isNotEmpty() ||
            hydrationState.value == HydrationState.Hydrating || pendingStop != null || pendingAfterCancel != null ||
            runtimeState.value == RuntimeState.WaitingApproval

    @Deprecated("Runtime lifetime is owned by ConversationSession")
    val isInUse: Boolean get() = hasRetainedWork

    fun close() {
        rejectAllAcceptedCommands("Runtime closed")
        sessionJob.cancel(CancellationException("Runtime closed"))
        activeRun?.control?.requestCancelAllTools(ToolCancelReason.SHUTDOWN)
        activeRun?.job?.cancel(CancellationException("Runtime closed"))
    }

    internal suspend fun enqueueEnvelope(envelope: CommandEnvelope<out ChatCommand>): SubmitResult {
        if (!sessionJob.isActive) return reject(envelope, "Runtime unavailable")
        val regenerate = envelope.command as? RegenerateCommand
        if (regenerate?.policy == RegeneratePolicy.INTERRUPT_CURRENT) {
            return replaceEmergencyEnvelope(
                CommandEnvelope(
                    id = envelope.id,
                    conversationId = envelope.conversationId,
                    command = InterruptRegenerateCommand(regenerate),
                    origin = envelope.origin,
                    createdAt = envelope.createdAt,
                    sequence = envelope.sequence,
                    expiresAt = envelope.expiresAt,
                    dedupeKey = envelope.dedupeKey,
                    dependencies = envelope.dependencies,
                    result = envelope.result,
                )
            )
        }

        // Acceptance is one linearized decision. Without this short critical section,
        // simultaneous retries can each pass the finished/accepted checks and one duplicate
        // can temporarily publish an outcome for the still-running original command.
        var finishedOutcome: CommandOutcome? = null
        var existingEnvelope: CommandEnvelope<out ChatCommand>? = null
        var supersededBy: Uuid? = null
        synchronized(acceptanceLock) {
            finishedOutcome = finishedOutcomes[envelope.id]
            if (finishedOutcome == null) {
                existingEnvelope = acceptedCommands[envelope.id]
            }
            if (finishedOutcome == null && existingEnvelope == null) {
                supersededBy = envelope.dedupeKey?.let(acceptedDedupeKeys::get)
                if (supersededBy == null) {
                    acceptedCommands[envelope.id] = envelope
                    envelope.dedupeKey?.let { acceptedDedupeKeys[it] = envelope.id }
                }
            }
        }
        finishedOutcome?.let { outcome ->
            complete(envelope, outcome, persistDurable = false)
            return SubmitResult.Accepted(envelope.id)
        }
        existingEnvelope?.let { existing ->
            mirrorOutcome(existing, envelope)
            return SubmitResult.Accepted(existing.id)
        }
        supersededBy?.let { previous ->
            complete(
                envelope,
                CommandOutcome.Superseded(previous),
                persistDurable = false,
            )
            return SubmitResult.Accepted(previous)
        }
        if (envelope.command !is EmergencyCommand) {
            when (val durableResult = persistDurable(envelope)) {
                is DurableSubmitResult.InvalidPayload -> {
                    complete(envelope, CommandOutcome.Rejected(durableResult.reason))
                    return SubmitResult.Rejected(durableResult.reason)
                }
                is DurableSubmitResult.AlreadyExists -> {
                    complete(
                        envelope,
                        CommandOutcome.Superseded(durableResult.commandId),
                        persistDurable = false,
                    )
                    durableWakeup.trySend(Unit)
                    return SubmitResult.Accepted(durableResult.commandId)
                }
                is DurableSubmitResult.DedupeHit -> {
                    complete(
                        envelope,
                        CommandOutcome.Superseded(durableResult.commandId),
                        persistDurable = false,
                    )
                    durableWakeup.trySend(Unit)
                    return SubmitResult.Accepted(durableResult.commandId)
                }
                is DurableSubmitResult.Inserted -> Unit
            }
        }
        return when (val command = envelope.command) {
            is EmergencyCommand -> reject(envelope, "EmergencyCommand must use submitEmergency")
            is ResumeAfterApprovalCommand -> tryEnqueue(
                approvalResumeChannel,
                envelope as CommandEnvelope<ResumeAfterApprovalCommand>,
                8,
                approvalResumeWakeup,
            )
            is ToolApprovalCommand -> tryEnqueue(
                approvalChannel,
                envelope as CommandEnvelope<ToolApprovalCommand>,
                16,
                approvalWakeup,
            )
            is SteerCommand -> if (command.applyPolicy == SteeringApplyPolicy.AFTER_CHECKPOINT) {
                registerSoftSteering(envelope as CommandEnvelope<SteerCommand>)
            } else {
                tryEnqueue(
                    steeringChannel,
                    envelope as CommandEnvelope<SteerCommand>,
                    8,
                    steeringWakeup,
                )
            }
            is CancelCurrentToolCommand -> tryEnqueue(
                toolControlChannel,
                envelope as CommandEnvelope<CancelCurrentToolCommand>,
                8,
                toolControlWakeup,
            )
            is ResumeQueueCommand,
            is ClearPendingQueueCommand,
            is CancelQueuedCommand,
            is UpdateQueuedMessageCommand,
            is PromoteQueuedMessageToSteeringCommand ->
                tryEnqueue(
                    queueControlChannel,
                    envelope as CommandEnvelope<out NormalCommand>,
                    8,
                    queueControlWakeup,
                )
            is NormalCommand -> enqueueNormal(envelope as CommandEnvelope<NormalCommand>)
        }
    }

    private suspend fun persistDurable(
        envelope: CommandEnvelope<out ChatCommand>,
    ): DurableSubmitResult {
        val queue = durableQueue ?: return DurableSubmitResult.Inserted(envelope.id)
        val encoded = runCatching { CommandCodec.encodeDurable(envelope.command, envelope.origin) }
            .getOrElse { return DurableSubmitResult.InvalidPayload(it.message ?: "Command encoding failed") }
        val entity = PendingChatCommandEntity(
            id = envelope.id.toString(),
            schemaVersion = 1,
            conversationId = envelope.conversationId.toString(),
            type = encoded.first,
            payloadJson = encoded.second,
            state = DurableCommandState.PENDING.name,
            priority = 0,
            sequence = envelope.sequence,
            expectedTargetVersion = (envelope.command as? RegenerateCommand)?.expectedTargetVersion,
            expectedBranchHeadMessageId = (envelope.command as? RegenerateCommand)?.expectedBranchHeadMessageId?.toString(),
            dedupeKey = envelope.dedupeKey,
            idempotencyKey = envelope.id.toString(),
            attempt = 0,
            claimedBy = null,
            leaseUntil = null,
            createdAt = envelope.createdAt.toEpochMilliseconds(),
            startedAt = null,
            finishedAt = null,
            expiresAt = envelope.expiresAt?.toEpochMilliseconds(),
            lastErrorCode = null,
            lastErrorMessage = null,
        )
        return runSuspendCatching { queue.submitDurable(entity) }
            .getOrElse { DurableSubmitResult.InvalidPayload(it.message ?: "Durable submit failed") }
    }

    private fun publishPendingSteeringEntry(
        envelope: CommandEnvelope<SteerCommand>,
        runId: Uuid,
    ) {
        _steeringEntries.update { entries ->
            entries + (
                envelope.id to SteeringUiEntry(
                    commandId = envelope.id,
                    runId = runId,
                    text = envelope.command.text,
                    state = me.rerere.rikkahub.data.ai.SteeringState.PENDING,
                    historyMode = envelope.command.historyMode,
                    editable = true,
                )
            )
        }
    }

    private suspend fun registerSoftSteering(
        envelope: CommandEnvelope<SteerCommand>,
        fallbackCommand: SendMessageCommand? = null,
    ): SubmitResult {
        val control = softSteeringTarget.get()
            ?: return fallbackSteeringToQueue(envelope, null, fallbackCommand)
        publishPendingSteeringEntry(envelope, control.runId)
        val registration = control.submitSteering(
            me.rerere.rikkahub.data.ai.SteeringNote(
                commandId = envelope.id,
                runId = control.runId,
                text = envelope.command.text,
                source = envelope.origin,
                scope = envelope.command.scope,
                historyMode = envelope.command.historyMode,
            )
        )
        return when (registration) {
            me.rerere.rikkahub.data.ai.SteeringRegistrationResult.Accepted ->
                SubmitResult.Accepted(envelope.id)
            me.rerere.rikkahub.data.ai.SteeringRegistrationResult.RunClosed ->
                fallbackSteeringToQueue(envelope, control, fallbackCommand)
            is me.rerere.rikkahub.data.ai.SteeringRegistrationResult.Rejected -> {
                complete(envelope, CommandOutcome.NotApplied(registration.reason))
                SubmitResult.Rejected(registration.reason)
            }
        }
    }

    private suspend fun fallbackSteeringToQueue(
        envelope: CommandEnvelope<SteerCommand>,
        control: GenerationRunControl?,
        fallbackCommand: SendMessageCommand? = null,
    ): SubmitResult {
        val replacementCommand = fallbackCommand
            ?: steeringFallbackCommands.remove(envelope.id)
            ?: SendMessageCommand(
                RawUserContent(listOf(me.rerere.ai.ui.UIMessagePart.Text(envelope.command.text)))
            )
        steeringFallbackCommands.remove(envelope.id)
        val rewriteFailure = runSuspendCatching {
            durableQueue?.rewritePendingCommand(envelope.id, replacementCommand, envelope.origin) ?: true
        }.fold(
            onSuccess = { rewritten ->
                if (rewritten) null else IllegalStateException(
                    "The saved guidance was no longer available for queue conversion"
                )
            },
            onFailure = { it },
        )
        if (rewriteFailure != null) {
            val reason = "这条补充暂时没能放到下一条，请稍后在恢复项中确认"
            // Preserve the original steer payload for explicit recovery. A failed rewrite
            // must never crash the event loop or be finalized as an unrecoverable FAILED row.
            durableQueue?.let { queue ->
                runSuspendCatching {
                    queue.resolvePending(
                        id = envelope.id.toString(),
                        state = DurableCommandState.MANUAL_CONFIRMATION,
                        errorCode = "STEERING_FALLBACK_REWRITE_FAILED",
                        errorMessage = rewriteFailure.message ?: reason,
                    )
                }
            }
            _steeringEntries.update { entries ->
                val current = entries[envelope.id] ?: return@update entries
                entries + (
                    envelope.id to current.copy(
                        state = me.rerere.rikkahub.data.ai.SteeringState.REJECTED_NOT_STEERABLE,
                        editable = false,
                    )
                )
            }
            complete(
                envelope,
                CommandOutcome.Failed(IllegalStateException(reason, rewriteFailure)),
                persistDurable = false,
            )
            return SubmitResult.Rejected(reason)
        }
        val replacement: CommandEnvelope<NormalCommand> = CommandEnvelope(
            id = envelope.id,
            conversationId = envelope.conversationId,
            command = replacementCommand,
            origin = envelope.origin,
            createdAt = envelope.createdAt,
            sequence = envelope.sequence,
            expiresAt = envelope.expiresAt,
            dedupeKey = envelope.dedupeKey,
            dependencies = envelope.dependencies,
            result = envelope.result,
        )
        acceptedCommands.replace(envelope.id, envelope, replacement)
        control?.markFallbackQueued(envelope.id)
        _steeringStatus.update { states ->
            states + (envelope.id to me.rerere.rikkahub.data.ai.SteeringState.FALLBACK_QUEUED)
        }
        _steeringEntries.update { entries ->
            val fallbackEntry = entries[envelope.id]?.copy(
                state = me.rerere.rikkahub.data.ai.SteeringState.FALLBACK_QUEUED,
                editable = false,
            ) ?: SteeringUiEntry(
                commandId = envelope.id,
                // When registration races a finished run there is no target run left to
                // display. Reuse the command ID as a stable UI-only correlation value.
                runId = control?.runId ?: envelope.id,
                text = envelope.command.text,
                state = me.rerere.rikkahub.data.ai.SteeringState.FALLBACK_QUEUED,
                historyMode = envelope.command.historyMode,
                editable = false,
            )
            entries + (envelope.id to fallbackEntry)
        }
        return enqueueNormal(replacement)
    }

    private suspend fun enqueueNormal(envelope: CommandEnvelope<NormalCommand>): SubmitResult {
        if (!pendingNormalIndex.add(envelope, limit = 32)) {
            complete(envelope, CommandOutcome.Rejected("Queue is full"))
            return SubmitResult.QueueFull(32)
        }
        normalWakeup.trySend(Unit)
        refreshQueueStatus()
        return SubmitResult.Accepted(envelope.id)
    }

    private fun <T> tryEnqueue(
        channel: Channel<T>,
        envelope: T,
        limit: Int,
        wakeup: Channel<Unit>,
    ): SubmitResult {
        val result = channel.trySend(envelope)
        if (result.isSuccess) {
            wakeup.trySend(Unit)
            return SubmitResult.Accepted((envelope as? CommandEnvelope<*>)?.id ?: Uuid.random())
        }
        val commandEnvelope = envelope as? CommandEnvelope<out ChatCommand>
        commandEnvelope?.let {
            complete(it, CommandOutcome.Rejected("Queue is full"))
        }
        return SubmitResult.QueueFull(limit)
    }

    internal fun replaceEmergencyEnvelope(envelope: CommandEnvelope<out EmergencyCommand>): SubmitResult {
        if (!sessionJob.isActive) return reject(envelope, "Runtime unavailable")
        var finishedOutcome: CommandOutcome? = null
        var existingEnvelope: CommandEnvelope<out ChatCommand>? = null
        synchronized(acceptanceLock) {
            finishedOutcome = finishedOutcomes[envelope.id]
            if (finishedOutcome == null) {
                existingEnvelope = acceptedCommands[envelope.id]
            }
            if (finishedOutcome == null && existingEnvelope == null) {
                acceptedCommands[envelope.id] = envelope
            }
        }
        finishedOutcome?.let { outcome ->
            complete(envelope, outcome, persistDurable = false)
            return SubmitResult.Accepted(envelope.id)
        }
        existingEnvelope?.let { existing ->
            mirrorOutcome(existing, envelope)
            return SubmitResult.Accepted(existing.id)
        }
        emergencySlot.getAndSet(envelope)?.let { old ->
            complete(old, CommandOutcome.Superseded(envelope.id))
        }
        emergencyWakeup.trySend(Unit)
        return SubmitResult.Accepted(envelope.id)
    }

    private fun mirrorOutcome(
        source: CommandEnvelope<out ChatCommand>,
        duplicate: CommandEnvelope<out ChatCommand>,
    ) {
        sessionScope.launch {
            complete(
                duplicate,
                source.result.await(),
                persistDurable = false,
            )
        }
    }

    private fun reject(envelope: CommandEnvelope<out ChatCommand>, reason: String): SubmitResult {
        complete(envelope, CommandOutcome.Rejected(reason))
        return SubmitResult.RuntimeUnavailable(reason)
    }

    private fun complete(
        envelope: CommandEnvelope<out ChatCommand>,
        outcome: CommandOutcome,
        persistDurable: Boolean = true,
    ) {
        // Command outcomes are exactly-once. A shutdown/clear race may observe
        // the same envelope through both acceptedCommands and a channel drain.
        val durableResolver = durableQueue.takeIf {
            persistDurable && envelope.command !is EmergencyCommand
        }
        val ownsTerminalizationMarker = durableResolver != null && terminalizingCommandIds.add(envelope.id)
        if (!envelope.result.complete(outcome)) {
            if (ownsTerminalizationMarker) terminalizingCommandIds.remove(envelope.id)
            return
        }
        if (envelope.command is SteerCommand) {
            steeringFallbackCommands.remove(envelope.id)
        }

        // A duplicate envelope intentionally shares the original command id but owns a
        // different Deferred. Completing that mirror must not evict the original command,
        // its queue position, dedupe reservation, or publish a premature global outcome.
        synchronized(acceptanceLock) {
            val acceptedOwner = acceptedCommands[envelope.id]
            if (acceptedOwner === envelope) {
                acceptedCommands.remove(envelope.id, envelope)
                envelope.dedupeKey?.let { acceptedDedupeKeys.remove(it, envelope.id) }
                finishedOutcomes[envelope.id] = outcome
            } else if (acceptedOwner == null) {
                // Standalone rejected/superseded envelopes still need idempotent replay.
                // putIfAbsent preserves the authoritative outcome of an already-finished owner.
                finishedOutcomes.putIfAbsent(envelope.id, outcome)
            }
        }
        // Control commands are durable too. Complete their Room row asynchronously so
        // the event loop never performs suspend/IO work while resolving a Deferred.
        durableResolver?.let { queue ->
            sessionScope.launch(dispatchers.io) {
                val resolved = runSuspendCatching {
                    queue.resolvePending(
                        id = envelope.id.toString(),
                        state = outcome.toDurableState(),
                        errorCode = (outcome as? CommandOutcome.Failed)?.error?.javaClass?.simpleName,
                        errorMessage = (outcome as? CommandOutcome.Failed)?.error?.message,
                    )
                }.getOrDefault(false)
                // Keep the marker when Room could not confirm the terminal state. That row
                // remains recoverable after restart, but this live Runtime must never replay it.
                if (resolved) terminalizingCommandIds.remove(envelope.id)
                durableWakeup.trySend(Unit)
            }
        }
        if (finishedOutcomes.size > 256) {
            finishedOutcomes.keys.firstOrNull()?.let { finishedOutcomes.remove(it) }
        }
        refreshQueueStatus()
    }

    private fun dependencyOutcome(envelope: CommandEnvelope<out ChatCommand>): CommandOutcome? {
        if (envelope.expiresAt?.let { it <= Clock.System.now() } == true) {
            return CommandOutcome.Rejected("Command expired")
        }
        for (dependency in envelope.dependencies) {
            val outcome = finishedOutcomes[dependency.commandId] ?: return null
            val accepted = when (dependency.requiredOutcome) {
                RequiredOutcome.COMPLETED -> outcome == CommandOutcome.Completed
                RequiredOutcome.NOT_FAILED -> outcome !is CommandOutcome.Failed &&
                    outcome !is CommandOutcome.Rejected && outcome !is CommandOutcome.Conflict
            }
            if (!accepted) return CommandOutcome.SkippedDependencyFailed(dependency.commandId)
        }
        return CommandOutcome.Completed
    }

    private fun refreshQueueStatus() {
        val snapshot = pendingNormalIndex.snapshot()
        _queueStatus.value = QueueStatus(
            paused = queuePaused,
            pendingCount = snapshot.size,
            activeCommandId = activeRun?.commandId,
            pendingCommandIds = snapshot.map { it.id },
        )
        _queuedMessages.value = pendingNormalIndex.uiSnapshot()
    }

    private suspend fun restoreDurableCommands() {
        val queue = durableQueue ?: return
        queue.recoverExpired()
        val rows = queue.scanPending().filter { it.conversationId == conversationId.toString() }
        rows.sortedWith(compareByDescending<PendingChatCommandEntity> { it.priority }.thenBy { it.sequence })
            .forEach { row ->
                val commandId = Uuid.parse(row.id)
                if (acceptedCommands.containsKey(commandId) || commandId in terminalizingCommandIds) {
                    return@forEach
                }
                val envelope = queue.decodeEnvelope(row) ?: run {
                    queue.resolvePending(
                        row.id,
                        DurableCommandState.MANUAL_CONFIRMATION,
                        errorCode = "PAYLOAD_DECODE_FAILED",
                        errorMessage = "Persisted command payload could not be decoded",
                    )
                    return@forEach
                }
                // A WAITING_APPROVAL row represents a suspended generation, not a new
                // message. Keep it durable for the approval UI; never replay the model.
                if (row.state == DurableCommandState.WAITING_APPROVAL.name) return@forEach
                acceptedCommands[envelope.id] = envelope
                when (val command = envelope.command) {
                    is ResumeQueueCommand,
                    is ClearPendingQueueCommand,
                    is CancelQueuedCommand,
                    is UpdateQueuedMessageCommand,
                    is PromoteQueuedMessageToSteeringCommand ->
                        tryEnqueue(
                            queueControlChannel,
                            envelope as CommandEnvelope<out NormalCommand>,
                            8,
                            queueControlWakeup,
                        )
                    is NormalCommand -> {
                        if (pendingNormalIndex.add(
                                envelope as CommandEnvelope<NormalCommand>,
                                limit = 32,
                            )
                        ) {
                            normalWakeup.trySend(Unit)
                        } else {
                            complete(envelope, CommandOutcome.Rejected("Durable queue is full"))
                        }
                    }
                    is ToolApprovalCommand -> tryEnqueue(
                        approvalChannel,
                        envelope as CommandEnvelope<ToolApprovalCommand>,
                        16,
                        approvalWakeup,
                    )
                    is SteerCommand -> {
                        @Suppress("UNCHECKED_CAST")
                        val steeringEnvelope = envelope as CommandEnvelope<SteerCommand>
                        if (command.applyPolicy == SteeringApplyPolicy.AFTER_CHECKPOINT) {
                            registerSoftSteering(steeringEnvelope)
                        } else {
                            tryEnqueue(steeringChannel, steeringEnvelope, 8, steeringWakeup)
                        }
                    }
                    is CancelCurrentToolCommand -> tryEnqueue(
                        toolControlChannel,
                        envelope as CommandEnvelope<CancelCurrentToolCommand>,
                        8,
                        toolControlWakeup,
                    )
                    ResumeAfterApprovalCommand -> tryEnqueue(
                        approvalResumeChannel,
                        envelope as CommandEnvelope<ResumeAfterApprovalCommand>,
                        8,
                        approvalResumeWakeup,
                    )
                    else -> complete(envelope, CommandOutcome.Rejected("Unsupported durable command type"))
                }
            }
        refreshQueueStatus()
    }

    private suspend fun eventLoop() {
        _hydrationState.value = HydrationState.Hydrating
        _runtimeState.value = RuntimeState.Hydrating
        val hydrated = withTimeoutOrNull(hydrationTimeout) { runSuspendCatching { hydrator.hydrate() }.getOrNull() } != null
        if (!hydrated) {
            _hydrationState.value = HydrationState.Failed
            _runtimeState.value = RuntimeState.HydrationFailed("Conversation hydration failed or timed out")
            rejectAllAcceptedCommands("Conversation hydration failed")
            return
        }
        _hydrationState.value = HydrationState.Hydrated
        restoreDurableCommands()
        _runtimeState.value = RuntimeState.Idle
        var controlBurst = 0
        while (currentCoroutineContext().isActive) {
            activeRun?.takeIf { it.job.isCompleted }?.let { handleRunFinished(it) }
            if (handleEmergency()) { controlBurst = 0; continue }
            if (handleCancellationTimeout()) { controlBurst = 0; continue }
            if (handleQueueControl()) { controlBurst = 0; continue }
            if (activeRun == null && handleApprovalResume()) { controlBurst++; if (controlBurst < 8) continue }
            if (handleApproval()) { controlBurst++; if (controlBurst < 8) continue }
            if (handleToolControl()) { controlBurst++; if (controlBurst < 4) continue }
            if (handleSteering()) { controlBurst++; if (controlBurst < 4) continue }
            if (controlBurst >= 8) {
                controlBurst = 0
                if (startPendingIfReady()) continue
            }
            if (startPendingIfReady()) continue
            selectUnbiased<Unit> {
                emergencyWakeup.onReceive { }
                approvalResumeWakeup.onReceive { }
                approvalWakeup.onReceive { }
                steeringWakeup.onReceive { }
                toolControlWakeup.onReceive { }
                queueControlWakeup.onReceive { }
                normalWakeup.onReceive { }
                completionWakeup.onReceive { }
                durableWakeup.onReceive { restoreDurableCommands() }
                cancellationTimeoutWakeup.onReceive { }
            }
        }
    }

    private fun handleEmergency(): Boolean {
        val envelope = emergencySlot.getAndSet(null) ?: return false
        when (val command = envelope.command) {
            is StopCommand -> {
                cancellationWatchdog?.cancel()
                cancellationWatchdog = null
                pendingAfterCancel?.let { complete(it, CommandOutcome.Superseded(envelope.id)) }
                pendingAfterCancel = null
                pendingStop?.let { complete(it, CommandOutcome.Superseded(envelope.id)) }
                pendingStop = envelope as CommandEnvelope<StopCommand>
                queuePaused = command.pauseQueue
                val run = activeRun
                if (run == null) {
                    val stop = pendingStop
                    pendingStop = null
                    stop?.let { complete(it, CommandOutcome.Completed) }
                    _runtimeState.value = if (queuePaused) RuntimeState.Paused else RuntimeState.Idle
                } else {
                    _runtimeState.value = RuntimeState.Cancelling(run.id)
                    run.control.markStoppedBy(envelope.id)
                    run.control.requestCancelAllTools(ToolCancelReason.USER_STOPPED)
                    run.control.requestProviderCancel(ToolCancelReason.USER_STOPPED)
                    run.job.cancel(CancellationException("Stopped by ${envelope.id}"))
                    scheduleCancellationTimeout(run, envelope)
                }
            }
            is InterruptCommand,
            is InterruptRegenerateCommand -> {
                pendingStop?.let { complete(it, CommandOutcome.Superseded(envelope.id)) }
                pendingStop = null
                queuePaused = false
                val run = activeRun
                if (run == null) startRun(envelope)
                else {
                    pendingAfterCancel?.let { complete(it, CommandOutcome.Superseded(envelope.id)) }
                    pendingAfterCancel = envelope
                    _runtimeState.value = RuntimeState.Cancelling(run.id)
                    run.control.markInterruptedBy(envelope.id)
                    run.control.requestCancelAllTools(ToolCancelReason.USER_INTERRUPTED)
                    run.control.requestProviderCancel(ToolCancelReason.USER_INTERRUPTED)
                    run.job.cancel(CancellationException("Interrupted by ${envelope.id}"))
                    scheduleCancellationTimeout(run, envelope)
                }
            }
        }
        refreshQueueStatus()
        return true
    }

    private fun scheduleCancellationTimeout(
        run: ActiveRun,
        envelope: CommandEnvelope<out EmergencyCommand>,
    ) {
        cancellationWatchdog?.cancel()
        cancellationWatchdog = sessionScope.launch {
            delay(cancellationGracePeriod)
            cancellationTimeoutChannel.send(
                CancellationTimeout(runId = run.id, commandId = envelope.id)
            )
            cancellationTimeoutWakeup.trySend(Unit)
        }
    }

    private suspend fun handleCancellationTimeout(): Boolean {
        val timeout = cancellationTimeoutChannel.tryReceive().getOrNull()
        timeout ?: return false
        val run = activeRun ?: return true
        val pending = pendingAfterCancel?.takeIf { it.id == timeout.commandId }
            ?: pendingStop?.takeIf { it.id == timeout.commandId }
            ?: return true
        if (run.id != timeout.runId || pending.id != timeout.commandId || run.job.isCompleted) {
            return true
        }

        cancellationWatchdog = null
        if (!run.control.hasToolExecutionInFlight()) {
            // A provider may ignore cancellation indefinitely. Fence all later writes from
            // that run before releasing its runtime slot, then reuse the normal cleanup and
            // persistence barrier to start the requested replacement.
            run.control.fenceUpdates()
            handleRunFinished(run)
            return true
        }

        if (pendingAfterCancel?.id == pending.id) pendingAfterCancel = null
        if (pendingStop?.id == pending.id) pendingStop = null
        val error = IllegalStateException(
            "The current response did not stop within $cancellationGracePeriod; " +
                "the requested action was not started because a tool may still be running."
        )
        complete(pending, CommandOutcome.Failed(error))
        onCancellationTimeout(pending, error)
        refreshQueueStatus()
        return true
    }

    private fun handleApprovalResume(): Boolean {
        val envelope = approvalResumeChannel.tryReceive().getOrNull() ?: return false
        if (activeRun == null) {
            startRun(envelope)
        } else {
            // A resume command is only valid after the approval run has finished.
            // Keep it ahead of ordinary FIFO work, but never create a second run.
            approvalResumeChannel.trySend(envelope)
            return false
        }
        return true
    }

    private fun handleApproval(): Boolean {
        val envelope = approvalChannel.tryReceive().getOrNull() ?: return false
        if (activeRun == null) {
            startRun(envelope)
        } else {
            complete(envelope, CommandOutcome.Rejected("Approval arrived while a run is active"))
        }
        return true
    }

    private suspend fun handleToolControl(): Boolean {
        val envelope = toolControlChannel.tryReceive().getOrNull() ?: return false
        val run = activeRun
        if (run == null) {
            complete(envelope, CommandOutcome.NotApplied("No active tool run"))
            return true
        }
        val reason = ToolCancelReason("User cancelled tool ${envelope.command.toolCallId}")
        val request = run.control.requestCancelTool(envelope.command.toolCallId, reason)
        if (request is CancelRequestResult.NotFound) {
            complete(envelope, CommandOutcome.Rejected("Tool call not found"))
            return true
        }
        val termination = run.control.awaitToolTermination(
            envelope.command.toolCallId,
            gracePeriod = 2.seconds,
        )
        val outcome = when (termination) {
            me.rerere.rikkahub.data.ai.tools.ToolTerminationState.StoppedConfirmed -> CommandOutcome.Completed
            me.rerere.rikkahub.data.ai.tools.ToolTerminationState.StillRunning,
            me.rerere.rikkahub.data.ai.tools.ToolTerminationState.Unknown ->
                CommandOutcome.NotApplied("Tool termination state is unknown")
            me.rerere.rikkahub.data.ai.tools.ToolTerminationState.CancelRequested,
            me.rerere.rikkahub.data.ai.tools.ToolTerminationState.Unsupported ->
                CommandOutcome.NotApplied("Tool cancellation is not confirmed")
        }
        complete(envelope, outcome)
        return true
    }

    private suspend fun handleSteering(): Boolean {
        val envelope = steeringChannel.tryReceive().getOrNull() ?: return false
        val run = activeRun
        if (run == null) {
            complete(envelope, CommandOutcome.NotApplied("Run finished before the next model checkpoint"))
            return true
        }
        if (envelope.command.applyPolicy == SteeringApplyPolicy.CANCEL_CURRENT_TOOL) {
            // Hard steering is a bounded tool cancellation barrier. It deliberately does
            // not cancel the provider stream: once every active tool is confirmed stopped,
            // the guidance is queued for the next model checkpoint in the same ActiveRun.
            val toolIds = run.control.activeToolCallIds()
            if (toolIds.isNotEmpty()) {
                run.control.requestCancelAllTools(ToolCancelReason.STEERING_OVERRIDE)
                val terminations = run.control.awaitToolTermination(2.seconds)
                val unconfirmed = terminations.filterValues {
                    it != me.rerere.rikkahub.data.ai.tools.ToolTerminationState.StoppedConfirmed
                }
                if (unconfirmed.isNotEmpty()) {
                    complete(
                        envelope,
                        CommandOutcome.NotApplied(
                            "Hard steering waited for tool termination but state remained unknown: ${unconfirmed.keys.joinToString()}",
                        ),
                    )
                    _steeringStatus.value = run.control.steeringStates()
                    return true
                }
            }
            // Cancel only the current provider child when one is active. STOP and INTERRUPT
            // still cancel the whole ActiveRun through their emergency path.
            val providerCancel = run.control.requestProviderCancel(ToolCancelReason.STEERING_OVERRIDE)
            if (providerCancel is me.rerere.rikkahub.data.ai.tools.CancelRequestResult.Failed) {
                complete(envelope, CommandOutcome.NotApplied("Provider cancellation failed: ${providerCancel.reason}"))
                return true
            }
            // Repair partial assistant/tool state before exposing the hard steering note.
            val repairFailure = runSuspendCatching {
                repairer.repair(
                    run.id,
                    ToolCancelReason.STEERING_OVERRIDE,
                    run.control.toolCancellationResults(),
                )
            }.getOrNull()?.let { it as? InterruptCleanupResult.PartialFailure }
            if (repairFailure != null) {
                complete(envelope, CommandOutcome.NotApplied("Hard steering repair failed: ${repairFailure.reason}"))
                return true
            }
            val barrier = runSuspendCatching { persistenceCoordinator.flushThrough(stateRevision.get()) }
                .getOrElse { PersistResult.Failed(it) }
            if (barrier is PersistResult.Failed) {
                complete(envelope, CommandOutcome.NotApplied("Hard steering persistence barrier failed"))
                return true
            }
        }
        publishPendingSteeringEntry(envelope, run.id)
        val registration = run.control.submitSteering(
            me.rerere.rikkahub.data.ai.SteeringNote(
                commandId = envelope.id,
                runId = run.id,
                text = envelope.command.text,
                source = envelope.origin,
                scope = envelope.command.scope,
                historyMode = envelope.command.historyMode,
            )
        )
        // The executor marks a later model checkpoint; until then this command remains observable.
        when (registration) {
            me.rerere.rikkahub.data.ai.SteeringRegistrationResult.Accepted -> Unit
            me.rerere.rikkahub.data.ai.SteeringRegistrationResult.RunClosed -> {
                _steeringEntries.update { entries ->
                    val current = entries[envelope.id] ?: return@update entries
                    entries + (
                        envelope.id to current.copy(
                            state = me.rerere.rikkahub.data.ai.SteeringState.NOT_APPLIED_RUN_FINISHED,
                            editable = false,
                        )
                    )
                }
                complete(envelope, CommandOutcome.NotApplied("Run finished before steering could be registered"))
            }
            is me.rerere.rikkahub.data.ai.SteeringRegistrationResult.Rejected ->
                complete(envelope, CommandOutcome.NotApplied(registration.reason))
        }
        _steeringStatus.value = run.control.steeringStates()
        return true
    }

    /** Queue controls are applied by the event loop even while a model run is active. */
    private suspend fun handleQueueControl(): Boolean {
        val envelope = queueControlChannel.tryReceive().getOrNull() ?: return false
        when (val command = envelope.command) {
            is ResumeQueueCommand -> {
                queuePaused = false
                complete(envelope, CommandOutcome.Completed)
                startPendingIfReady()
            }
            is ClearPendingQueueCommand -> {
                clearPendingQueue(CommandOutcome.Cancelled)
                complete(envelope, CommandOutcome.Completed)
            }
            is CancelQueuedCommand -> {
                if (cancelQueuedCommand(command.targetCommandId)) {
                    complete(envelope, CommandOutcome.Completed)
                } else {
                    complete(envelope, CommandOutcome.Rejected("Queued command not found"))
                }
            }
            is UpdateQueuedMessageCommand -> {
                val failure = updateQueuedMessage(command)
                complete(
                    envelope,
                    failure?.let(CommandOutcome::Rejected) ?: CommandOutcome.Completed,
                )
            }
            is PromoteQueuedMessageToSteeringCommand -> {
                when (val result = promoteQueuedMessageToSteering(command)) {
                    is SubmitResult.Accepted -> complete(envelope, CommandOutcome.Completed)
                    is SubmitResult.QueueFull -> complete(
                        envelope,
                        CommandOutcome.Rejected("Queue is full (${result.limit})"),
                    )
                    is SubmitResult.Rejected -> complete(envelope, CommandOutcome.Rejected(result.reason))
                    is SubmitResult.RuntimeUnavailable -> complete(
                        envelope,
                        CommandOutcome.Rejected(result.reason),
                    )
                }
            }
            else -> complete(envelope, CommandOutcome.Rejected("Unsupported queue control"))
        }
        _runtimeState.value = when {
            activeRun != null -> RuntimeState.Running
            queuePaused -> RuntimeState.Paused
            else -> RuntimeState.Idle
        }
        refreshQueueStatus()
        return true
    }

    private fun clearPendingQueue(outcome: CommandOutcome) {
        pendingNormalIndex.clear().forEach { complete(it, outcome) }
    }

    private fun cancelQueuedCommand(targetId: Uuid): Boolean {
        val removed = pendingNormalIndex.remove(targetId) ?: return false
        complete(removed, CommandOutcome.Cancelled)
        return true
    }

    private suspend fun updateQueuedMessage(command: UpdateQueuedMessageCommand): String? {
        val current = pendingNormalIndex.get(command.targetCommandId)
            ?: return "Queued command not found"
        val sendCommand = current.command as? SendMessageCommand
            ?: return "Only queued messages can be edited"
        val updatedCommand = sendCommand.copy(content = command.content)
        val rewritten = runSuspendCatching {
            durableQueue?.rewritePendingCommand(current.id, updatedCommand, current.origin) ?: true
        }.getOrElse { return it.message ?: "Queued message update failed" }
        if (!rewritten) return "Queued message is no longer pending"

        val updated = CommandEnvelope(
            id = current.id,
            conversationId = current.conversationId,
            command = updatedCommand,
            origin = current.origin,
            createdAt = current.createdAt,
            sequence = current.sequence,
            expiresAt = current.expiresAt,
            dedupeKey = current.dedupeKey,
            dependencies = current.dependencies,
            result = current.result,
        )
        if (!pendingNormalIndex.replace(current.id, updated)) {
            return "Queued message changed before the edit was applied"
        }
        if (!acceptedCommands.replace(current.id, current, updated)) {
            pendingNormalIndex.replace(current.id, current)
            runSuspendCatching {
                durableQueue?.rewritePendingCommand(current.id, sendCommand, current.origin)
            }
            return "Queued message is no longer available"
        }
        refreshQueueStatus()
        return null
    }

    private suspend fun promoteQueuedMessageToSteering(
        command: PromoteQueuedMessageToSteeringCommand,
    ): SubmitResult {
        val current = pendingNormalIndex.get(command.targetCommandId)
            ?: return SubmitResult.Rejected("Queued command not found")
        val sendCommand = current.command as? SendMessageCommand
            ?: return SubmitResult.Rejected("Only queued messages can become guidance")
        val text = sendCommand.content.parts
            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (text.isEmpty()) {
            return SubmitResult.Rejected("只有附件的消息不能转成引导，请继续排队或选择打断")
        }

        val steerCommand = SteerCommand(
            text = text,
            scope = command.scope,
            historyMode = command.historyMode,
        )
        val rewritten = runSuspendCatching {
            durableQueue?.rewritePendingCommand(current.id, steerCommand, current.origin) ?: true
        }.getOrElse { return SubmitResult.Rejected(it.message ?: "Guidance promotion failed") }
        if (!rewritten) return SubmitResult.Rejected("Queued message is no longer pending")

        val promoted = CommandEnvelope(
            id = current.id,
            conversationId = current.conversationId,
            command = steerCommand,
            origin = current.origin,
            createdAt = current.createdAt,
            sequence = current.sequence,
            expiresAt = current.expiresAt,
            dedupeKey = current.dedupeKey,
            dependencies = current.dependencies,
            result = current.result,
        )
        if (!acceptedCommands.replace(current.id, current, promoted)) {
            runSuspendCatching {
                durableQueue?.rewritePendingCommand(current.id, sendCommand, current.origin)
            }
            return SubmitResult.Rejected("Queued message is no longer available")
        }
        if (pendingNormalIndex.remove(current.id) == null) {
            acceptedCommands.replace(current.id, promoted, current)
            runSuspendCatching {
                durableQueue?.rewritePendingCommand(current.id, sendCommand, current.origin)
            }
            return SubmitResult.Rejected("Queued message changed before promotion")
        }
        steeringFallbackCommands[current.id] = sendCommand
        refreshQueueStatus()
        return registerSoftSteering(promoted, sendCommand)
    }

    private fun startPendingIfReady(): Boolean {
        if (activeRun != null || queuePaused) return false
        val envelope = pendingNormalIndex.peek() ?: return false
        return when (val dependency = dependencyOutcome(envelope)) {
            null -> false
            CommandOutcome.Completed -> {
                val removed = pendingNormalIndex.removeFirst(envelope.id) ?: return false
                startRun(removed)
                refreshQueueStatus()
                true
            }
            else -> {
                val removed = pendingNormalIndex.removeFirst(envelope.id) ?: return false
                complete(removed, dependency)
                true
            }
        }
    }

    fun updateSteeringHistoryMode(
        commandId: Uuid,
        historyMode: SteeringHistoryMode,
    ): Boolean {
        val control = softSteeringTarget.get() ?: return false
        val updated = control.updateSteeringHistoryMode(commandId, historyMode)
        if (updated) {
            _steeringEntries.update { entries ->
                val current = entries[commandId] ?: return@update entries
                val latestMode = control.steeringNotes()[commandId]?.historyMode ?: historyMode
                entries + (commandId to current.copy(historyMode = latestMode))
            }
        }
        return updated
    }

    private suspend fun claimDurable(envelope: CommandEnvelope<out ChatCommand>) {
        // Emergency commands never go through persistDurable, so there is no durable row
        // to claim. Claiming them fails with "already claimed or unavailable" and aborts
        // the run before the executor runs (e.g. interrupt regenerate looks like a no-op).
        if (envelope.command is EmergencyCommand) return
        val queue = durableQueue ?: return
        val claimed = queue.claim(envelope.id.toString())
        check(claimed) { "Durable command ${envelope.id} is already claimed or unavailable" }
    }

    private suspend fun finishDurable(envelope: CommandEnvelope<out ChatCommand>, outcome: CommandOutcome) {
        val queue = durableQueue ?: return
        val state = when (outcome) {
            CommandOutcome.Completed -> DurableCommandState.COMPLETED
            CommandOutcome.Cancelled -> DurableCommandState.CANCELLED
            is CommandOutcome.Rejected,
            is CommandOutcome.Conflict,
            is CommandOutcome.Failed,
            is CommandOutcome.SkippedDependencyFailed -> DurableCommandState.FAILED
            is CommandOutcome.Superseded -> DurableCommandState.CANCELLED
            is CommandOutcome.NotApplied -> DurableCommandState.COMPLETED
        }
        queue.complete(envelope.id.toString(), state)
    }

    private fun startRun(envelope: CommandEnvelope<out ChatCommand>) {
        // A guidance item that missed its run is rewritten into this normal FIFO command.
        // Once that command starts, the purple fallback card has served its purpose and must
        // leave the temporary input-area UI.
        _steeringEntries.update { entries ->
            if (entries[envelope.id]?.state == me.rerere.rikkahub.data.ai.SteeringState.FALLBACK_QUEUED) {
                entries - envelope.id
            } else {
                entries
            }
        }
        _steeringStatus.update { states ->
            if (states[envelope.id] == me.rerere.rikkahub.data.ai.SteeringState.FALLBACK_QUEUED) {
                states - envelope.id
            } else {
                states
            }
        }
        lateinit var control: GenerationRunControl
        control = GenerationRunControl(Uuid.random()) { transition ->
            val latestStates = control.steeringStates()
            val latestState = latestStates[transition.commandId] ?: transition.state
            val latestHistoryMode = control.steeringNotes()[transition.commandId]?.historyMode
                ?: transition.historyMode
            _steeringStatus.value = latestStates
            _steeringEntries.update { entries ->
                val current = entries[transition.commandId] ?: return@update entries
                // Transition callbacks run outside GenerationRunControl's lock. A later
                // APPLIED callback may therefore win before an earlier PENDING callback
                // returns. Never let the UI state move backwards in that race.
                val mergedState = when {
                    latestState == me.rerere.rikkahub.data.ai.SteeringState.FALLBACK_QUEUED -> latestState
                    current.state == me.rerere.rikkahub.data.ai.SteeringState.PENDING -> latestState
                    else -> current.state
                }
                entries + (
                    transition.commandId to current.copy(
                        state = mergedState,
                        historyMode = latestHistoryMode ?: current.historyMode,
                        editable = mergedState == me.rerere.rikkahub.data.ai.SteeringState.PENDING ||
                            mergedState == me.rerere.rikkahub.data.ai.SteeringState.APPLIED,
                    )
                )
            }
            val steeringEnvelope = acceptedCommands[transition.commandId]
            if (steeringEnvelope is CommandEnvelope<*> && steeringEnvelope.command is SteerCommand) {
                when (latestState) {
                    me.rerere.rikkahub.data.ai.SteeringState.APPLIED ->
                        complete(steeringEnvelope, CommandOutcome.Completed)
                    me.rerere.rikkahub.data.ai.SteeringState.REJECTED_NOT_STEERABLE ->
                        complete(
                            steeringEnvelope,
                            CommandOutcome.NotApplied(transition.reason ?: "Guidance could not be applied"),
                        )
                    else -> Unit
                }
            }
        }
        val outcome = CompletableDeferred<RunOutcome>()
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                // Claim the durable row before executing. A failed claim must not run the
                // command: another worker may already own the lease.
                val claimResult = runSuspendCatching { claimDurable(envelope) }
                if (claimResult.isFailure) {
                    RunOutcome.Failed(claimResult.exceptionOrNull() ?: IllegalStateException("Durable claim failed"))
                } else {
                    // A lease callback is part of the run's lifecycle. Do not turn a
                    // cancellation while claiming the durable command into a normal
                    // failure that leaves a RUNNING row without a live worker.
                    val lease = runSuspendCatching { onRunStarted(envelope) }.getOrNull()
                    val durableLeaseJob = durableQueue?.let { queue ->
                        sessionScope.launch(dispatchers.io + CoroutineName("Conversation-$conversationId-lease-${envelope.id}")) {
                            while (currentCoroutineContext().isActive) {
                                delay(10.seconds)
                                runSuspendCatching { queue.renew(envelope.id.toString()) }
                            }
                        }
                    }
                    try {
                        executor.execute(envelope, control)
                    } finally {
                        // The lease must be released even when the executor is cancelled.
                        // AutoCloseable.close() is deliberately best-effort and synchronous;
                        // the callback owns any async heartbeat cancellation.
                        durableLeaseJob?.cancel()
                        lease?.let { runCatching { it.close() } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RunOutcome.Failed(e)
            }
            outcome.complete(result)
        }
        activeEnvelope = envelope
        activeRun = ActiveRun(
            id = control.runId,
            commandId = envelope.id,
            kind = if (envelope.command is SendMessageCommand) RunKind.LLM_GENERATION else RunKind.ROUTINE,
            capabilities = setOf(RunCapability.SOFT_STEERABLE, RunCapability.TOOL_CANCELLABLE),
            control = control,
            job = job,
            outcome = outcome,
        )
        softSteeringTarget.set(control)
        onRunJobChanged(job)
        _runtimeState.value = RuntimeState.Running
        refreshQueueStatus()
        job.invokeOnCompletion { completionWakeup.trySend(Unit) }
        job.start()
    }

    private suspend fun handleRunFinished(run: ActiveRun) {
        if (activeRun !== run) return
        cancellationWatchdog?.cancel()
        cancellationWatchdog = null
        val envelope = activeEnvelope
        val outcome = run.outcome.getCompletedOrNull()
        val cancellationReason = when {
            run.control.stoppedBy != null -> ToolCancelReason.USER_STOPPED
            run.control.interruptedBy != null -> ToolCancelReason.USER_INTERRUPTED
            else -> null
        }
        val cleanup = cancellationReason?.let { reason ->
            runSuspendCatching {
                repairer.repair(run.id, reason, run.control.toolCancellationResults())
            }.getOrElse { InterruptCleanupResult.PartialFailure(it.message ?: "Interrupt repair failed") }
        }
        val repairFailure = (cleanup as? InterruptCleanupResult.PartialFailure)?.reason
        if (repairFailure != null) queuePaused = true

        // The active run finishing is the lock boundary for purple/yellow selection.
        // Close first, then persist from one immutable final snapshot so a UI toggle
        // cannot race the history decision.
        val unfinishedSteering = run.control.closeSteering()
        softSteeringTarget.compareAndSet(run.control, null)
        val finalSteeringNotes = run.control.steeringNotes()
        val finalSteeringStates = run.control.steeringStates()
        _steeringEntries.update { entries ->
            entries.mapValues { (commandId, entry) ->
                if (commandId in finalSteeringNotes) {
                    entry.copy(
                        state = finalSteeringStates[commandId] ?: entry.state,
                        historyMode = finalSteeringNotes[commandId]?.historyMode ?: entry.historyMode,
                        editable = false,
                    )
                } else {
                    entry
                }
            }
        }
        val appliedSteeringNotes = finalSteeringNotes.values.filter { note ->
            finalSteeringStates[note.commandId] == me.rerere.rikkahub.data.ai.SteeringState.APPLIED
        }
        val steeringHistoryFailure = runSuspendCatching {
            appliedSteeringNotes.forEach { onPersistSteering(it) }
        }.exceptionOrNull()
        if (steeringHistoryFailure == null && appliedSteeringNotes.isNotEmpty()) {
            val persistedCommandIds = appliedSteeringNotes.mapTo(hashSetOf()) { it.commandId }
            _steeringEntries.update { entries ->
                entries.filterKeys { it !in persistedCommandIds }
            }
        }
        val completedRevision = (outcome as? RunOutcome.Completed)?.finalRevision ?: stateRevision.get()
        stateRevision.updateAndGet { current -> maxOf(current, completedRevision) }
        val persistenceResult = runSuspendCatching {
            persistenceCoordinator.flushThrough(stateRevision.get())
        }.getOrElse { PersistResult.Failed(it) }
        val persistenceFailure = steeringHistoryFailure ?: (persistenceResult as? PersistResult.Failed)?.error
        if (persistenceFailure != null) queuePaused = true
        unfinishedSteering.forEach { transition ->
            val steeringEnvelope = acceptedCommands[transition.commandId]
            if (steeringEnvelope?.command is SteerCommand) {
                if (cancellationReason == null) {
                    @Suppress("UNCHECKED_CAST")
                    fallbackSteeringToQueue(
                        steeringEnvelope as CommandEnvelope<SteerCommand>,
                        run.control,
                    )
                } else {
                    // STOP/INTERRUPT explicitly abandons this run. A pending soft hint from
                    // that abandoned run must not reappear later as an unrelated queued turn.
                    complete(
                        steeringEnvelope,
                        CommandOutcome.NotApplied(
                            "Current task ended before the guidance reached a model checkpoint"
                        ),
                    )
                }
            }
        }
        _steeringStatus.value = run.control.steeringStates()
        activeRun = null
        activeEnvelope = null
        onRunJobChanged(null)
        var replacementToStart: CommandEnvelope<out EmergencyCommand>? = null
        when {
            run.control.stoppedBy != null -> {
                if (envelope != null) complete(envelope, CommandOutcome.Cancelled, persistDurable = false)
                val stop = pendingStop
                pendingStop = null
                stop?.let { complete(it, CommandOutcome.Completed) }
                _runtimeState.value = if (queuePaused) RuntimeState.Paused else RuntimeState.Idle
            }
            run.control.interruptedBy != null -> {
                if (envelope != null) complete(envelope, CommandOutcome.Cancelled, persistDurable = false)
                val next = pendingAfterCancel
                pendingAfterCancel = null
                if (next != null) {
                    if (persistenceFailure == null && repairFailure == null) {
                        replacementToStart = next
                    } else {
                        complete(
                            next,
                            CommandOutcome.Failed(
                                IllegalStateException(
                                    repairFailure ?: persistenceFailure?.message
                                        ?: "Interrupt cleanup barrier failed",
                                )
                            ),
                        )
                    }
                } else {
                    _runtimeState.value = if (queuePaused) RuntimeState.Paused else RuntimeState.Idle
                }
            }
            else -> {
                if (envelope != null) {
                    val commandOutcome = persistenceFailure?.let { CommandOutcome.Failed(it) }
                        ?: outcome.toCommandOutcome()
                    // Active-run rows are finalized once, below, after the persistence
                    // barrier (or moved to WAITING_APPROVAL). Avoid a competing async write.
                    complete(
                        envelope,
                        commandOutcome,
                        persistDurable = false,
                    )
                }
                _runtimeState.value = when (outcome) {
                    is RunOutcome.WaitingApproval -> RuntimeState.WaitingApproval
                    else -> if (queuePaused) RuntimeState.Paused else RuntimeState.Idle
                }
            }
        }
        if (envelope != null) {
            val finalOutcome = when {
                run.control.stoppedBy != null || run.control.interruptedBy != null -> CommandOutcome.Cancelled
                else -> persistenceFailure?.let { CommandOutcome.Failed(it) }
                    ?: outcome.toCommandOutcome()
            }
            runSuspendCatching {
                if (outcome is RunOutcome.WaitingApproval) {
                    durableQueue?.resolvePending(
                        id = envelope.id.toString(),
                        state = DurableCommandState.WAITING_APPROVAL,
                    )
                } else {
                    finishDurable(envelope, finalOutcome)
                }
            }
            onRunFinished(envelope, finalOutcome)
        }
        replacementToStart?.let(::startRun)
        if (persistenceFailure == null && repairFailure == null && !startPendingIfReady() && !hasRetainedWork) {
            onBecameIdle(conversationId)
        }
        refreshQueueStatus()
    }

    private fun RunOutcome?.toCommandOutcome(): CommandOutcome = when (this) {
        is RunOutcome.Completed -> CommandOutcome.Completed
        is RunOutcome.Rejected -> CommandOutcome.Rejected(reason)
        is RunOutcome.Conflict -> CommandOutcome.Conflict(reason)
        is RunOutcome.Failed -> CommandOutcome.Failed(error)
        is RunOutcome.WaitingApproval -> CommandOutcome.Completed
        is RunOutcome.Interrupted,
        is RunOutcome.Stopped -> CommandOutcome.Cancelled
        else -> CommandOutcome.Failed(IllegalStateException("Run ended without outcome"))
    }

    private fun CommandOutcome.toDurableState(): DurableCommandState = when (this) {
        CommandOutcome.Completed -> DurableCommandState.COMPLETED
        CommandOutcome.Cancelled,
        is CommandOutcome.Superseded -> DurableCommandState.CANCELLED
        is CommandOutcome.NotApplied -> DurableCommandState.COMPLETED
        is CommandOutcome.Rejected,
        is CommandOutcome.Conflict,
        is CommandOutcome.Failed,
        is CommandOutcome.SkippedDependencyFailed -> DurableCommandState.FAILED
    }

    private fun rejectAllAcceptedCommands(reason: String) {
        pendingNormalIndex.clear()
        acceptedCommands.values.toList().forEach { complete(it, CommandOutcome.Rejected(reason)) }
        emergencySlot.getAndSet(null)?.let { complete(it, CommandOutcome.Rejected(reason)) }
        pendingAfterCancel?.let { complete(it, CommandOutcome.Rejected(reason)) }
        pendingStop?.let { complete(it, CommandOutcome.Rejected(reason)) }
        pendingAfterCancel = null
        pendingStop = null
        refreshQueueStatus()
    }
}

private data class CancellationTimeout(
    val runId: Uuid,
    val commandId: Uuid,
)

private fun <T> CompletableDeferred<T>.getCompletedOrNull(): T? =
    if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null
