package me.rerere.rikkahub.learning.jobs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobErrorCode
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this test on the user's primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningJobCoordinatorInstrumentedTest {
    private lateinit var database: LearningDatabase
    private lateinit var clock: MutableLearningJobClock

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
        clock = MutableLearningJobClock(100)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun expiredReclaimFencesStaleWorkerHeartbeatAndFinish() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source"))
        val firstCoordinator = coordinator(PROCESS_A)
        val first = firstCoordinator.claimNext(uuid(WORKER_A), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed
        assertFalse(first.lease.toString().contains(PROCESS_A))
        assertFalse(first.lease.toString().contains(WORKER_A))

        clock.nowMs = 201
        val secondCoordinator = coordinator(PROCESS_B)
        val second = secondCoordinator.claimNext(uuid(WORKER_B), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed

        clock.nowMs = 202
        assertTrue(
            runCatching { firstCoordinator.heartbeat(first.lease, leaseDurationMs = 100) }
                .exceptionOrNull() is LearningLostLeaseException,
        )
        assertTrue(
            runCatching {
                firstCoordinator.failPermanently(
                    first.lease,
                    LearningJobFailureCode.SOURCE_STALE,
                )
            }
                .exceptionOrNull() is LearningLostLeaseException,
        )

        secondCoordinator.failPermanently(
            second.lease,
            LearningJobFailureCode.SOURCE_STALE,
        )
        assertEquals(LearningJobState.DEAD_LETTER.name, database.jobDao().findById("source")?.state)
    }

    @Test
    fun expiredLeaseCannotFinishP0Job() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source"))
        val coordinator = coordinator(PROCESS_A)
        val claim = coordinator.claimNext(uuid(WORKER_A), leaseDurationMs = 50)
            as LearningJobClaimResult.Claimed
        clock.nowMs = 151

        val result = runCatching {
            coordinator.failPermanently(claim.lease, LearningJobFailureCode.SOURCE_STALE)
        }

        assertTrue(result.exceptionOrNull() is LearningLostLeaseException)
        assertEquals(LearningJobState.RUNNING.name, database.jobDao().findById("source")?.state)
    }

    @Test
    fun businessHandlerApiHasNoDatabaseOrDaoParameter() {
        val executeMethod = LearningJobHandler::class.java.declaredMethods
            .single { it.name == "execute" }
        assertTrue(
            executeMethod.parameterTypes.none { parameter ->
                LearningDatabase::class.java.isAssignableFrom(parameter) ||
                    parameter.simpleName.endsWith("Dao")
            },
        )
    }

    @Test
    fun activeFutureAuthorityTimestampReportsClockRollback() = runBlocking {
        database.jobDao().insertIgnore(
            job(
                id = "future",
                notBeforeMs = 500,
                updatedAtMs = 500,
            ),
        )

        val result = coordinator(PROCESS_A).claimNext(uuid(WORKER_A), leaseDurationMs = 100)

        assertEquals(LearningJobClaimResult.ClockRollback("future"), result)
        assertNull(database.jobDao().findById("future")?.leaseProcessSessionId)
    }

    @Test
    fun heartbeatNeverShrinksPersistedDeadline() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source"))
        val coordinator = coordinator(PROCESS_A)
        val claim = coordinator.claimNext(uuid(WORKER_A), leaseDurationMs = 200)
            as LearningJobClaimResult.Claimed

        clock.nowMs = 110
        val unchanged = coordinator.heartbeat(claim.lease, leaseDurationMs = 20)
        assertEquals(300L, unchanged.leaseUntilMs)
        assertEquals(300L, database.jobDao().findById("source")?.leaseUntilMs)
        assertEquals(100L, database.jobDao().findById("source")?.updatedAtMs)

        clock.nowMs = 120
        val extended = coordinator.heartbeat(unchanged, leaseDurationMs = 300)
        assertEquals(420L, extended.leaseUntilMs)
        assertEquals(420L, database.jobDao().findById("source")?.leaseUntilMs)
    }

    @Test
    fun heartbeatResamplesClockAtFinalFenceAndRejectsCrossedDeadline() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source"))
        val claim = coordinator(PROCESS_A).claimNext(uuid(WORKER_A), leaseDurationMs = 50)
            as LearningJobClaimResult.Claimed
        val delayed = coordinator(
            processSessionId = PROCESS_A,
            jobClock = SequenceLearningJobClock(120, 151),
        )

        val failure = runCatching {
            delayed.heartbeat(claim.lease, leaseDurationMs = 100)
        }.exceptionOrNull()

        assertTrue(failure is LearningLostLeaseException)
        assertEquals(150L, database.jobDao().findById("source")?.leaseUntilMs)
    }

    @Test
    fun retryResamplesClockAtFinalFenceAndRejectsCrossedDeadline() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source"))
        val claim = coordinator(PROCESS_A).claimNext(uuid(WORKER_A), leaseDurationMs = 50)
            as LearningJobClaimResult.Claimed
        val delayed = coordinator(
            processSessionId = PROCESS_A,
            jobClock = SequenceLearningJobClock(120, 151),
        )

        val failure = runCatching {
            delayed.failAttempt(
                lease = claim.lease,
                retryDelayMs = 25,
                errorCode = LearningJobFailureCode.SOURCE_STALE,
            )
        }.exceptionOrNull()

        assertTrue(failure is LearningLostLeaseException)
        assertEquals(LearningJobState.RUNNING.name, database.jobDao().findById("source")?.state)
    }

    @Test
    fun failureOfLastAllowedAttemptDeadLettersInsteadOfRetrying() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source", maxAttempts = 1))
        val coordinator = coordinator(PROCESS_A)
        val claim = coordinator.claimNext(uuid(WORKER_A), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed

        clock.nowMs = 110
        coordinator.failAttempt(
            lease = claim.lease,
            retryDelayMs = 50,
            errorCode = LearningJobFailureCode.SOURCE_MISSING,
        )

        val dead = database.jobDao().findById("source")!!
        assertEquals(LearningJobState.DEAD_LETTER.name, dead.state)
        assertEquals(LearningJobErrorCode.ATTEMPTS_EXHAUSTED.name, dead.lastErrorCode)
        assertEquals(2L, dead.leaseGeneration)
        assertEquals(110L, dead.finishedAtMs)
    }

    @Test
    fun cancellationFencesGenerationEvenDuringClockRollback() = runBlocking {
        database.jobDao().insertIgnore(job(id = "source"))
        val coordinator = coordinator(PROCESS_A)
        val claim = coordinator.claimNext(uuid(WORKER_A), leaseDurationMs = 200)
            as LearningJobClaimResult.Claimed

        clock.nowMs = 50
        assertEquals(1, coordinator.cancelAllActive())

        val cancelled = database.jobDao().findById("source")!!
        assertEquals(LearningJobState.CANCELLED.name, cancelled.state)
        assertEquals(2L, cancelled.leaseGeneration)
        assertEquals(100L, cancelled.updatedAtMs)
        assertEquals(LearningJobErrorCode.CANCELLED_BY_RESET.name, cancelled.lastErrorCode)

        clock.nowMs = 101
        assertTrue(
            runCatching { coordinator.heartbeat(claim.lease, leaseDurationMs = 100) }
                .exceptionOrNull() is LearningLostLeaseException,
        )
    }

    @Test
    fun startupRecoveryFencesOtherExpiredAndExhaustedRowsInOneCall() = runBlocking {
        database.jobDao().insertIgnore(
            runningJob(
                id = "other-session",
                processSessionId = PROCESS_B,
                workerId = WORKER_B,
                leaseUntilMs = 500,
            ),
        )
        database.jobDao().insertIgnore(
            runningJob(
                id = "expired-current",
                processSessionId = PROCESS_A,
                workerId = WORKER_A,
                leaseUntilMs = 99,
            ),
        )
        database.jobDao().insertIgnore(
            job(
                id = "exhausted",
                state = LearningJobState.RETRY,
                attempts = 2,
                maxAttempts = 2,
                lastErrorCode = LearningJobErrorCode.SOURCE_STALE,
                updatedAtMs = 50,
            ),
        )

        val result = coordinator(PROCESS_A).recoverOnStartup(retryDelayMs = 25)

        assertEquals(
            LearningJobStartupRecoveryResult.Recovered(
                otherProcessSessions = 1,
                expiredLeases = 1,
                exhaustedAttempts = 1,
            ),
            result,
        )
        assertRecoveredRetry("other-session", LearningJobErrorCode.LOST_LEASE)
        assertRecoveredRetry("expired-current", LearningJobErrorCode.LEASE_EXPIRED)
        val exhausted = database.jobDao().findById("exhausted")!!
        assertEquals(LearningJobState.DEAD_LETTER.name, exhausted.state)
        assertEquals(LearningJobErrorCode.ATTEMPTS_EXHAUSTED.name, exhausted.lastErrorCode)
        assertEquals(1L, exhausted.leaseGeneration)
    }

    private suspend fun assertRecoveredRetry(id: String, errorCode: LearningJobErrorCode) {
        val recovered = database.jobDao().findById(id)!!
        assertEquals(LearningJobState.RETRY.name, recovered.state)
        assertEquals(errorCode.name, recovered.lastErrorCode)
        assertEquals(2L, recovered.leaseGeneration)
        assertEquals(125L, recovered.notBeforeMs)
        assertNull(recovered.leaseProcessSessionId)
        assertNull(recovered.leaseWorkerId)
        assertNull(recovered.leaseUntilMs)
    }

    private fun coordinator(
        processSessionId: String,
        jobClock: LearningJobClock = clock,
    ) = LearningJobCoordinator(
        database = database,
        processSessionId = uuid(processSessionId),
        clock = jobClock,
        maxLeaseDurationMs = 500,
    )

    private fun job(
        id: String,
        state: LearningJobState = LearningJobState.PENDING,
        attempts: Int = 0,
        maxAttempts: Int = 3,
        notBeforeMs: Long = 0,
        lastErrorCode: LearningJobErrorCode? = null,
        updatedAtMs: Long = 0,
    ) = LearningJobEntity(
        id = id,
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "dedupe-$id",
        streamId = STREAM_ID,
        sourceEventId = "event-$id",
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        state = state.name,
        priority = 0,
        attempts = attempts,
        maxAttempts = maxAttempts,
        notBeforeMs = notBeforeMs,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0,
        leaseUntilMs = null,
        lastErrorCode = lastErrorCode?.name,
        createdAtMs = 0,
        updatedAtMs = updatedAtMs,
        finishedAtMs = null,
        replayGeneration = 0,
    )

    private fun runningJob(
        id: String,
        processSessionId: String,
        workerId: String,
        leaseUntilMs: Long,
    ) = LearningJobEntity(
        id = id,
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "dedupe-$id",
        streamId = STREAM_ID,
        sourceEventId = "event-$id",
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        state = LearningJobState.RUNNING.name,
        priority = 0,
        attempts = 1,
        maxAttempts = 3,
        notBeforeMs = 0,
        leaseProcessSessionId = processSessionId,
        leaseWorkerId = workerId,
        leaseGeneration = 1,
        leaseUntilMs = leaseUntilMs,
        lastErrorCode = null,
        createdAtMs = 0,
        updatedAtMs = 50,
        finishedAtMs = null,
        replayGeneration = 0,
    )

    private fun uuid(value: String): Uuid = Uuid.parse(value)

    private class MutableLearningJobClock(var nowMs: Long) : LearningJobClock {
        override fun nowMs(): Long = nowMs
    }

    private class SequenceLearningJobClock(vararg values: Long) : LearningJobClock {
        private val samples = ArrayDeque(values.toList())

        override fun nowMs(): Long = samples.removeFirst()
    }

    private companion object {
        const val STREAM_ID = "00000000-0000-0000-0000-000000000001"
        const val SCOPE_ID = "00000000-0000-0000-0000-000000000003"
        const val PROCESS_A = "00000000-0000-0000-0000-000000000010"
        const val PROCESS_B = "00000000-0000-0000-0000-000000000020"
        const val WORKER_A = "00000000-0000-0000-0000-000000000011"
        const val WORKER_B = "00000000-0000-0000-0000-000000000021"
    }
}
