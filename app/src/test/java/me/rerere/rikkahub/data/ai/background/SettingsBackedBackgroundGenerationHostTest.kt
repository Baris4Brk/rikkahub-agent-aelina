package me.rerere.rikkahub.data.ai.background

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.BackgroundRuntimeAttestation
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RemoteBackgroundApiFamily
import me.rerere.ai.provider.RemoteBackgroundDispatchAttestation
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
    fun `local candidate listing is public explicit and fail closed`() {
        val keyCalled = AtomicBoolean(false)
        val providerCalled = AtomicBoolean(false)
        val eligible = model().copy(displayName = "  Local\nModel\u202e  ")
        val overwritten = model().copy(
            id = Uuid.parse("10000000-0000-4000-8000-000000000002"),
            displayName = "Overwritten",
            providerOverwrite = remoteProvider(model(), "not-readable"),
        )
        val disabled = model().copy(
            id = Uuid.parse("10000000-0000-4000-8000-000000000003"),
            displayName = "Disabled",
        )
        val local = ProviderSetting.LiteRtLocal(
            enabled = true,
            models = listOf(eligible, overwritten),
        )
        val factory = BackgroundGenerationHostIdentityFactory { _, _, _ ->
            keyCalled.set(true)
            "f".repeat(64)
        }
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(
                        local,
                        ProviderSetting.LiteRtLocal(enabled = false, models = listOf(disabled)),
                        remoteProvider(
                            model().copy(
                                id = Uuid.parse("10000000-0000-4000-8000-000000000004"),
                            ),
                            "not-readable",
                        ),
                    ),
                    userPolicy = BackgroundGenerationUserPolicy(),
                )
            },
            identityFactory = factory,
            providerResolver = {
                providerCalled.set(true)
                fakeHandle(fenced = true)
            },
        )

        val candidates = host.listLocalAuthorizationCandidates()

        assertEquals(1, candidates.size)
        assertEquals(eligible.id, candidates.single().modelUuid)
        assertEquals("Local Model", candidates.single().displayLabel)
        assertEquals(
            factory.publicIdentity(local, eligible).modelIdentityDigest,
            candidates.single().modelIdentityDigest,
        )
        assertEquals(
            LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.NO_CONFIGURATION,
            ),
            host.resolveSingleAuthorizedForClaim(),
        )
        assertFalse(keyCalled.get())
        assertFalse(providerCalled.get())
    }

    @Test
    fun `unified candidates include exact OpenCode Go but exclude relays and other paths`() {
        val localModel = model().copy(displayName = "Local")
        val goModel = model().copy(
            id = Uuid.parse("10000000-0000-4000-8000-000000000010"),
            modelId = "deepseek-v4-flash",
            displayName = "DeepSeek V4 Flash",
        )
        val excludedModel = model().copy(
            id = Uuid.parse("10000000-0000-4000-8000-000000000011"),
            displayName = "Excluded",
        )
        val local = ProviderSetting.LiteRtLocal(enabled = true, models = listOf(localModel))
        val go = ProviderSetting.OpenAI(
            enabled = true,
            name = "Configured name is not trusted",
            models = listOf(goModel),
            baseUrl = "https://opencode.ai/zen/go/v1/",
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false,
        )
        val genericOpenCode = ProviderSetting.OpenAI(
            enabled = true,
            models = listOf(excludedModel),
            baseUrl = "https://opencode.ai/v1",
        )
        val relay = ProviderSetting.OpenAI(
            enabled = true,
            models = listOf(excludedModel.copy(
                id = Uuid.parse("10000000-0000-4000-8000-000000000012"),
            )),
            baseUrl = "https://api.openai.com.example/v1",
        )
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(local, go, genericOpenCode, relay),
                    userPolicy = BackgroundGenerationUserPolicy(),
                )
            },
            identityFactory = testIdentityFactory(),
            providerResolver = { error("candidate_listing_must_not_resolve_provider") },
        )

        val candidates = host.listAuthorizationCandidates()

        assertEquals(2, candidates.size)
        assertEquals(
            setOf(
                BackgroundAuthorizationCandidateKind.LOCAL_LITERT,
                BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO,
            ),
            candidates.mapTo(linkedSetOf(), BackgroundAuthorizationCandidate::kind),
        )
        val remote = candidates.single(BackgroundAuthorizationCandidate::isRemote)
        assertEquals("OpenCode Go (official) / DeepSeek V4 Flash", remote.displayLabel)
        assertTrue(
            host.isExactOfficialRemoteReflectionTarget(
                remote.providerIdentityDigest,
                remote.modelIdentityDigest,
            ),
        )
    }

    @Test
    fun `official endpoint classifier rejects encoded suffixes and incompatible Go modes`() {
        fun openCode(
            baseUrl: String = "https://opencode.ai/zen/go/v1",
            useResponseApi: Boolean = false,
            path: String = "/chat/completions",
        ) = ProviderSetting.OpenAI(
            baseUrl = baseUrl,
            useResponseApi = useResponseApi,
            chatCompletionsPath = path,
        )

        assertEquals(
            BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO,
            openCode().officialBackgroundRemoteKindOrNull(),
        )
        assertEquals(null, openCode("https://opencode.ai/%7Aen/go/v1").officialBackgroundRemoteKindOrNull())
        assertEquals(null, openCode("https://user@opencode.ai/zen/go/v1").officialBackgroundRemoteKindOrNull())
        assertEquals(null, openCode("https://opencode.ai:444/zen/go/v1").officialBackgroundRemoteKindOrNull())
        assertEquals(null, openCode("https://opencode.ai/zen/go/v1?x=1").officialBackgroundRemoteKindOrNull())
        assertEquals(null, openCode(useResponseApi = true).officialBackgroundRemoteKindOrNull())
        assertEquals(null, openCode(path = "/responses").officialBackgroundRemoteKindOrNull())
    }

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
    fun `remote disclosure requires one exact authorized remote target and exposes no secrets`() {
        val model = model().copy(displayName = "  Remote\nModel\u202e ")
        val provider = remoteProvider(model, apiKey = "must-not-appear").copy(name = " Remote API ")
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
                        remoteReflectionProviderIdentityDigest = identity.providerIdentityDigest,
                        remoteReflectionModelIdentityDigest = identity.modelIdentityDigest,
                    ),
                )
            },
            identityFactory = factory,
            providerResolver = { error("disclosure_must_not_resolve_provider") },
        )

        val target = requireNotNull(host.remoteReflectionDisclosureTarget())
        assertEquals(identity.providerIdentityDigest, target.providerIdentityDigest)
        assertEquals(identity.modelIdentityDigest, target.modelIdentityDigest)
        assertEquals("OpenAI (official)", target.providerLabel)
        assertEquals("Remote Model", target.modelLabel)
        assertFalse(target.toString().contains("must-not-appear"))
    }

    @Test
    fun `remote disclosure fails closed for local ambiguous or unavailable selections`() {
        val first = model()
        val second = model().copy(id = Uuid.parse("10000000-0000-4000-8000-000000000099"))
        val provider = remoteProvider(first, "secret").copy(models = listOf(first, second))
        val factory = testIdentityFactory()
        val identities = provider.models.map { factory.publicIdentity(provider, it) }
        fun host(providerDigest: String?, modelDigest: String?) = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    true,
                    listOf(provider),
                    BackgroundGenerationUserPolicy(
                        backgroundWorkAuthorized = true,
                        remoteReflectionProviderIdentityDigest = providerDigest,
                        remoteReflectionModelIdentityDigest = modelDigest,
                    ),
                )
            },
            identityFactory = factory,
            providerResolver = { error("not_called") },
        )

        assertEquals(null, host(null, null).remoteReflectionDisclosureTarget())
        assertEquals(
            null,
            host(identities.first().providerIdentityDigest, "f".repeat(64))
                .remoteReflectionDisclosureTarget(),
        )
        assertEquals(2, host(null, null).listRemoteReflectionDisclosureTargets().size)
    }

    @Test
    fun `remote dispatch authorization is fenced to the exact disclosed provider model pair`() {
        val first = model()
        val second = first.copy(id = Uuid.parse("10000000-0000-4000-8000-000000000099"))
        val provider = remoteProvider(first, "secret").copy(models = listOf(first, second))
        val factory = testIdentityFactory()
        val firstIdentity = factory.publicIdentity(provider, first)
        val secondIdentity = factory.publicIdentity(provider, second)
        val host = SettingsBackedBackgroundGenerationHost(
            settingsSource = {
                BackgroundGenerationSettingsSnapshot(
                    initialized = true,
                    providers = listOf(provider),
                    userPolicy = BackgroundGenerationUserPolicy(
                        backgroundWorkAuthorized = true,
                        authorizedModelIdentityDigests = setOf(
                            firstIdentity.modelIdentityDigest,
                            secondIdentity.modelIdentityDigest,
                        ),
                        allowRemoteReflection = true,
                        remoteReflectionProviderIdentityDigest =
                            secondIdentity.providerIdentityDigest,
                        remoteReflectionModelIdentityDigest = secondIdentity.modelIdentityDigest,
                    ),
                )
            },
            identityFactory = factory,
            providerResolver = { fakeHandle(fenced = true, remote = true) },
        )

        assertFalse(
            host.isAuthorized(
                resolvedRemote(
                    firstIdentity,
                    factory.identify(provider, first).configurationDigest,
                ),
            ),
        )
        assertTrue(
            host.isAuthorized(
                resolvedRemote(
                    secondIdentity,
                    factory.identify(provider, second).configurationDigest,
                ),
            ),
        )
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
            providerResolver = { fakeHandle(fenced = true, attested = true) },
        )
        val claim = host.resolveForAttestedClaim(model.id)
        assertTrue(claim is LearningModelResolution.Resolved)
        val frozen = (claim as LearningModelResolution.Resolved).model
        assertEquals(
            runtimeAttestation("a".repeat(64)).opaqueDigestSha256(),
            frozen.runtimeAttestationDigest,
        )
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
    fun `single authorized model is the only attested claim-time selection`() = runBlocking {
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
            providerResolver = { fakeHandle(fenced = true, attested = true) },
        )

        assertTrue(
            host.resolveSingleAuthorizedForAttestedClaim() is LearningModelResolution.Resolved,
        )
    }

    @Test
    fun `ambiguous authorized models fail closed instead of choosing heuristically`() = runBlocking {
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
            host.resolveSingleAuthorizedForAttestedClaim(),
        )
    }

    @Test
    fun `LiteRT bind rejects same model when artifact attestation changes`() = runBlocking {
        val model = model()
        val provider = ProviderSetting.LiteRtLocal(enabled = true, models = listOf(model))
        val factory = testIdentityFactory()
        val public = factory.publicIdentity(provider, model)
        var runtime = runtimeAttestation("a".repeat(64))
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
            providerResolver = {
                fakeHandle(
                    fenced = true,
                    attested = true,
                    attestation = { runtime },
                )
            },
        )
        val claim = host.resolveForAttestedClaim(model.id)
        assertTrue(claim is LearningModelResolution.Resolved)
        val frozen = (claim as LearningModelResolution.Resolved).model

        runtime = runtimeAttestation("b".repeat(64))

        assertEquals(
            BackgroundGenerationBindingResult.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            ),
            host.bind(frozen),
        )
    }

    @Test
    fun `runtime attestation participates in keyed configuration identity`() {
        val model = model()
        val provider = ProviderSetting.LiteRtLocal(enabled = true, models = listOf(model))
        val factory = testIdentityFactory()

        val first = factory.identify(provider, model, runtimeAttestation("a".repeat(64)))
        val second = factory.identify(provider, model, runtimeAttestation("b".repeat(64)))

        assertNotEquals(first.configurationDigest, second.configurationDigest)
        assertEquals(first.providerIdentityDigest, second.providerIdentityDigest)
        assertEquals(first.modelIdentityDigest, second.modelIdentityDigest)
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

    private fun fakeHandle(
        fenced: Boolean,
        attested: Boolean = false,
        remote: Boolean = false,
        attestation: () -> BackgroundRuntimeAttestation = {
            runtimeAttestation("a".repeat(64))
        },
    ): BackgroundTextProviderHandle =
        object : BackgroundTextProviderHandle {
            override val cancellationFenceAbi: String? =
                if (!fenced) null else if (remote) {
                    RemoteBackgroundDispatchAttestation.CANCELLATION_FENCE_ABI
                } else {
                    "test-cancellation-fence-v1"
                }

            override val backgroundRuntimeAttestationAbi: String? =
                if (attested) "test-background-runtime-v1" else null

            override val remoteBackgroundDispatchAbi: String? =
                RemoteBackgroundDispatchAttestation.TRANSPORT_ABI.takeIf { remote }

            override val remoteBackgroundApiFamily: RemoteBackgroundApiFamily? =
                RemoteBackgroundApiFamily.OPENAI_CHAT_COMPLETIONS_V1.takeIf { remote }

            override suspend fun attestBackgroundRuntime(
                model: Model,
            ): BackgroundRuntimeAttestation? = if (attested) attestation() else null

            override suspend fun resolveTrustedContextWindowTokens(model: Model): Int? = 4_096

            override suspend fun streamText(
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<MessageChunk> = emptyFlow()
        }

    companion object {
        private fun runtimeAttestation(artifact: String): BackgroundRuntimeAttestation =
            BackgroundRuntimeAttestation(
                providerRuntimeAbi = "test-background-runtime-v1",
                sdkAbi = "test-sdk-v1",
                cancellationFenceAbi = "test-cancellation-fence-v1",
                artifactSha256 = artifact,
                forceCpu = true,
                accelerator = "CPU",
                contextWindowTokens = 4_096,
                topK = 64,
                topP = 0.95,
                temperature = 1.0,
                promptRendererAbi = "test-prompt-v1",
                nativeToolAbi = "test-native-v1",
            )
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
