package me.rerere.rikkahub.memory.dreaming.runtime

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.memory.dreaming.orchestration.DreamEpochClock

/** Explicit-clock app-background gate. It never reads the Android or JVM system clock. */
fun interface DreamAppIdleTracker {
    fun decisionAt(nowEpochMs: Long, idleThresholdMinutes: Int): DreamAppIdleDecision
}

enum class DreamAppIdleDeferralReason {
    INVALID_REQUEST,
    STATE_UNKNOWN,
    APP_FOREGROUND,
    CLOCK_ROLLBACK,
    THRESHOLD_NOT_REACHED,
}

sealed interface DreamAppIdleDecision {
    data class Eligible(
        val backgroundSinceEpochMs: Long,
        val eligibleAtEpochMs: Long,
    ) : DreamAppIdleDecision

    data class Deferred(
        val reason: DreamAppIdleDeferralReason,
        val nextEligibleAtEpochMs: Long? = null,
    ) : DreamAppIdleDecision
}

/**
 * Thread-safe lifecycle target for a later ProcessLifecycleOwner adapter. A new process starts in
 * UNKNOWN and therefore fails closed until it observes an explicit foreground/background event.
 */
class InMemoryDreamAppIdleTracker : DreamAppIdleTracker {
    private val lock = Any()
    private var state: VisibilityState = VisibilityState.Unknown

    fun onStateUnknown() {
        synchronized(lock) {
            state = VisibilityState.Unknown
        }
    }

    fun onAppForegrounded(nowEpochMs: Long) {
        require(nowEpochMs >= 0L)
        synchronized(lock) {
            state = VisibilityState.Foreground(nowEpochMs)
        }
    }

    fun onAppBackgrounded(nowEpochMs: Long) {
        require(nowEpochMs >= 0L)
        synchronized(lock) {
            state = when (val current = state) {
                is VisibilityState.Background -> if (nowEpochMs >= current.sinceEpochMs) {
                    current
                } else {
                    // A rewound transition clock is not silently accepted as extra idle time.
                    VisibilityState.Unknown
                }

                VisibilityState.Unknown,
                is VisibilityState.Foreground -> VisibilityState.Background(nowEpochMs)
            }
        }
    }

    override fun decisionAt(
        nowEpochMs: Long,
        idleThresholdMinutes: Int,
    ): DreamAppIdleDecision = synchronized(lock) {
        if (nowEpochMs < 0L || idleThresholdMinutes !in
            MIN_DREAMING_IDLE_THRESHOLD_MINUTES..MAX_DREAMING_IDLE_THRESHOLD_MINUTES
        ) {
            return@synchronized DreamAppIdleDecision.Deferred(
                DreamAppIdleDeferralReason.INVALID_REQUEST,
            )
        }
        when (val current = state) {
            VisibilityState.Unknown -> DreamAppIdleDecision.Deferred(
                DreamAppIdleDeferralReason.STATE_UNKNOWN,
            )

            is VisibilityState.Foreground -> DreamAppIdleDecision.Deferred(
                DreamAppIdleDeferralReason.APP_FOREGROUND,
            )

            is VisibilityState.Background -> {
                if (nowEpochMs < current.sinceEpochMs) {
                    DreamAppIdleDecision.Deferred(DreamAppIdleDeferralReason.CLOCK_ROLLBACK)
                } else {
                    val eligibleAt = try {
                        Math.addExact(
                            current.sinceEpochMs,
                            Math.multiplyExact(idleThresholdMinutes.toLong(), 60_000L),
                        )
                    } catch (_: ArithmeticException) {
                        return@synchronized DreamAppIdleDecision.Deferred(
                            DreamAppIdleDeferralReason.INVALID_REQUEST,
                        )
                    }
                    if (nowEpochMs >= eligibleAt) {
                        DreamAppIdleDecision.Eligible(current.sinceEpochMs, eligibleAt)
                    } else {
                        DreamAppIdleDecision.Deferred(
                            reason = DreamAppIdleDeferralReason.THRESHOLD_NOT_REACHED,
                            nextEligibleAtEpochMs = eligibleAt,
                        )
                    }
                }
            }
        }
    }

    private sealed interface VisibilityState {
        data object Unknown : VisibilityState
        data class Foreground(val sinceEpochMs: Long) : VisibilityState
        data class Background(val sinceEpochMs: Long) : VisibilityState
    }
}

/** App-process lifecycle adapter. Every transition is stamped by the injected explicit clock. */
class ProcessLifecycleDreamAppIdleTracker(
    private val clock: DreamEpochClock,
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
    private val delegate: InMemoryDreamAppIdleTracker = InMemoryDreamAppIdleTracker(),
) : DreamAppIdleTracker, DefaultLifecycleObserver {
    init {
        sample { now ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                delegate.onAppForegrounded(now)
            } else {
                delegate.onAppBackgrounded(now)
            }
        }
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        sample(delegate::onAppForegrounded)
    }

    override fun onStop(owner: LifecycleOwner) {
        sample(delegate::onAppBackgrounded)
    }

    override fun decisionAt(nowEpochMs: Long, idleThresholdMinutes: Int): DreamAppIdleDecision =
        delegate.decisionAt(nowEpochMs, idleThresholdMinutes)

    private inline fun sample(apply: (Long) -> Unit) {
        val now = try {
            clock.nowEpochMs()
        } catch (_: Exception) {
            delegate.onStateUnknown()
            return
        }
        if (now >= 0L) apply(now) else delegate.onStateUnknown()
    }
}

/** Safe fallback used until lifecycle state is wired: every synthesis request is deferred. */
object UnknownDreamAppIdleTracker : DreamAppIdleTracker {
    override fun decisionAt(nowEpochMs: Long, idleThresholdMinutes: Int): DreamAppIdleDecision =
        DreamAppIdleDecision.Deferred(DreamAppIdleDeferralReason.STATE_UNKNOWN)
}
