package me.rerere.rikkahub.learning.jobs

import androidx.room.withTransaction
import me.rerere.rikkahub.data.ai.background.BackgroundProviderAttemptAuthority
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobErrorCode
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.LearningProviderAttemptEntity
import me.rerere.rikkahub.learning.storage.LearningProviderAttemptState
import me.rerere.rikkahub.learning.storage.LearningProviderBudgetState
import me.rerere.rikkahub.learning.storage.LearningProviderConfigCohortEntity
import me.rerere.rikkahub.learning.storage.LearningProviderDispatchKnowledge
import me.rerere.rikkahub.learning.storage.LearningProviderJobManifestEntity
import me.rerere.rikkahub.learning.storage.LearningProviderTerminalOutcome

internal class RoomLearningJobStore(
    private val database: LearningDatabase,
    private val authorityClock: LearningJobClock = SystemLearningJobClock,
) : LearningJobStore {
    override suspend fun claim(
        processSessionId: String,
        workerId: String,
        nowMs: Long,
        leaseDurationMs: Long,
        eligibleJobTypes: Set<LearningJobType>,
    ): LearningJobClaimResult {
        requireUuid(processSessionId, "process session")
        requireUuid(workerId, "worker")
        requireValidNow(nowMs)
        require(leaseDurationMs > 0L) { "Lease duration must be positive" }
        require(eligibleJobTypes.isNotEmpty()) { "No eligible learning job type" }
        val leaseUntilMs = checkedAdd(nowMs, leaseDurationMs)
        val eligibleTypeNames = eligibleJobTypes.map(LearningJobType::name).sorted()

        return database.withTransaction {
            val dao = database.jobDao()
            dao.findActiveClockRollbackCandidate(nowMs, eligibleTypeNames)?.let { future ->
                return@withTransaction LearningJobClaimResult.ClockRollback(future.id)
            }
            // A crashed final attempt must not remain RUNNING forever, and an already-exhausted
            // pending/retry row must never be selected. Fence all exhausted active rows first.
            dao.deadLetterAllExhausted(
                nowMs = nowMs,
                errorCode = LearningJobErrorCode.ATTEMPTS_EXHAUSTED,
            )
            var invalidProviderJobs = 0
            while (invalidProviderJobs < MAX_INVALID_PROVIDER_JOBS_PER_CLAIM) {
                val candidate = dao.findClaimCandidate(nowMs, eligibleTypeNames)
                    ?: return@withTransaction LearningJobClaimResult.NoWork
                when (val admission = providerAdmission(candidate, nowMs)) {
                    ProviderAdmission.NotProvider -> return@withTransaction claimCandidate(
                        candidate = candidate,
                        processSessionId = processSessionId,
                        workerId = workerId,
                        nowMs = nowMs,
                        leaseUntilMs = leaseUntilMs,
                        eligibleTypeNames = eligibleTypeNames,
                        providerAdmission = null,
                    )

                    is ProviderAdmission.BudgetExhausted -> {
                        val deferred = dao.deferUnclaimedFenced(
                            id = candidate.id,
                            expectedGeneration = candidate.leaseGeneration,
                            expectedUpdatedAtMs = candidate.updatedAtMs,
                            nowMs = nowMs,
                            notBeforeMs = admission.notBeforeMs,
                            errorCode = LearningJobErrorCode.WAITING_BUDGET,
                        )
                        if (deferred != 1) {
                            return@withTransaction LearningJobClaimResult.Contended
                        }
                        invalidProviderJobs += 1
                    }

                    is ProviderAdmission.ClockRollback -> {
                        return@withTransaction LearningJobClaimResult.ClockRollback(admission.jobId)
                    }

                    ProviderAdmission.Invalid -> {
                        if (!deadLetterCandidateForInvalidManifest(
                                candidate = candidate,
                                processSessionId = processSessionId,
                                workerId = workerId,
                                nowMs = nowMs,
                                leaseUntilMs = leaseUntilMs,
                                eligibleTypeNames = eligibleTypeNames,
                            )
                        ) {
                            return@withTransaction LearningJobClaimResult.Contended
                        }
                        invalidProviderJobs += 1
                    }

                    is ProviderAdmission.Accepted -> return@withTransaction claimCandidate(
                        candidate = candidate,
                        processSessionId = processSessionId,
                        workerId = workerId,
                        nowMs = nowMs,
                        leaseUntilMs = leaseUntilMs,
                        eligibleTypeNames = eligibleTypeNames,
                        providerAdmission = admission,
                    )
                }
            }
            LearningJobClaimResult.NoWork
        }
    }

    override suspend fun heartbeat(
        lease: LearningJobLease,
        clock: LearningJobClock,
        leaseDurationMs: Long,
    ): LearningJobLease {
        require(leaseDurationMs > 0L) { "Lease duration must be positive" }

        return database.withTransaction {
            val dao = database.jobDao()
            val issued = lease.requireIssuedLease()
            val fence = issued.fence
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val commitNowMs = checkedNow(clock)
            if (commitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, commitNowMs)
            val requestedLeaseUntilMs = checkedAdd(commitNowMs, leaseDurationMs)

            val currentLeaseUntilMs = requireNotNull(current.leaseUntilMs)
            if (requestedLeaseUntilMs <= currentLeaseUntilMs) {
                issued.providerContext?.let { context ->
                    database.requireHeartbeatCompatibleAttempt(
                        context = context,
                        currentJobLeaseUntilMs = currentLeaseUntilMs,
                        requestedLeaseUntilMs = null,
                        nowMs = commitNowMs,
                    )
                }
                return@withTransaction issueLearningJobLease(current, issued.providerContext)
            }

            issued.providerContext?.let { context ->
                database.requireHeartbeatCompatibleAttempt(
                    context = context,
                    currentJobLeaseUntilMs = currentLeaseUntilMs,
                    requestedLeaseUntilMs = requestedLeaseUntilMs,
                    nowMs = commitNowMs,
                )
            }

            val changed = dao.heartbeatExtendingOnly(
                id = fence.jobId,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.generation,
                nowMs = commitNowMs,
                leaseUntilMs = requestedLeaseUntilMs,
            )
            if (changed != 1) throw LearningLostLeaseException()

            val extended = dao.findById(fence.jobId) ?: throw LearningJobInvariantException()
            if (!extended.isOwnedBy(fence) || extended.leaseUntilMs != requestedLeaseUntilMs) {
                throw LearningJobInvariantException()
            }
            issueLearningJobLease(extended, issued.providerContext)
        }
    }

    override suspend fun completeTyped(
        lease: LearningJobLease,
        clock: LearningJobClock,
        completion: PreparedLearningJobCompletion,
    ) {
        database.withTransaction {
            val dao = database.jobDao()
            val issued = lease.requireIssuedLease()
            val fence = issued.fence
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val frozenCommitNowMs = checkedNow(clock)
            if (frozenCommitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, frozenCommitNowMs)

            issued.providerContext?.let { context ->
                database.requireSuccessfulTerminalAttempt(context, frozenCommitNowMs)
            }

            // The typed output writer and terminal CAS share this Room transaction. Any writer
            // failure or affectedRows != 1 rolls both back, so a stale worker leaves no output.
            completion.persistInOpenTransaction(database, current)
            val changed = dao.finishDone(
                id = fence.jobId,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.generation,
                nowMs = frozenCommitNowMs,
            )
            if (changed != 1) throw LearningLostLeaseException()
        }
    }

    override suspend fun failAttempt(
        lease: LearningJobLease,
        clock: LearningJobClock,
        retryDelayMs: Long,
        errorCode: LearningJobFailureCode,
    ): LearningJobAttemptFailureResult {
        require(retryDelayMs >= 0L) { "Negative retry delay" }

        return database.withTransaction {
            val dao = database.jobDao()
            val issued = lease.requireIssuedLease()
            val fence = issued.fence
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val commitNowMs = checkedNow(clock)
            if (commitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, commitNowMs)
            val notBeforeMs = checkedAdd(commitNowMs, retryDelayMs)
            val providerRetryIsSafe = issued.providerContext?.let { context ->
                database.releaseAndProveUndispatchedForRetry(context, commitNowMs)
            } ?: true

            val shouldDeadLetter = current.attempts >= current.maxAttempts || !providerRetryIsSafe
            val changed = if (shouldDeadLetter) {
                dao.finishDeadLetter(
                    id = fence.jobId,
                    processSessionId = fence.processSessionId,
                    workerId = fence.workerId,
                    leaseGeneration = fence.generation,
                    nowMs = commitNowMs,
                    errorCode = if (current.attempts >= current.maxAttempts) {
                        LearningJobErrorCode.ATTEMPTS_EXHAUSTED
                    } else {
                        errorCode.persistedCode
                    },
                )
            } else {
                dao.retry(
                    id = fence.jobId,
                    processSessionId = fence.processSessionId,
                    workerId = fence.workerId,
                    leaseGeneration = fence.generation,
                    nowMs = commitNowMs,
                    notBeforeMs = notBeforeMs,
                    errorCode = errorCode.persistedCode,
                )
            }
            if (changed != 1) throw LearningLostLeaseException()
            if (shouldDeadLetter) {
                LearningJobAttemptFailureResult.DEAD_LETTERED
            } else {
                LearningJobAttemptFailureResult.RETRIED
            }
        }
    }

    override suspend fun failPermanently(
        lease: LearningJobLease,
        clock: LearningJobClock,
        errorCode: LearningJobFailureCode,
    ) {
        database.withTransaction {
            val dao = database.jobDao()
            val issued = lease.requireIssuedLease()
            val fence = issued.fence
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val commitNowMs = checkedNow(clock)
            if (commitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, commitNowMs)
            issued.providerContext?.let { context ->
                database.releaseReservationBeforePermanentFailure(context, commitNowMs)
            }
            val changed = dao.finishDeadLetter(
                id = fence.jobId,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.generation,
                nowMs = commitNowMs,
                errorCode = errorCode.persistedCode,
            )
            if (changed != 1) throw LearningLostLeaseException()
        }
    }

    override suspend fun recoverOnStartup(
        currentProcessSessionId: String,
        nowMs: Long,
        retryDelayMs: Long,
    ): LearningJobStartupRecoveryResult {
        requireUuid(currentProcessSessionId, "process session")
        requireValidNow(nowMs)
        require(retryDelayMs >= 0L) { "Negative retry delay" }
        val notBeforeMs = checkedAdd(nowMs, retryDelayMs)

        return database.withTransaction {
            val dao = database.jobDao()
            val providerDao = database.providerExecutionDao()
            dao.findActiveClockRollbackCandidate(
                nowMs = nowMs,
                eligibleJobTypes = LearningJobType.entries.map(LearningJobType::name),
            )?.let { future ->
                return@withTransaction LearningJobStartupRecoveryResult.ClockRollback(future.id)
            }
            if ((providerDao.maxAttemptUpdatedAtMs() ?: nowMs) > nowMs) {
                return@withTransaction LearningJobStartupRecoveryResult.ClockRollback(
                    PROVIDER_CLOCK_HIGH_WATER_ID,
                )
            }

            val unfinished = providerDao.listUnfinishedAttempts(MAX_PROVIDER_RECOVERY_ROWS + 1)
            if (unfinished.size > MAX_PROVIDER_RECOVERY_ROWS) {
                throw LearningJobInvariantException()
            }
            val orphaned = unfinished.filter { attempt ->
                attempt.leaseUntilMs <= nowMs ||
                    attempt.leaseProcessSessionId != currentProcessSessionId
            }
            val orphanedDispatches = orphaned.filter { attempt ->
                attempt.state == LearningProviderAttemptState.DISPATCH_STARTED.name
            }

            // Provider facts are recovered before jobs. A proven NOT_DISPATCHED reservation may
            // return to the retry path; a possibly-dispatched attempt becomes indeterminate and
            // its job is fenced terminal before generic lease recovery can make it RETRY.
            val releasedReservations = providerDao.releaseOrphanedUndispatchedReservations(
                currentProcessSessionId = currentProcessSessionId,
                nowMs = nowMs,
            )
            val indeterminateDispatches = providerDao.markOrphanedDispatchesIndeterminate(
                currentProcessSessionId = currentProcessSessionId,
                nowMs = nowMs,
            )
            if (
                releasedReservations != orphaned.count {
                    it.state == LearningProviderAttemptState.RESERVED.name
                } || indeterminateDispatches != orphanedDispatches.size
            ) {
                throw LearningJobInvariantException()
            }

            val requeuedMandatoryInvalidations =
                dao.requeueExhaustedMandatorySourceInvalidations(
                    nowMs = nowMs,
                    notBeforeMs = notBeforeMs,
                    limit = MAX_MANDATORY_INVALIDATION_STARTUP_REPAIRS,
                )
            val exhausted = dao.deadLetterAllExhausted(
                nowMs = nowMs,
                errorCode = LearningJobErrorCode.ATTEMPTS_EXHAUSTED,
            )
            var providerJobsDeadLettered = 0
            orphanedDispatches.forEach { attempt ->
                val job = dao.findById(attempt.jobId) ?: return@forEach
                if (database.deadLetterActiveJobOnStartup(
                        job = job,
                        nowMs = nowMs,
                        errorCode = LearningJobErrorCode.INTERNAL,
                    )
                ) {
                    providerJobsDeadLettered += 1
                }
            }

            val missingManifestJobs = providerDao.listActiveProviderJobsMissingManifest(
                MAX_PROVIDER_RECOVERY_ROWS + 1,
            )
            if (missingManifestJobs.size > MAX_PROVIDER_RECOVERY_ROWS) {
                throw LearningJobInvariantException()
            }
            var missingManifestJobsDeadLettered = 0
            missingManifestJobs.forEach { job ->
                val current = dao.findById(job.id) ?: return@forEach
                if (database.deadLetterActiveJobOnStartup(
                        job = current,
                        nowMs = nowMs,
                        errorCode = LearningJobErrorCode.INVALID_JOB_SPEC,
                    )
                ) {
                    missingManifestJobsDeadLettered += 1
                }
            }

            // Only released, provably undispatched provider attempts can reach these generic
            // recovery updates. Dispatched/indeterminate jobs were fenced above.
            val otherSessions = dao.recoverOtherProcessSessions(
                currentProcessSessionId = currentProcessSessionId,
                nowMs = nowMs,
                notBeforeMs = notBeforeMs,
                errorCode = LearningJobErrorCode.LOST_LEASE,
            )
            val expired = dao.recoverExpired(
                nowMs = nowMs,
                notBeforeMs = notBeforeMs,
                errorCode = LearningJobErrorCode.LEASE_EXPIRED,
            )
            LearningJobStartupRecoveryResult.Recovered(
                otherProcessSessions = otherSessions,
                expiredLeases = expired,
                exhaustedAttempts = exhausted,
                orphanReservationsReleased = releasedReservations,
                orphanDispatchesIndeterminate = indeterminateDispatches,
                providerJobsDeadLettered = providerJobsDeadLettered,
                missingManifestJobsDeadLettered = missingManifestJobsDeadLettered,
                requeuedMandatoryInvalidations = requeuedMandatoryInvalidations,
            )
        }
    }

    override suspend fun cancelAllActive(nowMs: Long): Int {
        requireValidNow(nowMs)
        return database.withTransaction {
            val dao = database.jobDao()
            // Cancellation must still fence stale workers during a wall-clock rollback. Preserve
            // the database's monotonic authority timestamp rather than writing time backwards.
            val authorityNowMs = maxOf(nowMs, dao.maxActiveUpdatedAt() ?: nowMs)
            dao.cancelAllActive(
                nowMs = authorityNowMs,
                errorCode = LearningJobErrorCode.CANCELLED_BY_RESET,
            )
        }
    }

    private suspend fun providerAdmission(
        candidate: LearningJobEntity,
        nowMs: Long,
    ): ProviderAdmission {
        if (!candidate.isProviderEffectJob()) return ProviderAdmission.NotProvider
        val providerKind = candidate.providerKindIdentity ?: return ProviderAdmission.Invalid
        if (providerKind != PROVIDER_KIND_LOCAL && providerKind != PROVIDER_KIND_REMOTE) {
            return ProviderAdmission.Invalid
        }

        val providerDao = database.providerExecutionDao()
        val maxAttemptClock = providerDao.maxAttemptUpdatedAtMs()
        if (maxAttemptClock != null && maxAttemptClock > nowMs) {
            return ProviderAdmission.ClockRollback(candidate.id)
        }
        val generation = candidate.providerConfigGeneration ?: return ProviderAdmission.Invalid
        val exactManifests = providerDao.findExactJobManifest(
            jobId = candidate.id,
            providerKind = providerKind,
            providerIdentitySha256 = candidate.providerIdentity ?: return ProviderAdmission.Invalid,
            modelIdentitySha256 = candidate.modelIdentity ?: return ProviderAdmission.Invalid,
            configurationIdentitySha256 = candidate.providerConfigurationIdentity
                ?: return ProviderAdmission.Invalid,
            configurationGeneration = generation,
        )
        if (exactManifests.size != 1) return ProviderAdmission.Invalid
        val manifest = exactManifests.single()
        val cohort = providerDao.findConfigCohort(manifest.cohortId)
            ?: return ProviderAdmission.Invalid
        if (!manifest.isExactFor(candidate, cohort)) return ProviderAdmission.Invalid

        val latest = providerDao.findLatestAttempt(candidate.id)
        if (latest != null) {
            if (latest.attemptOrdinal != candidate.attempts) return ProviderAdmission.Invalid
            when (latest.state) {
                LearningProviderAttemptState.RELEASED.name -> Unit
                LearningProviderAttemptState.RESERVED.name,
                LearningProviderAttemptState.DISPATCH_STARTED.name,
                LearningProviderAttemptState.TERMINAL.name,
                LearningProviderAttemptState.INDETERMINATE.name,
                -> return ProviderAdmission.Invalid
                else -> throw LearningJobInvariantException()
            }
        } else if (candidate.attempts != 0) {
            return ProviderAdmission.Invalid
        }

        val windowStartMs = utcDayStart(nowMs)
        val windowEndMs = checkedAdd(windowStartMs, PROVIDER_BUDGET_UTC_DAY_MS)
        val budget = providerKind.providerBudgetProfile() ?: return ProviderAdmission.Invalid
        val used = providerDao.readReservedBudgetForProviderKind(
            providerKind = providerKind,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
        )
        if (
            !canReserve(used.reservedProviderCalls, 1L, budget.dailyProviderCalls) ||
            !canReserve(
                used.reservedInputTokens,
                manifest.estimatedInputTokens,
                budget.dailyInputTokens,
            ) ||
            !canReserve(
                used.reservedOutputTokens,
                manifest.maxOutputTokens,
                budget.dailyOutputTokens,
            ) ||
            !canReserve(
                used.reservedCostMicros,
                manifest.maxCostMicros,
                budget.dailyCostMicros,
            )
        ) {
            return ProviderAdmission.BudgetExhausted(windowEndMs)
        }
        return ProviderAdmission.Accepted(manifest, cohort, windowStartMs, windowEndMs)
    }

    private suspend fun claimCandidate(
        candidate: LearningJobEntity,
        processSessionId: String,
        workerId: String,
        nowMs: Long,
        leaseUntilMs: Long,
        eligibleTypeNames: List<String>,
        providerAdmission: ProviderAdmission.Accepted?,
    ): LearningJobClaimResult {
        val dao = database.jobDao()
        val changed = dao.claim(
            id = candidate.id,
            expectedGeneration = candidate.leaseGeneration,
            processSessionId = processSessionId,
            workerId = workerId,
            nowMs = nowMs,
            leaseUntilMs = leaseUntilMs,
            eligibleJobTypes = eligibleTypeNames,
        )
        if (changed != 1) return LearningJobClaimResult.Contended
        val claimed = dao.findById(candidate.id) ?: throw LearningJobInvariantException()
        if (!claimed.isExactClaimOf(candidate, processSessionId, workerId, nowMs, leaseUntilMs)) {
            throw LearningJobInvariantException()
        }

        val providerContext = providerAdmission?.let { admission ->
            val attempt = admission.toReservedAttempt(claimed, nowMs, leaseUntilMs)
            val inserted = database.providerExecutionDao().insertAttemptIgnore(attempt)
            if (inserted == -1L) throw LearningJobInvariantException()
            if (database.providerExecutionDao().findAttempt(claimed.id, claimed.attempts) != attempt) {
                throw LearningJobInvariantException()
            }
            ProviderLeaseContext(
                database = database,
                clock = authorityClock,
                fence = RoomLearningProviderAttemptFence(
                    jobId = claimed.id,
                    attemptOrdinal = claimed.attempts,
                    processSessionId = processSessionId,
                    workerId = workerId,
                    leaseGeneration = claimed.leaseGeneration,
                ),
                receipt = admission.toReceipt(),
            )
        }
        return LearningJobClaimResult.Claimed(
            job = claimed,
            lease = issueLearningJobLease(claimed, providerContext),
        )
    }

    private suspend fun deadLetterCandidateForInvalidManifest(
        candidate: LearningJobEntity,
        processSessionId: String,
        workerId: String,
        nowMs: Long,
        leaseUntilMs: Long,
        eligibleTypeNames: List<String>,
    ): Boolean {
        val claimed = claimCandidate(
            candidate = candidate,
            processSessionId = processSessionId,
            workerId = workerId,
            nowMs = nowMs,
            leaseUntilMs = leaseUntilMs,
            eligibleTypeNames = eligibleTypeNames,
            providerAdmission = null,
        ) as? LearningJobClaimResult.Claimed ?: return false
        val fence = claimed.lease.requireIssuedLease().fence
        val changed = database.jobDao().finishDeadLetter(
            id = fence.jobId,
            processSessionId = fence.processSessionId,
            workerId = fence.workerId,
            leaseGeneration = fence.generation,
            nowMs = nowMs,
            errorCode = LearningJobErrorCode.INVALID_JOB_SPEC,
        )
        if (changed != 1) throw LearningJobInvariantException()
        return true
    }

    private fun issueLearningJobLease(
        job: LearningJobEntity,
        providerContext: ProviderLeaseContext?,
    ): LearningJobLease =
        IssuedLearningJobLease(
            fence = LearningJobFence(
                jobId = job.id,
                processSessionId = requireNotNull(job.leaseProcessSessionId),
                workerId = requireNotNull(job.leaseWorkerId),
                generation = job.leaseGeneration,
                leaseUntilMs = requireNotNull(job.leaseUntilMs),
            ),
            providerContext = providerContext?.let { context ->
                ProviderLeaseContext(
                    database = database,
                    clock = authorityClock,
                    fence = context.fence,
                    receipt = context.receipt,
                )
            },
        )
}

