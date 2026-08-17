package me.rerere.rikkahub.data.ai

/**
 * Provider-bound observations that can be made by the application itself.
 *
 * None of these events proves that a remote transport received every byte, that a model read the
 * request, or that it followed any contextual advice. [HostDispatched] means only that the app is
 * about to invoke the provider adapter. Progress and response events are likewise proxy signals
 * visible at the adapter boundary.
 */
sealed interface ProviderAttemptEvent {
    /** One-based ordinal within one logical provider run. A retry receives a new ordinal. */
    val attemptOrdinal: Int

    data class HostDispatched(
        override val attemptOrdinal: Int,
        val stream: Boolean,
    ) : ProviderAttemptEvent {
        init {
            require(attemptOrdinal > 0) { "Provider attempt ordinal must be positive" }
        }
    }

    data class FirstProgress(
        override val attemptOrdinal: Int,
        val kind: ProviderProgressKind,
    ) : ProviderAttemptEvent {
        init {
            require(attemptOrdinal > 0) { "Provider attempt ordinal must be positive" }
        }
    }

    data class ResponseFinished(
        override val attemptOrdinal: Int,
    ) : ProviderAttemptEvent {
        init {
            require(attemptOrdinal > 0) { "Provider attempt ordinal must be positive" }
        }
    }

    data class Terminal(
        override val attemptOrdinal: Int,
        val outcome: ProviderAttemptTerminalOutcome,
    ) : ProviderAttemptEvent {
        init {
            require(attemptOrdinal > 0) { "Provider attempt ordinal must be positive" }
        }
    }
}

/**
 * Terminal fact for one provider attempt. It is deliberately separate from Policy exposure
 * milestones and from the later authoritative Conversation/Command outcome link.
 */
enum class ProviderAttemptTerminalOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
    STEERING_CANCELLED,
    /** A watchdog ended this attempt and a fresh attempt ordinal will be reserved. */
    STALLED_RETRY,
}

/**
 * Generic provider-attempt observation port. It contains no Learning or persistence policy.
 *
 * Callers must invoke this port fail-open: an observer exception makes attribution UNKNOWN for
 * that attempt but must never cancel a provider call, dispatch a fallback request, or alter the
 * provider result. Coroutine cancellation remains owned by the generation caller.
 */
fun interface ProviderAttemptObserver {
    suspend fun observe(event: ProviderAttemptEvent)

    /**
     * Fenced pre-dispatch preparation for [attemptOrdinal]. Returning false forbids that attempt
     * from reaching the provider adapter. The primary attempt normally has already been reserved;
     * watchdog retries use this boundary to durably reserve their new ordinal before bytes can be
     * sent. Non-Learning observers may keep the default.
     */
    suspend fun prepareForDispatch(attemptOrdinal: Int, isRetry: Boolean): Boolean = true

    companion object {
        val NONE: ProviderAttemptObserver = ProviderAttemptObserver { }
    }
}
