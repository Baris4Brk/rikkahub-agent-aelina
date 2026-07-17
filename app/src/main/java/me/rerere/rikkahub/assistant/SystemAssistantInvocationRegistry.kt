package me.rerere.rikkahub.assistant

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

/**
 * Process-local authorization registry for native system-assistant invocations.
 *
 * Every registration receives an independent token. Closing a token only removes that token, so
 * overlapping `onShow` calls cannot accidentally make another visible session disappear from the
 * main-process execution gate's view.
 */
object SystemAssistantInvocationRegistry : InvocationSurfaceContextProvider {
    private const val ACCEPTED_RUN_MAX_AGE_NANOS = 30L * 60L * 1_000_000_000L

    private data class Invocation(
        val invokedFromKeyguard: Boolean,
        val ownerUser: Boolean,
        val hostKind: SystemAssistantHostKind,
        val conversationId: Uuid? = null,
    )

    private val nextTokenId = AtomicLong(0L)
    private val mutationLock = Any()
    private val invocations = ConcurrentHashMap<Long, Invocation>()
    private data class AcceptedRun(
        val conversationId: Uuid,
        val commandId: Uuid,
        val hostKind: SystemAssistantHostKind,
        val expiresAtNanos: Long,
    )

    private val acceptedRuns = ConcurrentHashMap<Long, AcceptedRun>()

    fun register(
        invokedFromKeyguard: Boolean,
        ownerUser: Boolean = true,
        hostKind: SystemAssistantHostKind = SystemAssistantHostKind.VOICE_SESSION,
    ): SystemAssistantInvocationToken {
        val id = synchronized(mutationLock) {
            nextTokenId.incrementAndGet().also { tokenId ->
                invocations[tokenId] = Invocation(
                    invokedFromKeyguard = invokedFromKeyguard,
                    ownerUser = ownerUser,
                    hostKind = hostKind,
                )
            }
        }
        return SystemAssistantInvocationToken(id)
    }

    override fun currentContext(
        origin: ToolCallOrigin,
        conversationId: Uuid,
        commandId: Uuid?,
    ): InvocationSurfaceContext {
        val now = System.nanoTime()
        acceptedRuns.forEach { (tokenId, run) ->
            if (now >= run.expiresAtNanos) acceptedRuns.remove(tokenId, run)
        }
        val acceptedRun = commandId?.let { requestedCommandId ->
            acceptedRuns.values.firstOrNull { run ->
                run.conversationId == conversationId && run.commandId == requestedCommandId
            }
        }
        val invocation = when (origin) {
            ToolCallOrigin.SystemAssistant -> invocations.values.firstOrNull { candidate ->
                candidate.ownerUser &&
                    !candidate.invokedFromKeyguard &&
                    candidate.conversationId == conversationId &&
                    (acceptedRun == null || candidate.hostKind == acceptedRun.hostKind)
            }

            ToolCallOrigin.SystemAssistantKeyguard -> invocations.values.firstOrNull { candidate ->
                candidate.invokedFromKeyguard && candidate.conversationId == conversationId
            }

            else -> null
        }
        val presence = when {
            origin == ToolCallOrigin.LocalChat -> InvocationSurfacePresence.FULL_CHAT
            origin != ToolCallOrigin.SystemAssistant &&
                origin != ToolCallOrigin.SystemAssistantKeyguard ->
                InvocationSurfacePresence.REMOTE_OR_WORKFLOW
            acceptedRun != null && invocation == null ->
                InvocationSurfacePresence.RUNNING_AFTER_OVERLAY_CLOSED
            invocation?.hostKind == SystemAssistantHostKind.ACTIVITY_OVERLAY ->
                InvocationSurfacePresence.OVERLAY_VISIBLE
            invocation?.hostKind == SystemAssistantHostKind.VOICE_SESSION ->
                InvocationSurfacePresence.VOICE_SESSION_VISIBLE
            else -> InvocationSurfacePresence.RUNNING_AFTER_OVERLAY_CLOSED
        }
        return InvocationSurfaceContext(
            origin = origin,
            hostKind = acceptedRun?.hostKind ?: invocation?.hostKind,
            presence = presence,
            conversationId = conversationId,
            commandId = commandId,
            unlockedOwner = acceptedRun != null ||
                invocation?.let { it.ownerUser && !it.invokedFromKeyguard } == true ||
                origin == ToolCallOrigin.LocalChat,
        )
    }

