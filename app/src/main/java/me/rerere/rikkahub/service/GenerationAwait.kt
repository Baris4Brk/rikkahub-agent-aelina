package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Waits until generation has started and then reaches its terminal null state. */
suspend fun awaitGenerationTerminal(
    generationJobs: Flow<Job?>,
    timeoutMs: Long,
): Boolean = withTimeoutOrNull(timeoutMs) {
    var observedRunningJob = false
    generationJobs.first { job ->
        if (job != null) {
            observedRunningJob = true
            false
        } else {
            observedRunningJob
        }
    }
    true
} ?: false