/** The only implementation accepted as a lease capability; it never leaves this source file. */
private class IssuedLearningJobLease(
    val fence: LearningJobFence,
    val providerContext: ProviderLeaseContext?,
) : LearningJobLease {
    override val jobId: String
        get() = fence.jobId

    override val leaseUntilMs: Long
        get() = fence.leaseUntilMs

    override val providerManifestReceipt: LearningProviderManifestReceipt?
        get() = providerContext?.receipt

    override val providerAttemptAuthority: BackgroundProviderAttemptAuthority?
        get() = providerContext?.let { context ->
            issueRoomLearningProviderAttemptAuthority(
                database = context.database,
                clock = context.clock,
                fence = context.fence,
                stableProviderIdempotencyKey = context.receipt.providerRequestKey,
                expectedDispatchAttestationSha256 = context.receipt.dispatchAttestationSha256,
            )
        }

    override fun toString(): String =
        "LearningJobLease(job=<redacted>, generation=${fence.generation}, deadline=$leaseUntilMs)"
}

private class ProviderLeaseContext(
    val database: LearningDatabase,
    val clock: LearningJobClock,
    val fence: RoomLearningProviderAttemptFence,
    val receipt: LearningProviderManifestReceipt,
)

private class LearningJobFence(
    val jobId: String,
    val processSessionId: String,
    val workerId: String,
    val generation: Long,
    val leaseUntilMs: Long,
) {
    init {
        require(jobId.isSafeLeaseIdentifier()) { "Invalid job identifier" }
        requireUuid(processSessionId, "process session")
        requireUuid(workerId, "worker")
        require(generation > 0L) { "Lease generation must be positive" }
        require(leaseUntilMs >= 0L) { "Negative lease deadline" }
    }
}

