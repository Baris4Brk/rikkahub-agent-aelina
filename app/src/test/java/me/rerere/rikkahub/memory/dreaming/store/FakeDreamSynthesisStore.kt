package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.DreamingTestFixtures
import me.rerere.rikkahub.memory.dreaming.input.DreamInputBuildRequest
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisFence

internal class FakeDreamSynthesisStore(
    private val fence: DreamSynthesisFence,
    var seed: DreamInputBuildRequest,
) : DreamSynthesisStore {
    var transactionOpen: Boolean = false
        private set
    var commitResult: DreamSynthesisCommitResult = DreamSynthesisCommitResult.Committed(
        DreamingTestFixtures.SNAPSHOT_ID,
        fence.baseDreamRevision + 1,
    )
    var commitException: Exception? = null
    var beginOverride: BeginDreamSynthesisResult? = null
    var readRejection: DreamSynthesisStoreRejection? = null
    var heartbeatResult: DreamSynthesisStoreResult = DreamSynthesisStoreResult.Accepted
    var failResult: DreamSynthesisStoreResult = DreamSynthesisStoreResult.Accepted
    var dispatchResult: DreamSynthesisStoreResult = DreamSynthesisStoreResult.Accepted
    val commits = mutableListOf<DreamSynthesisCommitRequest>()
    val dispatches = mutableListOf<DreamProviderDispatchRequest>()
    val conflicts = mutableListOf<DreamSynthesisCommitRejection>()
    val failures = mutableListOf<DreamSynthesisFailure>()
    var heartbeatCount: Int = 0
        private set

    override suspend fun begin(request: BeginDreamSynthesisRequest): BeginDreamSynthesisResult = transaction {
        beginOverride ?: BeginDreamSynthesisResult.Ready(fence)
    }

    override suspend fun readInputSeed(
        fence: DreamSynthesisFence,
        attemptNowEpochMs: Long,
    ): ReadDreamInputSeedResult = transaction {
        if (readRejection != null) ReadDreamInputSeedResult.Rejected(readRejection!!)
        else if (fence == this.fence && attemptNowEpochMs >= fence.frozenNowEpochMs) {
            ReadDreamInputSeedResult.Ready(seed)
        } else ReadDreamInputSeedResult.Rejected(DreamSynthesisStoreRejection.FENCE_CONFLICT)
    }

    override suspend fun heartbeat(
        fence: DreamSynthesisFence,
        nowMs: Long,
        leaseDurationMs: Long,
    ): DreamSynthesisStoreResult = transaction {
        heartbeatCount++
        if (fence == this.fence && nowMs >= 0L && leaseDurationMs > 0L) {
            heartbeatResult
        } else {
            DreamSynthesisStoreResult.Rejected(DreamSynthesisStoreRejection.FENCE_CONFLICT)
        }
    }

    override suspend fun markProviderDispatch(
        request: DreamProviderDispatchRequest,
    ): DreamSynthesisStoreResult = transaction {
        dispatches += request
        dispatchResult
    }

    override suspend fun commit(request: DreamSynthesisCommitRequest): DreamSynthesisCommitResult = transaction {
        commitException?.let { throw it }
        commits += request
        commitResult
    }

    override suspend fun terminalizeConflict(
        fence: DreamSynthesisFence,
        reason: DreamSynthesisCommitRejection,
        nowMs: Long,
    ): DreamSynthesisStoreResult = transaction {
        conflicts += reason
        DreamSynthesisStoreResult.Accepted
    }

    override suspend fun fail(
        fence: DreamSynthesisFence,
        failure: DreamSynthesisFailure,
        nowMs: Long,
    ): DreamSynthesisStoreResult = transaction {
        failures += failure
        failResult
    }

    private inline fun <T> transaction(block: () -> T): T {
        check(!transactionOpen)
        transactionOpen = true
        return try {
            block()
        } finally {
            transactionOpen = false
        }
    }
}
