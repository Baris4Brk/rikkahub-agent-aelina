package me.rerere.rikkahub.pet.behavior

import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.DefaultPetActionResolver
import me.rerere.rikkahub.pet.action.PetActionId
import me.rerere.rikkahub.pet.action.PetActionProfile
import me.rerere.rikkahub.pet.action.PetActionResolver

/**
 * The one writer for desktop-pet behaviour. Runtime state, interactions, speech and dialogue
 * submit semantic intents here; a renderer observes [state] and is the only layer that draws.
 *
 * This deliberately keeps leases in memory. A service restart restores only the latest
 * operational state and never revives an expired touch, speech, or completion animation.
 */
class PetBehaviorOrchestrator(
    private val scope: CoroutineScope,
    initialProfile: PetActionProfile = PetActionProfile.standard(),
    private val resolver: PetActionResolver = DefaultPetActionResolver(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val traceSink: (PetActionTrace) -> Unit = {},
) : Closeable {
    private val lock = Any()
    private var profile = initialProfile
    private val operational = mutableMapOf<PetActionSource, PetActionRequest>()
    private val queued = mutableListOf<PetActionRequest>()
    private var active: PetActionRequest? = null
    private var expiryJob: Job? = null

    private val _state = MutableStateFlow(initialState(initialProfile))
    val state: StateFlow<PetBehaviorState> = _state.asStateFlow()

    fun submit(intent: PetBehaviorIntent) {
        synchronized(lock) {
            when (intent) {
                is PetBehaviorIntent.Operational -> submitOperational(intent)
                is PetBehaviorIntent.OneShot -> submitOneShot(intent)
                is PetBehaviorIntent.Sequence -> submitSequence(intent)
                is PetBehaviorIntent.ClearSource -> clearSourceLocked(intent.source)
                PetBehaviorIntent.ClearTransient -> clearTransientLocked()
            }
        }
    }

    /** Applies an already-validated profile without changing logical behaviour or leases. */
    fun updateProfile(nextProfile: PetActionProfile) {
        synchronized(lock) {
            profile = nextProfile
            publishLocked()
        }
    }

    /** Removes an active or queued one-shot, for example after an explicit audio cancellation. */
    fun cancel(requestId: String) {
        synchronized(lock) {
            if (active?.requestId == requestId) {
                expiryJob?.cancel()
                active = null
                startNextOrPublishLocked()
                return
            }
            val removed = queued.removeAll { it.requestId == requestId || it.parentRequestId == requestId }
            if (removed) publishLocked()
        }
    }

    private fun submitOperational(intent: PetBehaviorIntent.Operational) {
        val previous = operational[intent.source]
        if (previous?.requestedAction == intent.action && previous.priority == intent.priority) {
            traceLocked(
                requested = intent.action,
                source = intent.source,
                priority = intent.priority,
                accepted = false,
                reason = "duplicate_operational_state",
            )
            return
        }
        operational[intent.source] = newRequest(
            action = intent.action,
            source = intent.source,
            priority = intent.priority,
            persistent = true,
            minDurationMs = 0L,
            maxDurationMs = null,
        )
        active?.takeIf { it.priority.rank < intent.priority.rank }?.let {
            expiryJob?.cancel()
            active = null
            // Do not play an old low-priority interaction when approval/safety clears later.
            queued.removeAll { queuedRequest -> queuedRequest.priority.rank < intent.priority.rank }
        }
        startNextOrPublishLocked()
    }

    private fun submitOneShot(intent: PetBehaviorIntent.OneShot) {
        val request = newRequest(
            action = intent.action,
            source = intent.source,
            priority = intent.priority,
            persistent = intent.persistent,
            minDurationMs = intent.minDurationMs,
            maxDurationMs = intent.maxDurationMs,
        )
        submitTransientLocked(request)
    }

    private fun submitSequence(intent: PetBehaviorIntent.Sequence) {
        if (intent.steps.isEmpty()) return
        val parentId = UUID.randomUUID().toString()
        val requests = intent.steps.map { step ->
            newRequest(
                action = step.action,
                source = intent.source,
                priority = intent.priority,
                persistent = false,
                minDurationMs = step.minDurationMs,
                maxDurationMs = step.maxDurationMs,
                parentRequestId = parentId,
            )
        }
        requests.forEach(::submitTransientLocked)
    }

    private fun submitTransientLocked(request: PetActionRequest) {
        require(!request.persistent || request.maxDurationMs == null) { "persistent_oneshot_must_not_expire" }
        require(request.minDurationMs >= 0L) { "pet_action_min_duration_invalid" }
        require(request.maxDurationMs == null || request.maxDurationMs >= request.minDurationMs) {
            "pet_action_max_duration_invalid"
        }
        operationalWinnerLocked()?.takeIf { it.priority.rank > request.priority.rank }?.let { winner ->
            traceLocked(
                request = request,
                accepted = false,
                reason = "preempted_by_${winner.source.name.lowercase()}_state",
            )
            return
        }
        val current = active
        if (current == null) {
            activateLocked(request)
            return
        }
        when {
            request.priority.rank > current.priority.rank -> {
                expiryJob?.cancel()
                active = null
                // A pre-empted lease is intentionally discarded. Returning to the latest
                // operational state avoids replaying stale touch or completion feedback.
                activateLocked(request)
            }
            request.priority.rank == current.priority.rank &&
                request.source == current.source && request.requestedAction == current.requestedAction -> {
                traceLocked(
                    requested = request.requestedAction,
                    source = request.source,
                    priority = request.priority,
                    accepted = false,
                    reason = "duplicate_active_lease",
                )
            }
            else -> {
                queued += request
                traceLocked(
                    request = request,
                    accepted = true,
                    reason = "queued_behind_active_lease",
                )
                publishLocked()
            }
        }
    }

    private fun activateLocked(request: PetActionRequest) {
        active = request
        val resolved = resolver.resolve(request.requestedAction, profile)
        traceLocked(request = request, accepted = true, resolved = resolved)
        publishLocked(resolved)
        expiryJob?.cancel()
        val max = request.maxDurationMs
        if (!request.persistent && max != null) {
            expiryJob = scope.launch {
                delay(max)
                synchronized(lock) {
                    if (active?.requestId == request.requestId) {
                        active = null
                        startNextOrPublishLocked()
                    }
                }
            }
        }
    }

    private fun clearSourceLocked(source: PetActionSource) {
        operational.remove(source)
        val activeWasRemoved = active?.source == source
        if (activeWasRemoved) {
            expiryJob?.cancel()
            active = null
        }
        val queuedRemoved = queued.removeAll { it.source == source }
        if (activeWasRemoved || queuedRemoved) startNextOrPublishLocked() else publishLocked()
    }

    private fun clearTransientLocked() {
        expiryJob?.cancel()
        active = null
        queued.clear()
        publishLocked()
    }

    private fun startNextOrPublishLocked() {
        val operationalWinner = operationalWinnerLocked()
        val next = queued
            .withIndex()
            .maxWithOrNull(compareBy<IndexedValue<PetActionRequest>> { it.value.priority.rank }.thenBy { -it.index })
            ?.takeIf { indexed ->
                operationalWinner == null || indexed.value.priority.rank >= operationalWinner.priority.rank
            }
            ?.also { queued.removeAt(it.index) }
            ?.value
        if (next != null) activateLocked(next) else publishLocked()
    }

    private fun publishLocked(preResolved: me.rerere.rikkahub.pet.action.ResolvedPetAction? = null) {
        val operationalWinner = operationalWinnerLocked()
        val displayRequest = active ?: operationalWinner ?: idleRequest()
        val resolved = preResolved ?: resolver.resolve(displayRequest.requestedAction, profile)
        _state.value = PetBehaviorState(
            operationalAction = operationalWinner,
            activeOneShot = active,
            queuedOneShots = queued.toList(),
            displayedAction = resolved,
            activeProfileId = profile.profileId,
            activeRendererType = profile.rendererType,
            lastTransitionAtMs = nowMs(),
        )
    }

    private fun operationalWinnerLocked(): PetActionRequest? = operational.values.maxWithOrNull(
        compareBy<PetActionRequest> { it.priority.rank }.thenBy { it.createdAtMs },
    )

    private fun initialState(initialProfile: PetActionProfile): PetBehaviorState {
        val idle = resolver.resolve(CorePetActions.IDLE, initialProfile)
        return PetBehaviorState(
            operationalAction = null,
            activeOneShot = null,
            queuedOneShots = emptyList(),
            displayedAction = idle,
            activeProfileId = initialProfile.profileId,
            activeRendererType = initialProfile.rendererType,
            lastTransitionAtMs = nowMs(),
        )
    }

    private fun idleRequest(): PetActionRequest = PetActionRequest(
        requestId = "idle",
        requestedAction = CorePetActions.IDLE,
        source = PetActionSource.DEBUG,
        priority = PetBehaviorPriority.IDLE,
        persistent = true,
        minDurationMs = 0L,
        maxDurationMs = null,
        returnPolicy = PetReturnPolicy.ResolveLatestOperationalState,
        createdAtMs = nowMs(),
    )

    private fun newRequest(
        action: PetActionId,
        source: PetActionSource,
        priority: PetBehaviorPriority,
        persistent: Boolean,
        minDurationMs: Long,
        maxDurationMs: Long?,
        parentRequestId: String? = null,
    ): PetActionRequest = PetActionRequest(
        requestId = UUID.randomUUID().toString(),
        requestedAction = action,
        source = source,
        priority = priority,
        persistent = persistent,
        minDurationMs = minDurationMs,
        maxDurationMs = maxDurationMs,
        returnPolicy = PetReturnPolicy.ResolveLatestOperationalState,
        createdAtMs = nowMs(),
        parentRequestId = parentRequestId,
    )

    private fun traceLocked(
        request: PetActionRequest,
        accepted: Boolean,
        reason: String? = null,
        resolved: me.rerere.rikkahub.pet.action.ResolvedPetAction? = null,
    ) = traceLocked(
        requested = request.requestedAction,
        source = request.source,
        priority = request.priority,
        accepted = accepted,
        reason = reason,
        resolved = resolved,
    )

    private fun traceLocked(
        requested: PetActionId,
        source: PetActionSource,
        priority: PetBehaviorPriority,
        accepted: Boolean,
        reason: String? = null,
        resolved: me.rerere.rikkahub.pet.action.ResolvedPetAction? = null,
    ) {
        traceSink(
            PetActionTrace(
                traceId = UUID.randomUUID().toString(),
                requestedAction = requested,
                resolvedAction = resolved?.resolvedAction,
                source = source,
                priority = priority,
                accepted = accepted,
                rejectionReason = reason,
                fallbackPath = resolved?.fallbackPath.orEmpty(),
                createdAtMs = nowMs(),
            ),
        )
    }

    override fun close() {
        synchronized(lock) {
            expiryJob?.cancel()
            expiryJob = null
            active = null
            queued.clear()
            operational.clear()
        }
    }
}