    fun hasActiveInvocation(): Boolean = invocations.isNotEmpty()

    /** Diagnostic only. Gate decisions must use the conversation-scoped overload. */
    fun hasActiveUnlockedInvocation(): Boolean = invocations.values.any { invocation ->
        invocation.ownerUser && !invocation.invokedFromKeyguard
    }

    /**
     * True only when an unlocked owner-user token is currently bound to [conversationId].
     * Unresolved/unbound sessions never authorize another conversation by accident.
     */
    fun hasActiveUnlockedInvocation(conversationId: Uuid): Boolean =
        invocations.values.any { invocation ->
            invocation.ownerUser &&
                !invocation.invokedFromKeyguard &&
                invocation.conversationId == conversationId
        }

    /**
     * True while either the bound overlay is visible or a command accepted from that visible
     * overlay is still running. Accepted-run authorization is scoped to one conversation and is
     * released by the command outcome; it never makes an old conversation permanently trusted.
     */
    fun hasAuthorizedUnlockedInvocation(
        conversationId: Uuid,
        commandId: Uuid?,
    ): Boolean {
        if (hasActiveUnlockedInvocation(conversationId)) return true
        if (commandId == null) return false
        val now = System.nanoTime()
        acceptedRuns.forEach { (tokenId, run) ->
            if (now >= run.expiresAtNanos) acceptedRuns.remove(tokenId, run)
        }
        return acceptedRuns.values.any { run ->
            run.conversationId == conversationId && run.commandId == commandId
        }
    }

    /**
     * Atomically exchanges a currently visible, unlocked owner invocation for a run lease.
     * Returning null means the overlay disappeared before queue admission.
     */
    fun acquireAcceptedRun(
        conversationId: Uuid,
        commandId: Uuid,
        hostKind: SystemAssistantHostKind = SystemAssistantHostKind.VOICE_SESSION,
    ): SystemAssistantAcceptedRunToken? =
        synchronized(mutationLock) {
            val matchingInvocation = invocations.values.any { invocation ->
                invocation.ownerUser &&
                    !invocation.invokedFromKeyguard &&
                    invocation.conversationId == conversationId &&
                    invocation.hostKind == hostKind
            }
            if (!matchingInvocation) return@synchronized null
            val id = nextTokenId.incrementAndGet()
            acceptedRuns[id] = AcceptedRun(
                conversationId = conversationId,
                commandId = commandId,
                hostKind = hostKind,
                expiresAtNanos = System.nanoTime() + ACCEPTED_RUN_MAX_AGE_NANOS,
            )
            SystemAssistantAcceptedRunToken(id)
        }

    /** Useful for fail-closed diagnostics; keyguard invocations never count as unlocked. */
    fun hasActiveKeyguardInvocation(): Boolean = invocations.values.any { it.invokedFromKeyguard }

    internal fun unregister(tokenId: Long) {
        synchronized(mutationLock) {
            invocations.remove(tokenId)
        }
    }

    internal fun bindConversation(tokenId: Long, conversationId: Uuid?) {
        synchronized(mutationLock) {
            invocations.computeIfPresent(tokenId) { _, invocation ->
                invocation.copy(conversationId = conversationId)
            }
        }
    }

    internal fun releaseAcceptedRun(tokenId: Long) {
        acceptedRuns.remove(tokenId)
    }
}

class SystemAssistantInvocationToken internal constructor(
    private val tokenId: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun bindConversation(conversationId: Uuid) {
        if (!closed.get()) {
            SystemAssistantInvocationRegistry.bindConversation(tokenId, conversationId)
        }
    }

    fun unbindConversation() {
        if (!closed.get()) {
            SystemAssistantInvocationRegistry.bindConversation(tokenId, null)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SystemAssistantInvocationRegistry.unregister(tokenId)
        }
    }
}

class SystemAssistantAcceptedRunToken internal constructor(
    private val tokenId: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SystemAssistantInvocationRegistry.releaseAcceptedRun(tokenId)
        }
    }
}
