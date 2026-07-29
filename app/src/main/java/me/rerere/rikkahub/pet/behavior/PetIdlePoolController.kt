package me.rerere.rikkahub.pet.behavior

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.PetActionId
import me.rerere.rikkahub.pet.action.PetActionProfile

/**
 * Optional, local-only idle variety. The selector never runs during work, approval, speaking,
 * touch, hidden screen, power save or low-battery conditions, and is disabled by default.
 */
class PetIdlePoolController(
    private val scope: CoroutineScope,
    private val behavior: PetBehaviorOrchestrator,
    private val randomUnit: () -> Double = Math::random,
) : Closeable {
    private var enabledByUser = false
    private var screenVisible = false
    private var trueIdle = false
    private var powerSave = false
    private var lowBattery = false
    private var profile: PetActionProfile = PetActionProfile.standard()
    private var scheduled: Job? = null
    private val observation: Job = scope.launch {
        behavior.state.collectLatest { reevaluate(it) }
    }

    fun setConditions(
        enabledByUser: Boolean,
        screenVisible: Boolean,
        trueIdle: Boolean,
        powerSave: Boolean,
        lowBattery: Boolean,
    ) {
        this.enabledByUser = enabledByUser
        this.screenVisible = screenVisible
        this.trueIdle = trueIdle
        this.powerSave = powerSave
        this.lowBattery = lowBattery
        reevaluate(behavior.state.value)
    }

    fun updateProfile(profile: PetActionProfile) {
        this.profile = profile
        reevaluate(behavior.state.value)
    }

    private fun reevaluate(state: PetBehaviorState) {
        val mayRun = enabledByUser && screenVisible && trueIdle && !powerSave && !lowBattery &&
            state.activeOneShot == null &&
            state.operationalAction?.requestedAction == CorePetActions.IDLE
        if (!mayRun) {
            scheduled?.cancel()
            scheduled = null
            if (state.activeOneShot?.source == PetActionSource.AUTONOMOUS ||
                state.queuedOneShots.any { it.source == PetActionSource.AUTONOMOUS }
            ) {
                behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.AUTONOMOUS))
            }
            return
        }
        if (scheduled?.isActive == true) return
        val config = profile.idlePool ?: defaultConfig()
        val delayMs = config.minIntervalMs + (randomUnit().coerceIn(0.0, 1.0) * config.minIntervalMs).toLong()
        scheduled = scope.launch {
            delay(delayMs)
            scheduled = null
            val latest = behavior.state.value
            if (enabledByUser && screenVisible && trueIdle && !powerSave && !lowBattery &&
                latest.activeOneShot == null && latest.operationalAction?.requestedAction == CorePetActions.IDLE
            ) {
                behavior.submit(
                    PetBehaviorIntent.OneShot(
                        action = choose(config.weights),
                        source = PetActionSource.AUTONOMOUS,
                        priority = PetBehaviorPriority.AUTONOMOUS,
                        minDurationMs = 700L,
                        maxDurationMs = 1_500L,
                    ),
                )
            }
        }
    }

    private fun choose(weights: Map<PetActionId, Int>): PetActionId {
        val total = weights.values.sum().coerceAtLeast(1)
        var cursor = randomUnit().coerceIn(0.0, 0.999999) * total
        weights.forEach { (action, weight) ->
            cursor -= weight
            if (cursor < 0) return action
        }
        return weights.keys.first()
    }

    private fun defaultConfig() = me.rerere.rikkahub.pet.action.PetIdlePoolConfig(
        weights = linkedMapOf(
            CorePetActions.IDLE to 70,
            CorePetActions.REVIEW to 20,
            CorePetActions.WAVE to 10,
        ),
    )

    override fun close() {
        scheduled?.cancel()
        observation.cancel()
        val state = behavior.state.value
        if (state.activeOneShot?.source == PetActionSource.AUTONOMOUS ||
            state.queuedOneShots.any { it.source == PetActionSource.AUTONOMOUS }
        ) {
            behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.AUTONOMOUS))
        }
    }
}
