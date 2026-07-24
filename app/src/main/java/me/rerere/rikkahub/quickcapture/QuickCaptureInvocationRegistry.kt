package me.rerere.rikkahub.quickcapture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.rerere.rikkahub.assistant.InvocationSurfaceContext
import me.rerere.rikkahub.assistant.InvocationSurfaceContextProvider
import me.rerere.rikkahub.assistant.InvocationSurfacePresence
import me.rerere.rikkahub.assistant.SystemAssistantHostKind
import me.rerere.rikkahub.assistant.SystemAssistantInvocationRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

/**
 * Process-local proof that a QuickCapture command came from a visible owner-user overlay.
 * Nothing exported can manufacture these tokens. Process death intentionally discards them.
 */
object QuickCaptureInvocationRegistry : InvocationSurfaceContextProvider {
    private const val MAX_RUN_AGE_NANOS = 30L * 60L * 1_000_000_000L
    private val nextId = AtomicLong(0)
    private val registeredOverlays = ConcurrentHashMap.newKeySet<Long>()
    private val overlays = ConcurrentHashMap<Long, Uuid>()
    private data class AcceptedRun(
        val conversationId: Uuid,
        val assistantId: Uuid,
        val commandId: Uuid,
        val captureSessionId: Uuid,
        val expiresAtNanos: Long,
    )
    private val acceptedRuns = ConcurrentHashMap<Long, AcceptedRun>()

    fun registerOverlay(): QuickCaptureOverlayToken {
        val id = nextId.incrementAndGet()
        registeredOverlays.add(id)
        return QuickCaptureOverlayToken(id)
    }

    internal fun acquireAcceptedRun(
        overlayId: Long,
        conversationId: Uuid,
        assistantId: Uuid,
        commandId: Uuid,
        captureSessionId: Uuid,
    ): QuickCaptureAcceptedRunToken? {
        trimExpired()
        // The exact visible-overlay token must still be registered and bound to this immutable
        // conversation. A different overlay (or a later re-opened bubble) cannot mint a lease
        // for the original capture session.
        if (overlays[overlayId] != conversationId) return null
        // Command IDs are generated once per submission. Refusing a duplicate prevents replay of
        // a still-live admission proof through another code path in the same process.
        if (acceptedRuns.values.any { it.commandId == commandId }) return null
        val id = nextId.incrementAndGet()
        acceptedRuns[id] = AcceptedRun(
            conversationId = conversationId,
            assistantId = assistantId,
            commandId = commandId,
            captureSessionId = captureSessionId,
            expiresAtNanos = System.nanoTime() + MAX_RUN_AGE_NANOS,
        )
        return QuickCaptureAcceptedRunToken(id)
    }

    fun hasAcceptedRun(
        conversationId: Uuid,
        assistantId: Uuid,
        commandId: Uuid,
        captureSessionId: Uuid,
    ): Boolean {
        trimExpired()
        return acceptedRuns.values.any { run ->
            run.conversationId == conversationId &&
                run.assistantId == assistantId &&
                run.commandId == commandId &&
                run.captureSessionId == captureSessionId
        }
    }

    fun hasAuthorizedRun(conversationId: Uuid, commandId: Uuid?): Boolean {
        trimExpired()
        return commandId != null && acceptedRuns.values.any {
                it.conversationId == conversationId && it.commandId == commandId
            }
    }

    override fun currentContext(
        origin: ToolCallOrigin,
        conversationId: Uuid,
        commandId: Uuid?,
    ): InvocationSurfaceContext {
        if (origin != ToolCallOrigin.QuickCapture) {
            return SystemAssistantInvocationRegistry.currentContext(origin, conversationId, commandId)
        }
        val visible = overlays.values.any { it == conversationId }
        val accepted = commandId != null && hasAuthorizedRun(conversationId, commandId)
        return InvocationSurfaceContext(
            origin = origin,
            hostKind = SystemAssistantHostKind.QUICK_CAPTURE_OVERLAY,
            presence = if (visible) {
                InvocationSurfacePresence.OVERLAY_VISIBLE
            } else {
                InvocationSurfacePresence.RUNNING_AFTER_OVERLAY_CLOSED
            },
            conversationId = conversationId,
            commandId = commandId,
            // A visible bubble is admission proof only. Tool execution must hold the exact
            // accepted-run lease, so a restored durable queue cannot regain privileges merely
            // because the user later starts the overlay again.
            unlockedOwner = accepted,
        )
    }

    internal fun bindOverlay(id: Long, conversationId: Uuid?) {
        if (id !in registeredOverlays) return
        if (conversationId == null) overlays.remove(id) else overlays[id] = conversationId
    }

    internal fun unregisterOverlay(id: Long) {
        registeredOverlays.remove(id)
        overlays.remove(id)
    }

    internal fun releaseAcceptedRun(id: Long) {
        acceptedRuns.remove(id)
    }

    private fun trimExpired() {
        val now = System.nanoTime()
        acceptedRuns.forEach { (id, run) ->
            if (now >= run.expiresAtNanos) acceptedRuns.remove(id, run)
        }
    }
}

object InvocationSurfaceContexts : InvocationSurfaceContextProvider {
    override fun currentContext(
        origin: ToolCallOrigin,
        conversationId: Uuid,
        commandId: Uuid?,
    ): InvocationSurfaceContext = QuickCaptureInvocationRegistry.currentContext(origin, conversationId, commandId)
}

class QuickCaptureOverlayToken internal constructor(private val id: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val consumedCaptureSessions = ConcurrentHashMap.newKeySet<Uuid>()

    fun bindConversation(conversationId: Uuid?) {
        if (!closed.get()) QuickCaptureInvocationRegistry.bindOverlay(id, conversationId)
    }

    fun acquireAcceptedRun(
        conversationId: Uuid,
        assistantId: Uuid,
        commandId: Uuid,
        captureSessionId: Uuid,
    ): QuickCaptureAcceptedRunToken? = if (
        closed.get() || !consumedCaptureSessions.add(captureSessionId)
    ) {
        null
    } else {
        QuickCaptureInvocationRegistry.acquireAcceptedRun(
            overlayId = id,
            conversationId = conversationId,
            assistantId = assistantId,
            commandId = commandId,
            captureSessionId = captureSessionId,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) QuickCaptureInvocationRegistry.unregisterOverlay(id)
    }
}

class QuickCaptureAcceptedRunToken internal constructor(private val id: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) QuickCaptureInvocationRegistry.releaseAcceptedRun(id)
    }
}
