package me.rerere.rikkahub.data.ai.background

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.learning.model.LearningModelResolution
import me.rerere.rikkahub.learning.model.LearningModelResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsBackedBackgroundGenerationHostTest {
    @Test
    fun `production default deny does not key credentials or resolve a provider`() = runBlocking {
        val keyCalled = AtomicBoolean(false)
        val providerCalled = AtomicBoolean(false)
        val model = model()
        val provider = remoteProvider(model, apiKey = "must-not-be-read")
        val factory = BackgroundGenerationHostIdentityFactory { _, _, _ ->
            keyCalled.set(true)
            "f".repeat(64)
        }
        val public = factory.publicIdentity(provider, model)
        val frozen = resolvedRemote(public, configurationDigest = "e".repeat(64))
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(provider),
                    userPolicy = BackgroundGenerationUserPolicy(),
                )
            },
            identityFactory = factory,
            providerResolver = {
                providerCalled.set(true)
                fakeHandle(fenced = true)
            },
        )

        assertFalse(host.isAuthorized(frozen))
        assertEquals(
            BackgroundGenerationBindingResult.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            ),
            host.bind(frozen),
        )
        assertFalse(keyCalled.get())
        assertFalse(providerCalled.get())
    }

    @Test
    fun `exact LiteRT configuration drift is rejected by the dispatch fence`() = runBlocking {
        val model = model()
        var provider: ProviderSetting.LiteRtLocal = ProviderSetting.LiteRtLocal(
            enabled = true,
            models = listOf(model),
        )
        val factory = testIdentityFactory()
        val public = factory.publicIdentity(provider, model)
        val policy = BackgroundGenerationUserPolicy(
            backgroundWorkAuthorized = true,
            authorizedModelIdentityDigests = setOf(public.modelIdentityDigest),
        )
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(true, listOf(provider), policy)
            },
            identityFactory = factory,
            providerResolver = { fakeHandle(fenced = true) },
        )
        val claim = host.resolveForClaim(model.id)
        assertTrue(claim is LearningModelResolution.Resolved)
        val frozen = (claim as LearningModelResolution.Resolved).model
        val binding = host.bind(frozen)
        assertTrue(binding is BackgroundGenerationBindingResult.Bound)

        provider = provider.copy(
            models = listOf(model.copy(userContextWindowTokens = 8_192)),
        )

        val execution = (binding as BackgroundGenerationBindingResult.Bound).execution
        assertEquals(
            BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            execution.validateBeforeDispatch(),
        )
        assertFalse(host.isAuthorized(frozen))
    }

    @Test
    fun `unfenced providers stay typed unsupported even with exact authorization`() {
        val model = model()
        val provider = ProviderSetting.LiteRtLocal(enabled = true, models = listOf(model))
        val factory = testIdentityFactory()
        val public = factory.publicIdentity(provider, model)
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(provider),
                    userPolicy = BackgroundGenerationUserPolicy(
                        backgroundWorkAuthorized = true,
                        authorizedModelIdentityDigests = setOf(public.modelIdentityDigest),
                    ),
                )
            },
            identityFactory = factory,
            providerResolver = { fakeHandle(fenced = false) },
        )

        assertEquals(
            LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            ),
            host.resolveForClaim(model.id),
        )
    }

    @Test
    fun `single authorized model is the only claim-time selection`() {
        val model = model()
        val provider = ProviderSetting.LiteRtLocal(enabled = true, models = listOf(model))
        val factory = testIdentityFactory()
        val identity = factory.publicIdentity(provider, model)
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(provider),
                    userPolicy = BackgroundGenerationUserPolicy(
                        backgroundWorkAuthorized = true,
                        authorizedModelIdentityDigests = setOf(identity.modelIdentityDigest),
                    ),
                )
            },
            identityFactory = factory,
            providerResolver = { fakeHandle(fenced = true) },
        )

        assertTrue(host.resolveSingleAuthorizedForClaim() is LearningModelResolution.Resolved)
    }

    @Test
    fun `ambiguous authorized models fail closed instead of choosing heuristically`() {
        val first = model()
        val second = first.copy(id = Uuid.parse("10000000-0000-4000-8000-000000000002"))
        val provider = ProviderSetting.LiteRtLocal(
            enabled = true,
            models = listOf(first, second),
        )
        val factory = testIdentityFactory()
        val identities = provider.models.map { factory.publicIdentity(provider, it) }
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(provider),
                    userPolicy = BackgroundGenerationUserPolicy(
                        backgroundWorkAuthorized = true,
                        authorizedModelIdentityDigests = identities
                            .mapTo(linkedSetOf(), BackgroundGenerationPublicIdentity::modelIdentityDigest),
                    ),
                )
            },
            identityFactory = factory,
            providerResolver = { fakeHandle(fenced = true) },
        )

        assertEquals(
            LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION),
            host.resolveSingleAuthorizedForClaim(),
        )
    }

    @Test
    fun `secret configuration is keyed and never appears in identities`() {
        val model = model().copy(
            customHeaders = listOf(CustomHeader("Authorization", "header-secret")),
        )
        val factory = testIdentityFactory()
        val first = factory.identify(remoteProvider(model, "api-secret-one"), model)
        val second = factory.identify(remoteProvider(model, "api-secret-two"), model)

        assertEquals(first.providerIdentityDigest, second.providerIdentityDigest)
        assertEquals(first.modelIdentityDigest, second.modelIdentityDigest)
        assertNotEquals(first.configurationDigest, second.configurationDigest)
        assertFalse(first.toString().contains("secret"))
    }

    private fun model(): Model = Model(
        id = Uuid.parse("10000000-0000-4000-8000-000000000001"),
        modelId = "background-test-model",
        userContextWindowTokens = 4_096,
    )

    private fun remoteProvider(
        model: Model,
        apiKey: String,
    ): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
        id = Uuid.parse("20000000-0000-4000-8000-000000000002"),
        enabled = true,
        models = listOf(model),
        apiKey = apiKey,
    )

    private fun testIdentityFactory(): BackgroundGenerationHostIdentityFactory =
        BackgroundGenerationHostIdentityFactory { canonical, _, _ -> canonical }

    private fun fakeHandle(fenced: Boolean): BackgroundTextProviderHandle =
        object : BackgroundTextProviderHandle {
            override val cancellationFenceAbi: String? =
                if (fenced) "test-cancellation-fence-v1" else null

            override suspend fun resolveTrustedContextWindowTokens(model: Model): Int? = 4_096

            override suspend fun streamText(
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<MessageChunk> = emptyFlow()
        }
}

private fun resolvedRemote(
    identity: BackgroundGenerationPublicIdentity,
    configurationDigest: String,
): me.rerere.rikkahub.learning.model.ResolvedLearningModel =
    me.rerere.rikkahub.learning.model.ResolvedLearningModel(
        providerKind = identity.providerKind,
        providerIdentityDigest = identity.providerIdentityDigest,
        modelIdentityDigest = identity.modelIdentityDigest,
        configurationDigest = configurationDigest,
        route = me.rerere.rikkahub.learning.resources.LearningRouteCapabilities(
            executionClass = me.rerere.rikkahub.learning.resources.LearningExecutionClass.REMOTE_NETWORK,
            requiresNetwork = true,
            cancellation =
                me.rerere.rikkahub.learning.resources.LearningCancellationCapability.PROVEN_RELIABLE,
        ),
    )