private fun LearningJobLease.requireIssuedLease(): IssuedLearningJobLease =
    this as? IssuedLearningJobLease ?: throw LearningLostLeaseException()

private fun LearningJobEntity.isExactClaimOf(
    previous: LearningJobEntity,
    processSessionId: String,
    workerId: String,
    nowMs: Long,
    leaseUntilMs: Long,
): Boolean =
    id == previous.id &&
        state == LearningJobState.RUNNING.name &&
        attempts == previous.attempts + 1 &&
        leaseGeneration == previous.leaseGeneration + 1L &&
        leaseProcessSessionId == processSessionId &&
        leaseWorkerId == workerId &&
        this.leaseUntilMs == leaseUntilMs &&
        updatedAtMs == nowMs &&
        lastErrorCode == null &&
        finishedAtMs == null

private fun LearningJobEntity.requireOwnedBy(fence: LearningJobFence, nowMs: Long) {
    if (updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    if (!isOwnedBy(fence) || requireNotNull(leaseUntilMs) <= nowMs) {
        throw LearningLostLeaseException()
    }
}

private fun LearningJobEntity.isOwnedBy(fence: LearningJobFence): Boolean =
    state == LearningJobState.RUNNING.name &&
        id == fence.jobId &&
        leaseProcessSessionId == fence.processSessionId &&
        leaseWorkerId == fence.workerId &&
        leaseGeneration == fence.generation

private sealed interface ProviderAdmission {
    data object NotProvider : ProviderAdmission
    data class BudgetExhausted(val notBeforeMs: Long) : ProviderAdmission
    data object Invalid : ProviderAdmission
    data class ClockRollback(val jobId: String) : ProviderAdmission
    data class Accepted(
        val manifest: LearningProviderJobManifestEntity,
        val cohort: LearningProviderConfigCohortEntity,
        val budgetWindowStartMs: Long,
        val budgetWindowEndMs: Long,
    ) : ProviderAdmission
}

private fun LearningJobEntity.isProviderEffectJob(): Boolean =
    jobType == LearningJobType.REFLECT_EPISODE_V1.name ||
        jobType == LearningJobType.DISTILL_POLICY_V1.name

private fun LearningProviderJobManifestEntity.isExactFor(
    job: LearningJobEntity,
    cohort: LearningProviderConfigCohortEntity,
): Boolean =
    jobId == job.id &&
        cohortId == cohort.id &&
        cohort.providerKind == job.providerKindIdentity &&
        cohort.providerIdentitySha256 == job.providerIdentity &&
        cohort.modelIdentitySha256 == job.modelIdentity &&
        cohort.configurationIdentitySha256 == job.providerConfigurationIdentity &&
        cohort.configurationGeneration == job.providerConfigGeneration &&
        providerRequestKey == learningProviderIdempotencyKey(job.id) &&
        maxProviderCalls == 1 &&
        when (cohort.providerKind) {
            PROVIDER_KIND_LOCAL -> maxCostMicros == 0L
            PROVIDER_KIND_REMOTE -> maxCostMicros == REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS
            else -> false
        }

private fun ProviderAdmission.Accepted.toReservedAttempt(
    claimed: LearningJobEntity,
    nowMs: Long,
    leaseUntilMs: Long,
): LearningProviderAttemptEntity {
    val attemptIdentity = LearningCanonicalId.digest(
        domainVersion = "learning-provider-attempt-v1",
        fields = listOf(
            claimed.id,
            claimed.attempts.toString(),
            manifest.providerRequestKey,
            manifest.dispatchAttestationSha256,
            manifest.requestHmacSha256,
            cohort.id,
            cohort.providerKind,
        ),
    )
    val budgetAuthorization = LearningCanonicalId.digest(
        domainVersion = "learning-provider-budget-v1",
        fields = listOf(
            claimed.id,
            claimed.attempts.toString(),
            manifest.requestHmacSha256,
            cohort.id,
            cohort.providerKind,
            budgetWindowStartMs.toString(),
            budgetWindowEndMs.toString(),
            manifest.estimatedInputTokens.toString(),
            manifest.maxOutputTokens.toString(),
            manifest.maxCostMicros.toString(),
        ),
    )
    return LearningProviderAttemptEntity(
        jobId = claimed.id,
        attemptOrdinal = claimed.attempts,
        attemptIdentitySha256 = attemptIdentity,
        state = LearningProviderAttemptState.RESERVED.name,
        dispatchKnowledge = LearningProviderDispatchKnowledge.NOT_DISPATCHED.name,
        budgetState = LearningProviderBudgetState.RESERVED.name,
        budgetAuthorizationSha256 = budgetAuthorization,
        budgetWindowStartMs = budgetWindowStartMs,
        budgetWindowEndMs = budgetWindowEndMs,
        reservedProviderCalls = 1,
        reservedInputTokens = manifest.estimatedInputTokens,
        reservedOutputTokens = manifest.maxOutputTokens,
        reservedCostMicros = manifest.maxCostMicros,
        actualProviderCalls = null,
        actualInputTokens = null,
        actualOutputTokens = null,
        actualCostMicros = null,
        terminalOutcome = null,
        leaseProcessSessionId = requireNotNull(claimed.leaseProcessSessionId),
        leaseWorkerId = requireNotNull(claimed.leaseWorkerId),
        leaseGeneration = claimed.leaseGeneration,
        leaseUntilMs = leaseUntilMs,
        createdAtMs = nowMs,
        dispatchStartedAtMs = null,
        terminalObservedAtMs = null,
        updatedAtMs = nowMs,
        finishedAtMs = null,
    )
}

private fun ProviderAdmission.Accepted.toReceipt(): LearningProviderManifestReceipt =
    LearningProviderManifestReceipt(
        cohortId = cohort.id,
        providerKind = cohort.providerKind,
        providerIdentitySha256 = cohort.providerIdentitySha256,
        modelIdentitySha256 = cohort.modelIdentitySha256,
        configurationIdentitySha256 = cohort.configurationIdentitySha256,
        configurationGeneration = cohort.configurationGeneration,
        manifestSchemaVersion = manifest.manifestSchemaVersion,
        requestHmacSha256 = manifest.requestHmacSha256,
        inputIdentitySha256 = manifest.inputIdentitySha256,
        runtimeAttestationSha256 = manifest.runtimeAttestationSha256,
        redactionPolicyIdentity = manifest.redactionPolicyIdentity,
        fieldCategoriesIdentity = manifest.fieldCategoriesIdentity,
        tokenEstimatorIdentity = manifest.tokenEstimatorIdentity,
        providerRequestKey = manifest.providerRequestKey,
        inputUtf8Bytes = manifest.inputUtf8Bytes,
        maxInputUtf8Bytes = manifest.maxInputUtf8Bytes,
        estimatedInputTokens = manifest.estimatedInputTokens,
        maxOutputTokens = manifest.maxOutputTokens,
        maxOutputUtf8Bytes = manifest.maxOutputUtf8Bytes,
        maxProviderCalls = manifest.maxProviderCalls,
        maxCostMicros = manifest.maxCostMicros,
        timeoutMs = manifest.timeoutMs,
        frozenAtMs = manifest.frozenAtMs,
    )

private suspend fun LearningDatabase.requireHeartbeatCompatibleAttempt(
    context: ProviderLeaseContext,
    currentJobLeaseUntilMs: Long,
    requestedLeaseUntilMs: Long?,
    nowMs: Long,
) {
    val dao = providerExecutionDao()
    val attempt = dao.findAttempt(context.fence.jobId, context.fence.attemptOrdinal)
        ?: throw LearningLostLeaseException()
    if (attempt.updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    if (!attempt.isOwnedFence(context.fence)) {
        throw LearningLostLeaseException()
    }
    if (
        attempt.state == LearningProviderAttemptState.TERMINAL.name ||
        attempt.state == LearningProviderAttemptState.RELEASED.name
    ) {
        if (attempt.leaseUntilMs > currentJobLeaseUntilMs) throw LearningJobInvariantException()
        return
    }
    if (!attempt.isOwnedActive(context.fence) || attempt.leaseUntilMs != currentJobLeaseUntilMs) {
        throw LearningLostLeaseException()
    }
    val newDeadline = requestedLeaseUntilMs ?: return
    val changed = dao.extendReservedOrDispatchedLeaseIfOwned(
        jobId = context.fence.jobId,
        attemptOrdinal = context.fence.attemptOrdinal,
        processSessionId = context.fence.processSessionId,
        workerId = context.fence.workerId,
        leaseGeneration = context.fence.leaseGeneration,
        expectedLeaseUntilMs = currentJobLeaseUntilMs,
        newLeaseUntilMs = newDeadline,
        nowMs = nowMs,
    )
    if (changed != 1) throw LearningLostLeaseException()
}

private suspend fun LearningDatabase.requireSuccessfulTerminalAttempt(
    context: ProviderLeaseContext,
    nowMs: Long,
) {
    val attempt = providerExecutionDao().findAttempt(
        context.fence.jobId,
        context.fence.attemptOrdinal,
    ) ?: throw LearningJobInvariantException()
    if (attempt.updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    if (
        !attempt.isOwnedState(context.fence, LearningProviderAttemptState.TERMINAL) ||
        attempt.dispatchKnowledge != LearningProviderDispatchKnowledge.TERMINAL_OBSERVED.name ||
        attempt.budgetState != LearningProviderBudgetState.COMMITTED.name ||
        attempt.terminalOutcome != LearningProviderTerminalOutcome.SUCCESS.name ||
        attempt.actualProviderCalls != 1
    ) {
        throw LearningJobInvariantException()
    }
}

private suspend fun LearningDatabase.releaseAndProveUndispatchedForRetry(
    context: ProviderLeaseContext,
    nowMs: Long,
): Boolean {
    val dao = providerExecutionDao()
    val attempt = dao.findAttempt(context.fence.jobId, context.fence.attemptOrdinal)
        ?: throw LearningJobInvariantException()
    if (attempt.updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    if (attempt.isOwnedState(context.fence, LearningProviderAttemptState.RESERVED)) {
        val changed = dao.releaseUndispatchedIfOwned(
            jobId = context.fence.jobId,
            attemptOrdinal = context.fence.attemptOrdinal,
            processSessionId = context.fence.processSessionId,
            workerId = context.fence.workerId,
            leaseGeneration = context.fence.leaseGeneration,
            nowMs = nowMs,
        )
        if (changed != 1) throw LearningLostLeaseException()
    }
    return dao.findAttempt(context.fence.jobId, context.fence.attemptOrdinal)
        .isOwnedState(context.fence, LearningProviderAttemptState.RELEASED)
}

private suspend fun LearningDatabase.releaseReservationBeforePermanentFailure(
    context: ProviderLeaseContext,
    nowMs: Long,
) {
    val dao = providerExecutionDao()
    val attempt = dao.findAttempt(context.fence.jobId, context.fence.attemptOrdinal)
        ?: throw LearningJobInvariantException()
    if (attempt.updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    if (attempt.isOwnedState(context.fence, LearningProviderAttemptState.RESERVED)) {
        if (
            dao.releaseUndispatchedIfOwned(
                jobId = context.fence.jobId,
                attemptOrdinal = context.fence.attemptOrdinal,
                processSessionId = context.fence.processSessionId,
                workerId = context.fence.workerId,
                leaseGeneration = context.fence.leaseGeneration,
                nowMs = nowMs,
            ) != 1
        ) throw LearningLostLeaseException()
    }
}

private fun LearningProviderAttemptEntity?.isOwnedActive(
    fence: RoomLearningProviderAttemptFence,
): Boolean =
    isOwnedState(fence, LearningProviderAttemptState.RESERVED) ||
        isOwnedState(fence, LearningProviderAttemptState.DISPATCH_STARTED)

private fun LearningProviderAttemptEntity.isOwnedFence(
    fence: RoomLearningProviderAttemptFence,
): Boolean =
    jobId == fence.jobId &&
        attemptOrdinal == fence.attemptOrdinal &&
        leaseProcessSessionId == fence.processSessionId &&
        leaseWorkerId == fence.workerId &&
        leaseGeneration == fence.leaseGeneration

private fun LearningProviderAttemptEntity?.isOwnedState(
    fence: RoomLearningProviderAttemptFence,
    expectedState: LearningProviderAttemptState,
): Boolean = this != null &&
    jobId == fence.jobId &&
    attemptOrdinal == fence.attemptOrdinal &&
    leaseProcessSessionId == fence.processSessionId &&
    leaseWorkerId == fence.workerId &&
    leaseGeneration == fence.leaseGeneration &&
    state == expectedState.name

private suspend fun LearningDatabase.deadLetterActiveJobOnStartup(
    job: LearningJobEntity,
    nowMs: Long,
    errorCode: LearningJobErrorCode,
): Boolean {
    val dao = jobDao()
    if (job.state == LearningJobState.RUNNING.name) {
        val owner = job.leaseProcessSessionId ?: return false
        val worker = job.leaseWorkerId ?: return false
        if (requireNotNull(job.leaseUntilMs) > nowMs) {
            val changed = dao.finishDeadLetter(
                id = job.id,
                processSessionId = owner,
                workerId = worker,
                leaseGeneration = job.leaseGeneration,
                nowMs = nowMs,
                errorCode = errorCode,
            )
            if (changed != 1) throw LearningJobInvariantException()
            return true
        }
    }
    if (
        job.state != LearningJobState.PENDING.name &&
        job.state != LearningJobState.RETRY.name &&
        job.state != LearningJobState.RUNNING.name
    ) {
        return false
    }
    val syntheticProcess = STARTUP_RECOVERY_PROCESS_UUID
    val syntheticWorker = STARTUP_RECOVERY_WORKER_UUID
    val leaseUntilMs = checkedAdd(nowMs, STARTUP_RECOVERY_LEASE_MS)
    if (
        dao.claim(
            id = job.id,
            expectedGeneration = job.leaseGeneration,
            processSessionId = syntheticProcess,
            workerId = syntheticWorker,
            nowMs = nowMs,
            leaseUntilMs = leaseUntilMs,
            eligibleJobTypes = listOf(job.jobType),
        ) != 1
    ) return false
    val claimed = dao.findById(job.id) ?: throw LearningJobInvariantException()
    val finished = dao.finishDeadLetter(
        id = job.id,
        processSessionId = syntheticProcess,
        workerId = syntheticWorker,
        leaseGeneration = claimed.leaseGeneration,
        nowMs = nowMs,
        errorCode = errorCode,
    )
    if (finished != 1) throw LearningJobInvariantException()
    return true
}

private fun utcDayStart(nowMs: Long): Long =
    Math.floorDiv(nowMs, PROVIDER_BUDGET_UTC_DAY_MS) * PROVIDER_BUDGET_UTC_DAY_MS

private fun canReserve(used: Long, requested: Long, limit: Long): Boolean {
    if (used < 0L || requested < 0L || limit < 0L) throw LearningJobInvariantException()
    val total = try {
        Math.addExact(used, requested)
    } catch (_: ArithmeticException) {
        return false
    }
    return total <= limit
}

private data class ProviderBudgetProfile(
    val dailyProviderCalls: Long,
    val dailyInputTokens: Long,
    val dailyOutputTokens: Long,
    val dailyCostMicros: Long,
)

private fun String.providerBudgetProfile(): ProviderBudgetProfile? = when (this) {
    PROVIDER_KIND_LOCAL -> ProviderBudgetProfile(
        dailyProviderCalls = LOCAL_DAILY_PROVIDER_CALLS,
        dailyInputTokens = LOCAL_DAILY_INPUT_TOKENS,
        dailyOutputTokens = LOCAL_DAILY_OUTPUT_TOKENS,
        dailyCostMicros = LOCAL_DAILY_COST_MICROS,
    )
    PROVIDER_KIND_REMOTE -> ProviderBudgetProfile(
        dailyProviderCalls = REMOTE_DAILY_PROVIDER_CALLS,
        dailyInputTokens = REMOTE_DAILY_INPUT_TOKENS,
        dailyOutputTokens = REMOTE_DAILY_OUTPUT_TOKENS,
        dailyCostMicros = REMOTE_DAILY_COST_RESERVATION_MICROS,
    )
    else -> null
}

private fun checkedNow(clock: LearningJobClock): Long = clock.nowMs().also(::requireValidNow)

private fun requireValidNow(nowMs: Long) {
    require(nowMs >= 0L) { "Negative clock" }
}

private fun checkedAdd(left: Long, right: Long): Long =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Learning job timestamp overflow")
    }

private fun requireUuid(value: String, label: String) {
    require(
        value != "00000000-0000-0000-0000-000000000000" &&
            runCatching { kotlin.uuid.Uuid.parse(value) }.isSuccess
    ) {
        "Learning job $label must be a UUID"
    }
}

private const val PROVIDER_KIND_LOCAL = "local_litert"
private const val PROVIDER_KIND_REMOTE = "remote"
private const val PROVIDER_BUDGET_UTC_DAY_MS = 86_400_000L
private const val LOCAL_DAILY_PROVIDER_CALLS = 8L
private const val LOCAL_DAILY_INPUT_TOKENS = 131_072L
private const val LOCAL_DAILY_OUTPUT_TOKENS = 65_536L
private const val LOCAL_DAILY_COST_MICROS = 0L
private const val REMOTE_DAILY_PROVIDER_CALLS = 8L
private const val REMOTE_DAILY_INPUT_TOKENS = 131_072L
private const val REMOTE_DAILY_OUTPUT_TOKENS = 65_536L
/**
 * A conservative authorization envelope, not a price estimate. Official provider pricing is not
 * frozen in this database. Explicit remote consent admits the call; terminal usage records the
 * provider-reported actual cost (or UNKNOWN/null), never a fabricated zero.
 */
internal const val REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS = 1_000_000L
private const val REMOTE_DAILY_COST_RESERVATION_MICROS =
    REMOTE_DAILY_PROVIDER_CALLS * REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS
private const val MAX_INVALID_PROVIDER_JOBS_PER_CLAIM = 64
private const val MAX_MANDATORY_INVALIDATION_STARTUP_REPAIRS = 64
private const val MAX_PROVIDER_RECOVERY_ROWS = 1_024
private const val STARTUP_RECOVERY_LEASE_MS = 1L
private const val STARTUP_RECOVERY_PROCESS_UUID = "00000000-0000-0000-0000-000000000001"
private const val STARTUP_RECOVERY_WORKER_UUID = "00000000-0000-0000-0000-000000000002"
private const val PROVIDER_CLOCK_HIGH_WATER_ID = "provider-attempt-clock-high-water"
