package me.rerere.rikkahub.quickcapture

import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SubmitResult
import kotlin.uuid.Uuid

sealed interface QuickCaptureStartEligibility {
    data class Ready(val target: QuickCaptureTarget) : QuickCaptureStartEligibility

    data class Blocked(
        val code: String,
        val detail: String? = null,
    ) : QuickCaptureStartEligibility
}

data class QuickCapturePreview(
    /** Caller-owned bitmap. It is held only for the settings preview and is never persisted. */
    val bitmap: android.graphics.Bitmap,
    val width: Int,
    val height: Int,
    val backend: ScreenCaptureBackendKind,
)

/**
 * In-process coordinator for the owner-visible QuickCapture overlay.
 *
 * It does not own an Android window and cannot be invoked from an exported component. The service
 * supplies the visible-overlay proof and the narrow UI seam needed to hide/crop. Every automatic
 * submission is still routed through the ordinary persisted conversation queue.
 */
class QuickCaptureCoordinator(
    context: Context,
    private val settingsStore: SettingsStore,
    private val targetResolver: QuickCaptureTargetResolver,
    private val captureManager: ScreenCaptureManager,
    private val filesManager: FilesManager,
    private val chatService: ChatService,
    private val safetySettings: AgentSafetySettings,
    private val accessState: QuickCaptureAccessState,
    private val navigator: QuickCaptureNavigator,
    parentScope: AppScope,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private val _state = MutableStateFlow(QuickCaptureUiState())
    val state = _state.asStateFlow()

    private var overlayHost: QuickCaptureOverlayHost? = null
    private var overlayToken: QuickCaptureOverlayToken? = null
    private var temporaryAssistantId: Uuid? = null
    private var batchTimeoutJob: Job? = null
    private var completionResetJob: Job? = null
    private var activeOverlayOperation: Job? = null
    private var activeOverlayOperationId: Uuid? = null

    fun attachOverlay(host: QuickCaptureOverlayHost, token: QuickCaptureOverlayToken) {
        overlayHost = host
        overlayToken?.close()
        overlayToken = token
        _state.value.target?.let { token.bindConversation(it.conversationId) }
    }

    fun detachOverlay(host: QuickCaptureOverlayHost) {
        if (overlayHost !== host) return
        overlayHost = null
        overlayToken?.close()
        overlayToken = null
        // Service destruction can happen outside the explicit stop action (for example after an
        // overlay permission revocation). Cancel any bitmap-owning operation so a region selector
        // cannot retain its source image until its timeout after the Android window is gone.
        if (activeOverlayOperation?.isActive == true || _state.value.stage in CANCELLABLE_CAPTURE_STAGES) {
            scope.launch { stopAndDiscardUnsubmitted() }
        }
    }

    fun setTemporaryAssistant(assistantId: Uuid?) {
        // The selector remains as a compatibility affordance for existing overlays, but it
        // cannot redirect a capture to another assistant.  Only the current global authority
        // may be selected, and the resolver independently checks that same authority at submit.
        temporaryAssistantId = assistantId?.takeIf {
            SecondUserAuthorityRegistry.current()?.assistantId == it
        }
    }

    suspend fun preflightStart(): QuickCaptureStartEligibility {
        accessBlock(requireOverlayPermission = true)?.let { return it }
        val resolved = targetResolver.resolve()
        val target = (resolved as? QuickCaptureTargetResolution.Resolved)?.target
            ?: return QuickCaptureStartEligibility.Blocked(
                code = (resolved as QuickCaptureTargetResolution.Unavailable).reason.name.lowercase(),
                detail = resolved.detail,
            )
        val settings = settingsStore.settingsFlow.first { !it.init }.quickCaptureSettings.normalized()
        if (!captureManager.isBackendAvailable(settings.backend)) {
            return QuickCaptureStartEligibility.Blocked("capture_backend_unavailable")
        }
        return QuickCaptureStartEligibility.Ready(target)
    }

    /** A settings-only capture probe: it never resolves a model target or submits a message. */
    suspend fun capturePreview(): Result<QuickCapturePreview> = try {
        accessBlock(requireOverlayPermission = false)?.let { blocked -> error(blocked.code) }
        val settings = settingsStore.settingsFlow.first { !it.init }.quickCaptureSettings.normalized()
        val host = overlayHost
        val result = withQuickCaptureOverlayHidden(host) {
            captureManager.capture(settings.backend)
        }
        Result.success(
            when (result) {
                is ScreenCaptureResult.Failure -> error(result.code.name.lowercase())
                is ScreenCaptureResult.Success -> QuickCapturePreview(
                    bitmap = result.capture.bitmap,
                    width = result.capture.bitmap.width,
                    height = result.capture.bitmap.height,
                    backend = result.capture.backend,
                )
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    fun onSingleTap() {
        when (decideQuickCaptureSingleTap(_state.value.stage)) {
            QuickCaptureSingleTapAction.CAPTURE_SINGLE ->
                launchOverlayOperation { captureSingle() }
            QuickCaptureSingleTapAction.CAPTURE_FOR_BATCH ->
                launchOverlayOperation { captureForBatch() }
            QuickCaptureSingleTapAction.RESET_AND_CAPTURE ->
                launchOverlayOperation {
                    resetToIdle()
                    captureSingle()
                }
            QuickCaptureSingleTapAction.IGNORE_WHILE_BUSY -> Unit
        }
    }

    fun onLongPress() {
        launchOverlayOperation {
            when (_state.value.stage) {
                QuickCaptureStage.IDLE,
                QuickCaptureStage.COMPLETED,
                QuickCaptureStage.FAILED,
                -> {
                    if (_state.value.stage != QuickCaptureStage.IDLE) resetToIdle()
                    beginBatch()
                }
                QuickCaptureStage.COLLECTING -> submitBatch()
                else -> Unit
            }
        }
    }

    fun onDoubleTap() {
        scope.launch {
            if (_state.value.stage == QuickCaptureStage.COLLECTING) {
                cancelBatch()
            }
        }
    }

    fun openCurrentConversation() {
        _state.value.target?.let { navigator.openConversation(it.conversationId) }
    }

    fun openSettings() = navigator.openSettings()

    suspend fun availableTemporaryAssistants(): List<Pair<Uuid, String>> {
        val active = SecondUserAuthorityRegistry.current() ?: return emptyList()
        return settingsStore.settingsFlow
            .first { !it.init }
            .assistants
            .firstOrNull { it.id == active.assistantId }
            ?.let { listOf(it.id to it.name) }
            .orEmpty()
    }

    suspend fun updateBubblePosition(edge: QuickCaptureBubbleEdge, yFraction: Float) {
        settingsStore.update { current ->
            current.copy(
                quickCaptureSettings = current.quickCaptureSettings.copy(
                    bubbleEdge = edge,
                    bubbleYFraction = yFraction.coerceIn(0f, 1f),
                ).normalized(),
            )
        }
    }

    suspend fun stopAndDiscardUnsubmitted() {
        val stageBeforeStop = _state.value.stage
        val operationToCancel = activeOverlayOperation.takeIf {
            // The operation can still be resolving a target while state is IDLE. Once it reaches
            // SUBMITTING we do not cancel: queue admission may already have atomically accepted
            // the durable command and must retain its files and run lease.
            stageBeforeStop != QuickCaptureStage.SUBMITTING && _state.value.commandId == null
        }
        operationToCancel?.cancel()
        mutex.withLock {
            val current = _state.value
            if (shouldDiscardQuickCaptureAttachments(current)) {
                discardAttachments(_state.value.attachments)
            }
            batchTimeoutJob?.cancel()
            batchTimeoutJob = null
            _state.value = QuickCaptureUiState()
            temporaryAssistantId = null
            if (activeOverlayOperation === operationToCancel) {
                activeOverlayOperation = null
                activeOverlayOperationId = null
            }
        }
    }

    private suspend fun captureSingle() {
        mutex.withLock {
            if (_state.value.stage != QuickCaptureStage.IDLE) return@withLock
            val target = resolveTargetForNewSession() ?: return@withLock
            val sessionId = Uuid.random()
            transition(QuickCaptureStage.HIDING_OVERLAY) {
                it.copy(captureSessionId = sessionId, target = target, attachments = emptyList(), errorCode = null)
            }
            captureAndPersist(target, sessionId, collecting = false)
        }
    }

    private suspend fun beginBatch() {
        mutex.withLock {
            if (_state.value.stage != QuickCaptureStage.IDLE) return@withLock
            val target = resolveTargetForNewSession() ?: return@withLock
            val sessionId = Uuid.random()
            transition(QuickCaptureStage.COLLECTING) {
                it.copy(captureSessionId = sessionId, target = target, attachments = emptyList(), errorCode = null)
            }
            scheduleBatchTimeout(sessionId)
        }
    }

    private suspend fun captureForBatch() {
        mutex.withLock {
            val snapshot = _state.value
            if (snapshot.stage != QuickCaptureStage.COLLECTING) return@withLock
            val target = snapshot.target ?: run {
                fail("target_snapshot_missing")
                return@withLock
            }
            val sessionId = snapshot.captureSessionId ?: run {
                fail("capture_session_missing")
                return@withLock
            }
            if (!validateTargetSnapshot(target)) return@withLock
            transition(QuickCaptureStage.HIDING_OVERLAY)
            captureAndPersist(target, sessionId, collecting = true)
        }
    }

    private suspend fun captureAndPersist(
        target: QuickCaptureTarget,
        sessionId: Uuid,
        collecting: Boolean,
    ) {
        val settings = settingsStore.settingsFlow.first { !it.init }.quickCaptureSettings.normalized()
        val host = overlayHost
        val capture = try {
            withQuickCaptureOverlayHidden(host) {
                transition(QuickCaptureStage.CAPTURING)
                captureManager.capture(settings.backend)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ScreenCaptureResult.Failure(CaptureFailureCode.INTERNAL_ERROR)
        }
        val managed = when (capture) {
            is ScreenCaptureResult.Failure -> {
                fail(capture.code.name.lowercase())
                return
            }
            is ScreenCaptureResult.Success -> capture.capture
        }
        var source: android.graphics.Bitmap? = managed.bitmap
        val bitmap = try {
            if (settings.areaMode == QuickCaptureAreaMode.SELECT_REGION) {
                val selector = overlayHost
                if (selector == null) {
                    fail("region_selector_unavailable")
                    return
                }
                transition(QuickCaptureStage.SELECTING_REGION)
                val original = source ?: run {
                    fail("capture_bitmap_missing")
                    return
                }
                source = null // The selector now owns (and always consumes) the full bitmap.
                selector.selectRegion(original)
                    ?: run {
                        if (collecting) transition(QuickCaptureStage.COLLECTING) else transition(QuickCaptureStage.IDLE)
                        return
                    }
            } else {
                (source ?: run {
                    fail("capture_bitmap_missing")
                    return
                }).also { source = null }
            }
        } finally {
            source?.let(::recycle)
        }
        var persistedEntity: me.rerere.rikkahub.data.db.entity.ManagedFileEntity? = null
        var attachmentAccepted = false
        try {
            transition(QuickCaptureStage.PERSISTING)
            currentCoroutineContext().ensureActive()
            val entity = withContext(NonCancellable) {
                filesManager.saveUploadPng(
                    bitmap = bitmap,
                    displayName = "quick-capture-$sessionId-${_state.value.attachments.size + 1}.png",
                )
            }
            persistedEntity = entity
            currentCoroutineContext().ensureActive()
            val attachment = QuickCaptureAttachment(
                managedFileId = entity.id,
                uri = filesManager.getFile(entity).toUri().toString(),
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = entity.sizeBytes,
                capturedAtMs = managed.capturedAtMs,
            )
            when (decideQuickCaptureBatch(_state.value.attachments, attachment.sizeBytes)) {
                QuickCaptureBatchDecision.TooManyImages -> {
                    deleteImmediately(entity)
                    persistedEntity = null
                    if (collecting && _state.value.attachments.isNotEmpty()) {
                        // Reject only the ninth image. The already persisted eight-image batch
                        // remains valid and can still be submitted with a long press.
                        transition(QuickCaptureStage.COLLECTING) {
                            it.copy(errorCode = "batch_image_limit")
                        }
                        scheduleBatchTimeout(sessionId)
                    } else {
                        fail("batch_image_limit")
                    }
                    return
                }
                QuickCaptureBatchDecision.TooLarge -> {
                    deleteImmediately(entity)
                    persistedEntity = null
                    if (collecting && _state.value.attachments.isNotEmpty()) {
                        // A too-large additional PNG must not discard valid earlier captures.
                        transition(QuickCaptureStage.COLLECTING) {
                            it.copy(errorCode = "batch_size_limit")
                        }
                        scheduleBatchTimeout(sessionId)
                    } else {
                        fail("batch_size_limit")
                    }
                    return
                }
                is QuickCaptureBatchDecision.Accepted -> Unit
            }
            _state.value = _state.value.copy(
                attachments = _state.value.attachments + attachment,
                errorCode = null,
            )
            attachmentAccepted = true
            if (collecting) {
                transition(QuickCaptureStage.COLLECTING)
                scheduleBatchTimeout(sessionId)
            } else {
                submitCurrent(target, sessionId)
            }
        } catch (cancelled: CancellationException) {
            if (!attachmentAccepted) {
                val entity = persistedEntity
                if (entity != null) deleteImmediately(entity)
            }
            throw cancelled
        } catch (_: Throwable) {
            if (!attachmentAccepted) {
                val entity = persistedEntity
                if (entity != null) deleteImmediately(entity)
            }
            discardAttachments(_state.value.attachments)
            fail("persist_failed")
        } finally {
            recycle(bitmap)
        }
    }

    private suspend fun submitBatch() {
        mutex.withLock {
            val snapshot = _state.value
            if (snapshot.stage != QuickCaptureStage.COLLECTING) return@withLock
            if (snapshot.attachments.isEmpty()) {
                fail("batch_empty")
                return@withLock
            }
            val target = snapshot.target ?: run {
                fail("target_snapshot_missing")
                return@withLock
            }
            val sessionId = snapshot.captureSessionId ?: run {
                fail("capture_session_missing")
                return@withLock
            }
            if (!validateTargetSnapshot(target)) return@withLock
            batchTimeoutJob?.cancel()
            batchTimeoutJob = null
            submitCurrent(target, sessionId)
        }
    }

    private suspend fun submitCurrent(target: QuickCaptureTarget, sessionId: Uuid) {
        val snapshot = _state.value
        val settings = settingsStore.settingsFlow.first { !it.init }.quickCaptureSettings.normalized()
        val prompt = settings.prompt.trim().ifBlank { DEFAULT_QUICK_CAPTURE_PROMPT }
        if (!settings.autoSend) {
            navigator.openDraft(target.conversationId, prompt, snapshot.attachments.map { it.uri })
            temporaryAssistantId = null
            transition(QuickCaptureStage.COMPLETED) { it.copy(answerPreview = null, errorCode = null) }
            scheduleCompletedReset()
            return
        }
        if (!validateTargetSnapshot(target)) return
        val commandId = Uuid.random()
        val runToken = overlayToken?.let {
            it.bindConversation(target.conversationId)
            it.acquireAcceptedRun(
                conversationId = target.conversationId,
                assistantId = target.assistantId,
                commandId = commandId,
                captureSessionId = sessionId,
            )
        }
        if (runToken == null) {
            discardAttachments(snapshot.attachments)
            fail("overlay_not_visible")
            return
        }
        transition(QuickCaptureStage.SUBMITTING) { it.copy(commandId = commandId, errorCode = null) }
        val annotation = UIMessageAnnotation.QuickCapture(
            commandId = commandId.toString(),
            captureSessionId = sessionId.toString(),
        )
        val submission = try {
            chatService.submitUserMessageTracked(
                commandId = commandId,
                conversationId = target.conversationId,
                content = listOf(UIMessagePart.Text(prompt)) + snapshot.attachments.map {
                    UIMessagePart.Image(it.uri)
                },
                answer = true,
                origin = CommandOrigin.QUICK_CAPTURE,
                dedupeKey = "quick-capture:$sessionId",
                assistantIdSnapshot = target.assistantId,
                annotations = listOf(annotation),
                quickCaptureSessionId = sessionId,
            )
        } catch (cancelled: CancellationException) {
            runToken.close()
            throw cancelled
        } catch (_: Throwable) {
            runToken.close()
            discardAttachments(snapshot.attachments)
            fail("submission_failed")
            return
        }
        when (val result = submission.submission) {
            is SubmitResult.Accepted -> {
                if (result.commandId != commandId) {
                    runToken.close()
                    discardAttachments(snapshot.attachments)
                    fail("command_identity_mismatch")
                    return
                }
                temporaryAssistantId = null
                transition(QuickCaptureStage.QUEUED) { it.copy(commandId = commandId) }
                observeAcceptedCommand(target, commandId, annotation, submission.outcome, runToken)
            }
            is SubmitResult.QueueFull -> {
                runToken.close()
                discardAttachments(snapshot.attachments)
                fail("queue_full")
            }
            is SubmitResult.Rejected -> {
                runToken.close()
                discardAttachments(snapshot.attachments)
                fail("submission_rejected", result.reason)
            }
            is SubmitResult.RuntimeUnavailable -> {
                runToken.close()
                discardAttachments(snapshot.attachments)
                fail("runtime_unavailable", result.reason)
            }
        }
    }

    private fun observeAcceptedCommand(
        target: QuickCaptureTarget,
        commandId: Uuid,
        annotation: UIMessageAnnotation.QuickCapture,
        outcome: kotlinx.coroutines.Deferred<CommandOutcome>,
        runToken: QuickCaptureAcceptedRunToken,
    ) {
        val statusObserver = scope.launch {
            combine(
                chatService.getQueueStatusFlow(target.conversationId),
                chatService.getRuntimeStateFlow(target.conversationId),
            ) { queue, runtime -> queue to runtime }.collect { (queue, runtime) ->
                val stage = when {
                    queue.activeCommandId == commandId && runtime is RuntimeState.WaitingApproval ->
                        QuickCaptureStage.WAITING_APPROVAL
                    queue.activeCommandId == commandId -> QuickCaptureStage.RUNNING
                    commandId in queue.pendingCommandIds -> QuickCaptureStage.QUEUED
                    else -> null
                }
                if (stage != null) updateForCommand(commandId, stage)
            }
        }
        scope.launch {
            try {
                when (val terminal = outcome.await()) {
                    CommandOutcome.Completed -> {
                        val preview = findAnswerPreview(target.conversationId, annotation)
                        updateForCommand(commandId, QuickCaptureStage.COMPLETED, preview = preview)
                        scheduleCompletedReset()
                    }
                    is CommandOutcome.Failed -> updateForCommand(
                        commandId,
                        QuickCaptureStage.FAILED,
                        error = terminal.error.message ?: terminal.error.javaClass.simpleName,
                    )
                    is CommandOutcome.Rejected -> updateForCommand(commandId, QuickCaptureStage.FAILED, error = terminal.reason)
                    is CommandOutcome.Conflict -> updateForCommand(commandId, QuickCaptureStage.FAILED, error = terminal.reason)
                    is CommandOutcome.NotApplied -> updateForCommand(commandId, QuickCaptureStage.FAILED, error = terminal.reason)
                    is CommandOutcome.SkippedDependencyFailed -> updateForCommand(
                        commandId,
                        QuickCaptureStage.FAILED,
                        error = "dependency_failed",
                    )
                    CommandOutcome.Cancelled -> updateForCommand(commandId, QuickCaptureStage.FAILED, error = "cancelled")
                    is CommandOutcome.Superseded -> updateForCommand(commandId, QuickCaptureStage.FAILED, error = "superseded")
                }
            } catch (_: Throwable) {
                updateForCommand(commandId, QuickCaptureStage.FAILED, error = "outcome_unavailable")
            } finally {
                statusObserver.cancel()
                runToken.close()
            }
        }
    }

    private suspend fun findAnswerPreview(
        conversationId: Uuid,
        annotation: UIMessageAnnotation.QuickCapture,
    ): String? = chatService.getConversationFlow(conversationId).value.currentMessages
        .asReversed()
        .firstOrNull { it.role == MessageRole.ASSISTANT && annotation in it.annotations }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString(" ") { it.text }
        ?.trim()
        ?.take(280)
        ?.takeIf { it.isNotEmpty() }

    private suspend fun resolveTargetForNewSession(): QuickCaptureTarget? {
        accessBlock(requireOverlayPermission = true)?.let {
            fail(it.code, it.detail)
            return null
        }
        val resolved = targetResolver.resolve(temporaryAssistantId)
        val target = (resolved as? QuickCaptureTargetResolution.Resolved)?.target
        if (target == null) {
            val unavailable = resolved as QuickCaptureTargetResolution.Unavailable
            fail(unavailable.reason.name.lowercase(), unavailable.detail)
            return null
        }
        val settings = settingsStore.settingsFlow.first { !it.init }.quickCaptureSettings.normalized()
        if (!captureManager.isBackendAvailable(settings.backend)) {
            fail("capture_backend_unavailable")
            return null
        }
        overlayToken?.bindConversation(target.conversationId)
        transition(QuickCaptureStage.VALIDATING_TARGET) {
            it.copy(target = target, errorCode = null, answerPreview = null, commandId = null)
        }
        return target
    }

    private suspend fun validateTargetSnapshot(target: QuickCaptureTarget): Boolean {
        val result = targetResolver.validateTargetSnapshot(target)
        if (result is QuickCaptureTargetResolution.Resolved) return true
        val unavailable = result as QuickCaptureTargetResolution.Unavailable
        discardAttachments(_state.value.attachments)
        fail(unavailable.reason.name.lowercase(), unavailable.detail)
        return false
    }

    private suspend fun accessBlock(requireOverlayPermission: Boolean): QuickCaptureStartEligibility.Blocked? = when {
        !accessState.isOwnerUser() -> QuickCaptureStartEligibility.Blocked("owner_user_required")
        accessState.isDeviceLocked() -> QuickCaptureStartEligibility.Blocked("device_locked")
        safetySettings.emergencyStopFlow.first() -> QuickCaptureStartEligibility.Blocked("emergency_stop_active")
        requireOverlayPermission && !AndroidSettings.canDrawOverlays(appContext) ->
            QuickCaptureStartEligibility.Blocked("overlay_permission_required")
        else -> null
    }

    private fun scheduleBatchTimeout(sessionId: Uuid) {
        batchTimeoutJob?.cancel()
        batchTimeoutJob = scope.launch {
            delay(QUICK_CAPTURE_BATCH_IDLE_TIMEOUT_MS)
            mutex.withLock {
                val current = _state.value
                if (current.stage == QuickCaptureStage.COLLECTING && current.captureSessionId == sessionId) {
                    discardAttachments(current.attachments)
                    _state.value = QuickCaptureUiState()
                }
            }
        }
    }

    private suspend fun cancelBatch() {
        mutex.withLock {
            val current = _state.value
            if (current.stage != QuickCaptureStage.COLLECTING) return
            discardAttachments(current.attachments)
            batchTimeoutJob?.cancel()
            batchTimeoutJob = null
            _state.value = QuickCaptureUiState()
            temporaryAssistantId = null
        }
    }

    private fun scheduleCompletedReset() {
        completionResetJob?.cancel()
        completionResetJob = scope.launch {
            delay(COMPLETED_PREVIEW_MS)
            mutex.withLock {
                if (_state.value.stage == QuickCaptureStage.COMPLETED) _state.value = QuickCaptureUiState()
            }
        }
    }

    private suspend fun resetToIdle() = mutex.withLock {
        batchTimeoutJob?.cancel()
        batchTimeoutJob = null
        val current = _state.value
        if (shouldDiscardQuickCaptureAttachments(current)) {
            discardAttachments(current.attachments)
        }
        _state.value = QuickCaptureUiState()
    }

    private fun launchOverlayOperation(block: suspend () -> Unit) {
        if (activeOverlayOperation?.isActive == true) return
        val operationId = Uuid.random()
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (activeOverlayOperationId == operationId) {
                    activeOverlayOperation = null
                    activeOverlayOperationId = null
                }
            }
        }
        activeOverlayOperation = operation
        activeOverlayOperationId = operationId
        operation.start()
    }

    private suspend fun discardAttachments(attachments: List<QuickCaptureAttachment>) {
        attachments.forEach { attachment ->
            filesManager.get(attachment.managedFileId)?.let { entity ->
                deleteImmediately(entity)
            }
        }
    }

    private suspend fun deleteImmediately(entity: me.rerere.rikkahub.data.db.entity.ManagedFileEntity) {
        withContext(NonCancellable) {
            runCatching { filesManager.deleteManagedFile(entity) }
        }
    }

    private fun transition(
        stage: QuickCaptureStage,
        transform: (QuickCaptureUiState) -> QuickCaptureUiState = { it },
    ) {
        val current = _state.value
        if (!QuickCaptureStateMachine.allows(current.stage, stage) && current.stage != stage) return
        _state.value = transform(current).copy(stage = stage)
    }

    private fun updateForCommand(
        commandId: Uuid,
        stage: QuickCaptureStage,
        preview: String? = null,
        error: String? = null,
    ) {
        val current = _state.value
        if (current.commandId != commandId || current.stage in setOf(QuickCaptureStage.COMPLETED, QuickCaptureStage.FAILED)) {
            return
        }
        if (!QuickCaptureStateMachine.allows(current.stage, stage) && current.stage != stage) return
        _state.value = current.copy(stage = stage, answerPreview = preview ?: current.answerPreview, errorCode = error)
    }

    private fun fail(code: String, detail: String? = null) {
        completionResetJob?.cancel()
        val current = _state.value
        _state.value = current.copy(
            stage = QuickCaptureStage.FAILED,
            errorCode = listOfNotNull(code, detail?.take(120)).joinToString(":"),
        )
    }

    private fun recycle(bitmap: android.graphics.Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) runCatching { bitmap.recycle() }
    }

    private companion object {
        const val COMPLETED_PREVIEW_MS = 12_000L
        val CANCELLABLE_CAPTURE_STAGES = setOf(
            QuickCaptureStage.VALIDATING_TARGET,
            QuickCaptureStage.HIDING_OVERLAY,
            QuickCaptureStage.CAPTURING,
            QuickCaptureStage.SELECTING_REGION,
            QuickCaptureStage.PERSISTING,
            QuickCaptureStage.COLLECTING,
            QuickCaptureStage.FAILED,
        )
    }
}

/** Only accepted commands and a deliberately opened draft retain their app-private screenshots. */
internal fun shouldDiscardQuickCaptureAttachments(state: QuickCaptureUiState): Boolean =
    state.commandId == null && state.stage != QuickCaptureStage.COMPLETED
