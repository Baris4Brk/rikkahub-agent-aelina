package me.rerere.rikkahub.learning.jobs

import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

    private data object TestOutput : LearningJobTypedOutput {
        override val outputSchemaIdentity: String = "test-output-v1"
    }
}
