package me.rerere.rikkahub.data.ai.background

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.rikkahub.learning.model.LearningProviderKind
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import me.rerere.rikkahub.learning.privacy.LearningOutboundFieldCategory
import me.rerere.rikkahub.learning.resources.LearningCancellationCapability
import me.rerere.rikkahub.learning.resources.LearningDeviceConditions
import me.rerere.rikkahub.learning.resources.LearningExecutionClass
import me.rerere.rikkahub.learning.resources.LearningForegroundPreemption
import me.rerere.rikkahub.learning.resources.LearningForegroundRegistry
import me.rerere.rikkahub.learning.resources.LearningForegroundWorkKind
import me.rerere.rikkahub.learning.resources.LearningResourceGovernor
import me.rerere.rikkahub.learning.resources.LearningRouteCapabilities
import me.rerere.rikkahub.learning.resources.LearningSignal
import me.rerere.rikkahub.learning.resources.LearningThermalState
import me.rerere.rikkahub.learning.resources.LearningYieldReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackgroundGenerationClientTest {
    @Test
    fun resourceAdmissionHappensBeforeExecutionTimeBinding() = runBlocking {
        val registry = LearningForegroundRegistry()
        val bindCalled = AtomicBoolean(false)
        val governor = LearningResourceGovernor(
            foregroundRegistry = registry,
            conditionsSource = { allowedConditions().copy(userAllowsBackgroundWork = false) },
            admissionWaitMs = 250,
        )
        val client = BackgroundGenerationClient(
            governor = governor,
            binder = BackgroundGenerationBinder {
                bindCalled.set(true)
                BackgroundGenerationBindingResult.Unavailable(
                    BackgroundBindingUnavailableReason.NO_CONFIGURATION,
                )
            },
            authorizationGate = BackgroundGenerationAuthorizationGate { true },
        )

        val result = client.generate(request(frozenModel()))

        assertEquals(
            BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Resource(LearningYieldReason.USER_DISABLED),
            ),
            result,
        )
        assertFalse(bindCalled.get())
    }

    @Test
    fun foregroundThatStartsDuringBindingIsCaughtByDispatchFence() = runBlocking {
        val registry = LearningForegroundRegistry()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = { successfulFlow("must-not-run") },
        )
        var foregroundLease: AutoCloseable? = null
        val client = BackgroundGenerationClient(
            governor = LearningResourceGovernor(
                foregroundRegistry = registry,
                conditionsSource = { allowedConditions() },
                admissionWaitMs = 250,
            ),
            binder = BackgroundGenerationBinder {
                foregroundLease = registry.enter(LearningForegroundWorkKind.CONVERSATION_EXECUTION)
                BackgroundGenerationBindingResult.Bound(execution)
            },
            authorizationGate = BackgroundGenerationAuthorizationGate {
                it == execution.frozenModel
            },
        )

        val result = try {
            client.generate(request(execution.frozenModel))
        } finally {
            foregroundLease?.close()
        }

        assertEquals(
            BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Resource(LearningYieldReason.FOREGROUND_ACTIVE),
            ),
            result,
        )
        assertFalse(execution.called.get())
    }

    @Test
    fun requestIsBoundedRedactedAndProviderReceivesStrictToolFreeProjection() = runBlocking {
        val registry = LearningForegroundRegistry()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            model = Model(
                modelId = "fake-chat",
                userContextWindowTokens = 4_096,
                tools = setOf(BuiltInTools.Search),
                customHeaders = listOf(CustomHeader("X-Unsafe-Override", "secret")),
            ),
            trustedWindow = 4_096,
            streamFactory = { successfulFlow("accepted") },
        )
        val client = client(registry, execution)
        val request = request(execution.frozenModel, maxOutputTokens = 128)

        val result = client.generate(request)

        assertTrue(result is BackgroundGenerationResult.Success)
        result as BackgroundGenerationResult.Success
        assertEquals("accepted", result.text)
        assertEquals(128, result.effectiveMaxOutputTokens)
        val params = assertNotNullValue(execution.observedParams)
        assertTrue(params.tools.isEmpty())
        assertTrue(params.model.tools.isEmpty())
        assertTrue(params.model.customHeaders.isEmpty())
        assertTrue(params.customBody.isEmpty())
        assertTrue(params.customHeaders.isEmpty())
        assertEquals(null, params.providerCacheIdentity)
        assertEquals(128, params.maxTokens)
        val messages = assertNotNullValue(execution.observedMessages)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), messages.map { it.role })
        assertFalse(request.prompt.toString().contains("private-payload"))
        assertFalse(request.toString().contains("private-payload"))
        assertFalse(result.toString().contains("accepted"))
    }

    @Test
    fun strictLosslessRejectsOutputClampBeforeProviderDispatch() = runBlocking {
        val registry = LearningForegroundRegistry()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            model = Model(modelId = "small-window", userContextWindowTokens = 512),
            trustedWindow = 512,
            streamFactory = { successfulFlow("must-not-run") },
        )
        val result = client(registry, execution).generate(
            request(execution.frozenModel, maxOutputTokens = 480),
        )

        assertEquals(
            BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.CONTEXT_REQUIRES_EXPLICIT_ADJUSTMENT,
            ),
            result,
        )
        assertFalse(execution.called.get())
    }

    @Test
    fun outputByteLimitCancelsCollectionAndReturnsContentFreeFailure() = runBlocking {
        val registry = LearningForegroundRegistry()
        val streamCancelled = AtomicBoolean(false)
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = {
                flow {
                    try {
                        emit(textChunk("12345", terminal = false))
                        awaitCancellation()
                    } finally {
                        streamCancelled.set(true)
                    }
                }
            },
        )

        val result = client(registry, execution).generate(
            request(
                frozenModel = execution.frozenModel,
                maxOutputTokens = 64,
                maxOutputUtf8Bytes = 4,
            ),
        )

        assertEquals(
            BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.OUTPUT_BYTE_LIMIT,
                BackgroundProviderAttemptState.DISPATCH_STARTED,
            ),
            result,
        )
        assertTrue(streamCancelled.get())
    }

    @Test
    fun foregroundArrivalCancelsProviderAndReturnsRetryDeferral() = runBlocking {
        val registry = LearningForegroundRegistry()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = {
                flow {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            },
        )
        val pending = async {
            client(registry, execution).generate(request(execution.frozenModel))
        }
        withTimeout(2_000) { started.await() }

        val foreground = registry.enter(LearningForegroundWorkKind.PET_DIALOGUE)
        val result = try {
            withTimeout(2_000) { pending.await() }
        } finally {
            foreground.close()
        }

        assertEquals(
            BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Foreground(
                    LearningForegroundPreemption.FOREGROUND_STARTED,
                ),
                BackgroundProviderAttemptState.DISPATCH_STARTED,
            ),
            result,
        )
        withTimeout(2_000) { cancelled.await() }
    }

    @Test
    fun callerCancellationPropagatesAndCancelsProvider() = runBlocking {
        val registry = LearningForegroundRegistry()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = {
                flow {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            },
        )
        val pending = async {
            client(registry, execution).generate(request(execution.frozenModel))
        }
        withTimeout(2_000) { started.await() }

        pending.cancel(CancellationException("caller_cancelled"))
        try {
            pending.await()
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
            Unit
        }
        withTimeout(2_000) { cancelled.await() }
    }

    @Test
    fun requestTimeoutCancelsProviderAndReturnsContentFreeFailure() = runBlocking {
        val registry = LearningForegroundRegistry()
        val cancelled = CompletableDeferred<Unit>()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = {
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            },
        )

        val result = client(registry, execution).generate(
            request(execution.frozenModel).copy(timeoutMs = 25L),
        )

        assertEquals(
            BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.PROVIDER_TIMEOUT,
                BackgroundProviderAttemptState.DISPATCH_STARTED,
            ),
            result,
        )
        withTimeout(2_000) { cancelled.await() }
    }

    @Test
    fun enclosingCallerTimeoutIsNotMisreportedAsProviderTimeout() = runBlocking {
        val registry = LearningForegroundRegistry()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = { flow { awaitCancellation() } },
        )

        try {
            withTimeout(25L) {
                client(registry, execution).generate(request(execution.frozenModel))
            }
            fail("enclosing TimeoutCancellationException must propagate")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Unit
        }
    }

    @Test
    fun executionIdentityMismatchNeverReachesProvider() = runBlocking {
        val registry = LearningForegroundRegistry()
        val requested = frozenModel()
        val execution = FakeExecution(
            frozenModel = requested.copy(configurationDigest = "d".repeat(64)),
            streamFactory = { successfulFlow("must-not-run") },
        )

        val result = BackgroundGenerationClient(
            governor = LearningResourceGovernor(
                foregroundRegistry = registry,
                conditionsSource = { allowedConditions() },
                admissionWaitMs = 250,
            ),
            binder = BackgroundGenerationBinder {
                BackgroundGenerationBindingResult.Bound(execution)
            },
            authorizationGate = BackgroundGenerationAuthorizationGate { it == requested },
        ).generate(request(requested))

        assertEquals(
            BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
                ),
            ),
            result,
        )
        assertFalse(execution.called.get())
    }

    @Test
    fun missingLiveAuthorizationFailsClosedBeforeBinding() = runBlocking {
        val registry = LearningForegroundRegistry()
        val bindCalled = AtomicBoolean(false)
        val client = BackgroundGenerationClient(
            governor = LearningResourceGovernor(
                foregroundRegistry = registry,
                conditionsSource = { allowedConditions() },
                admissionWaitMs = 250,
            ),
            binder = BackgroundGenerationBinder {
                bindCalled.set(true)
                BackgroundGenerationBindingResult.Unavailable(
                    BackgroundBindingUnavailableReason.NO_CONFIGURATION,
                )
            },
        )

        assertEquals(
            BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
                ),
            ),
            client.generate(request(frozenModel())),
        )
        assertFalse(bindCalled.get())
    }

    @Test
    fun liveAuthorizationRevocationDuringBindingPreventsDispatch() = runBlocking {
        val registry = LearningForegroundRegistry()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = { successfulFlow("must-not-run") },
        )
        var authorized = true
        val client = BackgroundGenerationClient(
            governor = LearningResourceGovernor(
                foregroundRegistry = registry,
                conditionsSource = { allowedConditions() },
                admissionWaitMs = 250,
            ),
            binder = BackgroundGenerationBinder {
                authorized = false
                BackgroundGenerationBindingResult.Bound(execution)
            },
            authorizationGate = BackgroundGenerationAuthorizationGate { candidate ->
                authorized && candidate == execution.frozenModel
            },
        )

        assertEquals(
            BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
                ),
            ),
            client.generate(request(execution.frozenModel)),
        )
        assertFalse(execution.called.get())
    }

    @Test
    fun providerExceptionAfterDispatchIsNotMarkedSafeForBlindRetry() = runBlocking {
        val registry = LearningForegroundRegistry()
        val execution = FakeExecution(
            frozenModel = frozenModel(),
            streamFactory = { throw IllegalStateException("synthetic transport failure") },
        )

        assertEquals(
            BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.PROVIDER_FAILURE,
                BackgroundProviderAttemptState.DISPATCH_STARTED,
            ),
            client(registry, execution).generate(request(execution.frozenModel)),
        )
        assertTrue(execution.called.get())
    }

    private fun client(
        registry: LearningForegroundRegistry,
        execution: FakeExecution,
    ): BackgroundGenerationClient {
        val governor = LearningResourceGovernor(
            foregroundRegistry = registry,
            conditionsSource = { allowedConditions() },
            admissionWaitMs = 250,
        )
        return BackgroundGenerationClient(
            governor = governor,
            binder = BackgroundGenerationBinder {
                BackgroundGenerationBindingResult.Bound(execution)
            },
            authorizationGate = BackgroundGenerationAuthorizationGate {
                it == execution.frozenModel
            },
        )
    }

    private fun request(
        frozenModel: ResolvedLearningModel,
        maxOutputTokens: Int = 128,
        maxOutputUtf8Bytes: Int = 4_096,
    ): BackgroundGenerationRequestV1 = BackgroundGenerationRequestV1(
        prompt = BoundedRedactedBackgroundPromptV1.fromRedacted(
            systemText = "bounded-system-contract",
            payloadText = "private-payload",
            redactionPolicyVersion = "test-v1",
            fieldCategories = setOf(LearningOutboundFieldCategory.REDACTED_TASK_FEATURES),
        ),
        frozenModel = frozenModel,
        templateVersion = "test-template-v1",
        maxOutputTokens = maxOutputTokens,
        maxOutputUtf8Bytes = maxOutputUtf8Bytes,
        timeoutMs = 5_000,
    )

    private fun frozenModel(): ResolvedLearningModel = ResolvedLearningModel(
        providerKind = LearningProviderKind.REMOTE,
        providerIdentityDigest = "a".repeat(64),
        modelIdentityDigest = "b".repeat(64),
        configurationDigest = "c".repeat(64),
        route = LearningRouteCapabilities(
            executionClass = LearningExecutionClass.REMOTE_NETWORK,
            requiresNetwork = true,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
        ),
    )

    private fun allowedConditions(): LearningDeviceConditions = LearningDeviceConditions(
        userAllowsBackgroundWork = true,
        batterySaver = LearningSignal.NO,
        thermalState = LearningThermalState.NOMINAL,
        networkValidated = LearningSignal.YES,
        networkMetered = LearningSignal.NO,
        userAllowsMeteredNetwork = false,
    )

    private fun successfulFlow(text: String): Flow<MessageChunk> = flowOf(textChunk(text))

    private fun textChunk(text: String, terminal: Boolean = true): MessageChunk = MessageChunk(
        id = "chunk",
        model = "fake",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage.assistant(text),
                message = null,
                finishReason = if (terminal) "stop" else null,
            ),
        ),
        terminal = if (terminal) {
            GenerationTerminal(terminalSeen = true, category = FinishCategory.STOP)
        } else {
            null
        },
    )

    private class FakeExecution(
        override val frozenModel: ResolvedLearningModel,
        override val model: Model = Model(
            modelId = "fake-chat",
            userContextWindowTokens = 4_096,
        ),
        private val trustedWindow: Int? = 4_096,
        private val streamFactory: suspend () -> Flow<MessageChunk>,
    ) : BackgroundGenerationExecution {
        val called = AtomicBoolean(false)
        var observedMessages: List<UIMessage>? = null
        var observedParams: TextGenerationParams? = null

        override suspend fun resolveTrustedContextWindowTokens(): Int? = trustedWindow

        override suspend fun streamText(
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> {
            called.set(true)
            observedMessages = messages
            observedParams = params
            return streamFactory()
        }
    }
}

private fun <T> assertNotNullValue(value: T?): T {
    assertNotNull(value)
    return requireNotNull(value)
}
