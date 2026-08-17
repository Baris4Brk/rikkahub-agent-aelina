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
import me.rerere.rikkahub.learning.storage.LearningProviderAttemptState
import me.rerere.rikkahub.learning.storage.LearningProviderBudgetState
import me.rerere.rikkahub.learning.storage.LearningProviderConfigCohortEntity
import me.rerere.rikkahub.learning.storage.LearningProviderDispatchKnowledge
import me.rerere.rikkahub.learning.storage.LearningProviderJobManifestEntity
import me.rerere.rikkahub.learning.storage.PROVIDER_JOB_MANIFEST_SCHEMA_VERSION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this test on the user's primary phone. */
@RunWith(AndroidJUnit4::class)
class P1ProviderProcessDeathInstrumentedTest {
    private lateinit var database: LearningDatabase
    private lateinit var clock: MutableClock

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
        clock = MutableClock(100L)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun processDeathAfterDispatchMakesAttemptIndeterminateAndJobUnclaimable() = runBlocking {
        database.jobDao().insertIgnore(providerJob())
        database.providerExecutionDao().insertConfigCohortIgnore(cohort())
        database.providerExecutionDao().insertJobManifestIgnore(manifest())

        val firstProcess = coordinator(PROCESS_A)
        val claimed = firstProcess.claimNext(uuid(WORKER_A), leaseDurationMs = 100L)
            as LearningJobClaimResult.Claimed
        val authority = requireNotNull(claimed.providerAttemptAuthority)
        clock.nowMs = 110L
        assertTrue(authority.markDispatchStarted(RUNTIME_ATTESTATION))

        val dispatched = requireNotNull(
            database.providerExecutionDao().findAttempt(JOB_ID, attemptOrdinal = 1),
        )
        assertEquals(LearningProviderAttemptState.DISPATCH_STARTED.name, dispatched.state)
        assertEquals(
            LearningProviderDispatchKnowledge.POSSIBLY_DISPATCHED.name,
            dispatched.dispatchKnowledge,
        )

        // A fresh process session models process death. The old lease has not even expired yet;
        // different ownership alone must be enough to forbid a duplicate provider call.
        clock.nowMs = 120L
        val recovered = coordinator(PROCESS_B).recoverOnStartup(retryDelayMs = 25L)
            as LearningJobStartupRecoveryResult.Recovered
        assertEquals(1, recovered.orphanDispatchesIndeterminate)
        assertEquals(1, recovered.providerJobsDeadLettered)
        assertEquals(0, recovered.orphanReservationsReleased)

        val attempt = requireNotNull(
            database.providerExecutionDao().findAttempt(JOB_ID, attemptOrdinal = 1),
        )
        assertEquals(LearningProviderAttemptState.INDETERMINATE.name, attempt.state)
        assertEquals(LearningProviderBudgetState.INDETERMINATE.name, attempt.budgetState)
        assertEquals(
            LearningProviderDispatchKnowledge.POSSIBLY_DISPATCHED.name,
            attempt.dispatchKnowledge,
        )
        val job = requireNotNull(database.jobDao().findById(JOB_ID))
        assertEquals(LearningJobState.DEAD_LETTER.name, job.state)
        assertEquals(LearningJobErrorCode.INTERNAL.name, job.lastErrorCode)
        assertEquals(
            LearningJobClaimResult.NoWork,
            coordinator(PROCESS_B).claimNext(uuid(WORKER_B), leaseDurationMs = 100L),
        )
    }

    private fun coordinator(processId: String) = LearningJobCoordinator(
        database = database,
        processSessionId = uuid(processId),
        clock = clock,
        maxLeaseDurationMs = 500L,
    )

    private fun providerJob() = LearningJobEntity(
        id = JOB_ID,
        jobType = LearningJobType.REFLECT_EPISODE_V1.name,
        jobSchemaVersion = 1,
        dedupeKey = "p1-process-death-dedupe",
        streamId = STREAM_ID,
        sourceEventId = "learning-event-v1:p1-process-death",
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        state = LearningJobState.PENDING.name,
        priority = 10,
        attempts = 0,
        maxAttempts = 3,
        notBeforeMs = 50L,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0L,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = 50L,
        updatedAtMs = 50L,
        finishedAtMs = null,
        replayGeneration = 0L,
        algorithmIdentity = "reflection-v1",
        promptIdentity = "reflection-v1",
        providerKindIdentity = LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode,
        modelIdentity = MODEL_IDENTITY,
        providerIdentity = PROVIDER_IDENTITY,
        providerConfigurationIdentity = CONFIGURATION_IDENTITY,
        providerConfigGeneration = 1L,
        sourceSchemaIdentity = "reflection-input-v2",
        toolsetIdentity = "no-tools-v1",
        outputSchemaIdentity = "episode-lesson-v1",
    )

    private fun cohort() = LearningProviderConfigCohortEntity(
        id = COHORT_ID,
        providerKind = LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode,
        providerIdentitySha256 = PROVIDER_IDENTITY,
        modelIdentitySha256 = MODEL_IDENTITY,
        configurationIdentitySha256 = CONFIGURATION_IDENTITY,
        configurationGeneration = 1L,
        createdAtMs = 50L,
    )

    private fun manifest() = LearningProviderJobManifestEntity(
        jobId = JOB_ID,
        cohortId = COHORT_ID,
        manifestSchemaVersion = PROVIDER_JOB_MANIFEST_SCHEMA_VERSION,
        requestHmacSha256 = "d".repeat(64),
        inputIdentitySha256 = "e".repeat(64),
        runtimeAttestationSha256 = RUNTIME_ATTESTATION,
        redactionPolicyIdentity = "learning-redaction-v1",
        fieldCategoriesIdentity = "bounded-learning-fields-v1",
        tokenEstimatorIdentity = "p1-test-token-estimator-v1",
        providerRequestKey = learningProviderIdempotencyKey(JOB_ID),
        inputUtf8Bytes = 128L,
        maxInputUtf8Bytes = 128L * 1_024L,
        estimatedInputTokens = 64L,
        maxOutputTokens = 1_024L,
        maxOutputUtf8Bytes = 16L * 1_024L,
        maxProviderCalls = 1,
        maxCostMicros = 0L,
        timeoutMs = 120_000L,
        frozenAtMs = 50L,
    )

    private fun uuid(value: String): Uuid = Uuid.parse(value)

    private class MutableClock(var nowMs: Long) : LearningJobClock {
        override fun nowMs(): Long = nowMs
    }

    private companion object {
        const val JOB_ID = "p1-process-death-reflection"
        const val COHORT_ID = "p1-process-death-cohort"
        const val STREAM_ID = "00000000-0000-4000-8000-000000000001"
        const val SCOPE_ID = "00000000-0000-4000-8000-000000000002"
        const val PROCESS_A = "00000000-0000-4000-8000-000000000010"
        const val PROCESS_B = "00000000-0000-4000-8000-000000000020"
        const val WORKER_A = "00000000-0000-4000-8000-000000000011"
        const val WORKER_B = "00000000-0000-4000-8000-000000000021"
        val PROVIDER_IDENTITY = "a".repeat(64)
        val MODEL_IDENTITY = "b".repeat(64)
        val CONFIGURATION_IDENTITY = "c".repeat(64)
        val RUNTIME_ATTESTATION = "9".repeat(64)
    }
}
