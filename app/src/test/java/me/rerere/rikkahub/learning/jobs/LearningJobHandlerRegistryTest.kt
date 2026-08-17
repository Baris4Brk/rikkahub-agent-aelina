package me.rerere.rikkahub.learning.jobs

import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.background.BackgroundProviderAttemptAuthority
import me.rerere.rikkahub.data.ai.background.BackgroundProviderTerminalOutcome
import me.rerere.rikkahub.data.ai.background.BackgroundProviderUsage
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningJobHandlerRegistryTest {
    @Test
    fun emptyProductionRegistryHasNoClaimableType() = runBlocking {
        val readiness = LearningJobHandlerRegistry.empty().readiness()

        assertEquals(0, readiness.registeredCount)
        assertTrue(readiness.readyTypes.isEmpty())
    }

    @Test
    fun unavailableHandlerIsExcludedBeforeDispatch() = runBlocking {
        val called = AtomicBoolean(false)
        val registry = registry(
            readiness = LearningJobHandlerReadiness.WAITING_CONFIGURATION,
            called = called,
        )

        val readiness = registry.readiness()

        assertTrue(readiness.readyTypes.isEmpty())
        assertEquals(1, readiness.waitingConfigurationCount)
        assertFalse(called.get())
    }

    @Test
    fun dispatchCarriesFrozenP0SpecAndTypedOutput() = runBlocking {
        var observed: LearningJobExecutionInputV1? = null
        val registry = LearningJobHandlerRegistry.Builder()
            .register(
                jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW,
                handler = LearningJobHandler<TestOutput> { input, control ->
                    control.checkpoint()
                    observed = input
                    LearningJobHandlerResult.Success(TestOutput)
                },
                outputCommitter = LearningJobTypedOutputCommitter { _, _, _ -> Unit },
            )
            .build()

        val result = registry.dispatch(job(), monotonicDeadlineMs = 10, monotonicMs = { 1 })

        assertTrue(result is LearningJobDispatchResult.Success)
        assertEquals("p0-structural-handoff-v1", observed?.executionSpec?.algorithmIdentity)
        assertEquals("no-provider-model-v1", observed?.executionSpec?.modelIdentity)
        assertEquals("authority-event-only-v1", observed?.executionSpec?.toolsetIdentity)
        assertEquals(0L, observed?.createdAtMs)
        assertTrue(observed?.stableProviderIdempotencyKey?.startsWith("learning-provider-v1:") == true)
    }

    @Test(expected = CancellationException::class)
    fun handlerCancellationIsNeverConvertedToFailure(): Unit = runBlocking {
        val registry = LearningJobHandlerRegistry.Builder()
            .register(
                jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW,
                handler = LearningJobHandler<TestOutput> { _, _ -> throw CancellationException() },
                outputCommitter = LearningJobTypedOutputCommitter { _, _, _ -> Unit },
            )
            .build()

        registry.dispatch(job(), monotonicDeadlineMs = 10, monotonicMs = { 1 })
    }

    @Test
    fun handlerContractCannotReceiveRoomOrDao() {
        val execute = LearningJobHandler::class.java.declaredMethods.single { it.name == "execute" }

        assertFalse(execute.parameterTypes.any { it.name.contains("LearningDatabase") })
        assertFalse(execute.parameterTypes.any { it.simpleName.endsWith("Dao") })
        assertTrue(Modifier.isPublic(execute.modifiers))
    }

    @Test
    fun remoteProviderJobCarriesTheSameDurableManifestAndAttemptAuthority() = runBlocking {
        var observed: LearningJobExecutionInputV1? = null
        val remoteJob = remoteProviderJob()
        val requestKey = learningProviderIdempotencyKey(remoteJob.id)
        val dispatchDigest = "d".repeat(64)
        val authority = object : BackgroundProviderAttemptAuthority {
            override val stableProviderIdempotencyKey: String = requestKey
            override val expectedRuntimeAttestationSha256: String = dispatchDigest
            override suspend fun markDispatchStarted(
                observedDispatchAttestationSha256: String,
            ): Boolean = false
            override suspend fun releaseUndispatched(): Boolean = false
            override suspend fun markTerminal(
                outcome: BackgroundProviderTerminalOutcome,
                usage: BackgroundProviderUsage,
            ): Boolean = false
        }
        val receipt = LearningProviderManifestReceipt(
            cohortId = "provider-cohort-v1:${"c".repeat(64)}",
            providerKind = LearningJobProviderKindIdentity.REMOTE.wireCode,
            providerIdentitySha256 = requireNotNull(remoteJob.providerIdentity),
            modelIdentitySha256 = requireNotNull(remoteJob.modelIdentity),
            configurationIdentitySha256 = requireNotNull(
                remoteJob.providerConfigurationIdentity,
            ),
            configurationGeneration = requireNotNull(remoteJob.providerConfigGeneration),
            manifestSchemaVersion = 1,
            requestHmacSha256 = "e".repeat(64),
            inputIdentitySha256 = "f".repeat(64),
            runtimeAttestationSha256 = dispatchDigest,
            redactionPolicyIdentity = "learning-redaction-v1",
            fieldCategoriesIdentity = "p1-bounded-provider-fields-v1",
            tokenEstimatorIdentity = "utf8-quarter-token-upper-v1",
            providerRequestKey = requestKey,
            inputUtf8Bytes = 64,
            maxInputUtf8Bytes = 160L * 1_024L,
            estimatedInputTokens = 16,
            maxOutputTokens = 1_024,
            maxOutputUtf8Bytes = 32L * 1_024L,
            maxProviderCalls = 1,
            maxCostMicros = REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS,
            timeoutMs = 60_000,
            frozenAtMs = remoteJob.createdAtMs,
        )
        val registry = LearningJobHandlerRegistry.Builder()
            .register(
                jobType = LearningJobType.REFLECT_EPISODE_V1,
                handler = LearningJobHandler<RemoteOutput> { input, _ ->
                    observed = input
                    LearningJobHandlerResult.Success(RemoteOutput)
                },
                outputCommitter = LearningJobTypedOutputCommitter { _, _, _ -> Unit },
            )
            .build()

        val result = registry.dispatch(
            job = remoteJob,
            monotonicDeadlineMs = 10,
            monotonicMs = { 1 },
            providerAttemptAuthority = authority,
            providerManifestReceipt = receipt,
        )

        assertTrue(result is LearningJobDispatchResult.Success)
        assertEquals(LearningJobProviderKindIdentity.REMOTE.wireCode, observed?.executionSpec?.providerKindIdentity)
        assertTrue(observed?.providerAttemptAuthority === authority)
        assertTrue(observed?.providerManifestReceipt === receipt)
    }

    private fun registry(
        readiness: LearningJobHandlerReadiness,
        called: AtomicBoolean,
    ) = LearningJobHandlerRegistry.Builder()
        .register(
            jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW,
            handler = LearningJobHandler<TestOutput> { _, _ ->
                called.set(true)
                LearningJobHandlerResult.Success(TestOutput)
            },
            outputCommitter = LearningJobTypedOutputCommitter { _, _, _ -> Unit },
            readiness = LearningJobHandlerReadinessProbe { readiness },
        )
        .build()

    private fun job() = LearningJobEntity(
        id = "learning-job-v1:${"a".repeat(64)}",
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "learning-job-dedupe-v1:${"a".repeat(64)}",
        streamId = "00000000-0000-0000-0000-000000000001",
        sourceEventId = "learning-event-v1:${"b".repeat(64)}",
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000002",
        state = LearningJobState.RUNNING.name,
        priority = 0,
        attempts = 1,
        maxAttempts = 3,
        notBeforeMs = 0,
        leaseProcessSessionId = "00000000-0000-0000-0000-000000000003",
        leaseWorkerId = "00000000-0000-0000-0000-000000000004",
        leaseGeneration = 1,
        leaseUntilMs = 100,
        lastErrorCode = null,
        createdAtMs = 0,
        updatedAtMs = 1,
        finishedAtMs = null,
        replayGeneration = 0,
    )

    private fun remoteProviderJob() = job().copy(
        id = "learning-p1-job-v1:${"9".repeat(64)}",
        jobType = LearningJobType.REFLECT_EPISODE_V1.name,
        dedupeKey = "learning-p1-job-dedupe-v1:${"9".repeat(64)}",
        algorithmIdentity = "reflection-v1",
        promptIdentity = "reflection-prompt-v1",
        providerKindIdentity = LearningJobProviderKindIdentity.REMOTE.wireCode,
        modelIdentity = "a".repeat(64),
        providerIdentity = "b".repeat(64),
        providerConfigurationIdentity = "c".repeat(64),
        providerConfigGeneration = 1,
        sourceSchemaIdentity = "reflection-input-v1:${"d".repeat(64)}",
        toolsetIdentity = "redacted-trace-v1",
        outputSchemaIdentity = "episode-lesson-v1",
    )

    private data object TestOutput : LearningJobTypedOutput {
        override val outputSchemaIdentity: String = "test-output-v1"
    }

    private data object RemoteOutput : LearningJobTypedOutput {
        override val outputSchemaIdentity: String = "episode-lesson-v1"
    }
}
