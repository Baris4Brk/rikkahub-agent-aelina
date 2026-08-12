package me.rerere.rikkahub.data.ai.background

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.FencedTextGenerationProvider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.execution.ExecutionTokenProvider
import me.rerere.rikkahub.learning.model.LearningModelCandidate
import me.rerere.rikkahub.learning.model.LearningModelResolution
import me.rerere.rikkahub.learning.model.LearningModelResolutionFailure
import me.rerere.rikkahub.learning.model.LearningModelResolutionPolicy
import me.rerere.rikkahub.learning.model.LearningModelResolver
import me.rerere.rikkahub.learning.model.LearningProviderKind
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import kotlin.uuid.Uuid

private const val BACKGROUND_HOST_IDENTITY_ABI = "background-host-identity-v1"
private val SAFE_CAPABILITY_ABI = Regex("^[A-Za-z0-9._-]{1,64}$")
private val LOWER_SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * Explicit, model-scoped user policy. There is intentionally no broad "all configured models"
 * fallback: a future UI must persist the exact model identity selected for background work.
 */
data class BackgroundGenerationUserPolicy(
    val backgroundWorkAuthorized: Boolean = false,
    val authorizedModelIdentityDigests: Set<String> = emptySet(),
    val allowRemoteReflection: Boolean = false,
) {
    init {
        require(authorizedModelIdentityDigests.all(LOWER_SHA256::matches)) {
            "Invalid authorized background model identity"
        }
    }
}

fun interface BackgroundGenerationUserPolicySource {
    fun current(): BackgroundGenerationUserPolicy
}

/** Production P0 policy. It cannot infer consent from Chat, Memory, or Dreaming settings. */
object DisabledBackgroundGenerationUserPolicySource : BackgroundGenerationUserPolicySource {
    override fun current(): BackgroundGenerationUserPolicy = BackgroundGenerationUserPolicy()
}

/**
 * One immutable host snapshot. Provider objects are referenced, not serialized or copied, so API
 * keys and custom headers never enter a second settings representation. Its string form is always
 * content-free.
 */
class BackgroundGenerationSettingsSnapshot internal constructor(
    internal val initialized: Boolean,
    internal val providers: List<ProviderSetting>,
    internal val userPolicy: BackgroundGenerationUserPolicy,
) {
    override fun toString(): String =
        "BackgroundGenerationSettingsSnapshot(initialized=$initialized, " +
            "providerCount=${providers.size}, policy=<redacted>)"
}

fun interface BackgroundGenerationSettingsSource {
    fun current(): BackgroundGenerationSettingsSnapshot
}

/** Reads the live SettingsStore value at claim, bind, authorization, and pre-dispatch time. */
class SettingsStoreBackgroundGenerationSettingsSource(
    private val settingsStore: SettingsStore,
    private val userPolicySource: BackgroundGenerationUserPolicySource,
) : BackgroundGenerationSettingsSource {
    override fun current(): BackgroundGenerationSettingsSnapshot {
        val settings = settingsStore.settingsFlow.value
        return BackgroundGenerationSettingsSnapshot(
            initialized = !settings.init,
            providers = settings.providers,
            userPolicy = userPolicySource.current(),
        )
    }
}

/** Content-free identities frozen into a durable Learning job. */
data class BackgroundGenerationHostIdentity(
    val providerKind: LearningProviderKind,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    /** Keyed digest: secrets cannot be tested offline from the durable value. */
    val configurationDigest: String,
) {
    init {
        require(providerIdentityDigest.matches(LOWER_SHA256))
        require(modelIdentityDigest.matches(LOWER_SHA256))
        require(configurationDigest.matches(LOWER_SHA256))
    }

    override fun toString(): String =
        "BackgroundGenerationHostIdentity(providerKind=$providerKind, identity=<redacted>)"
}

data class BackgroundGenerationPublicIdentity(
    val providerKind: LearningProviderKind,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
)

/** Converts a secret-bearing canonical digest into a durable, app-keyed opaque digest. */
fun interface BackgroundGenerationConfigurationKeyer {
    fun key(
        canonicalConfigurationDigest: String,
        providerId: String,
        modelId: String,
    ): String
}

/**
 * Uses the existing Keystore-backed host token primitive with domain separation. The intermediate
 * SHA-256 over provider credentials/configuration never leaves this call; only the two keyed
 * 128-bit halves are persisted as the 256-bit configuration identity.
 */
class KeystoreBackgroundGenerationConfigurationKeyer(
    private val tokens: ExecutionTokenProvider,
) : BackgroundGenerationConfigurationKeyer {
    override fun key(
        canonicalConfigurationDigest: String,
        providerId: String,
        modelId: String,
    ): String {
        require(canonicalConfigurationDigest.matches(LOWER_SHA256))
        val domain = "bgcfg1_$canonicalConfigurationDigest"
        val left = tokens.ownerTokenFor(domain, providerId, modelId, "left")
        val right = tokens.ownerTokenFor(domain, providerId, modelId, "right")
        return (left + right).also { keyed ->
            require(keyed.matches(LOWER_SHA256)) { "Invalid keyed configuration identity" }
        }
    }
}

/**
 * Versioned canonical identity factory. Provider/model IDs are safe SHA-256 identities;
 * request-affecting configuration (including credentials and custom request fields) is fed
 * incrementally into a keyed digest and is never materialized as JSON or logged.
 */
class BackgroundGenerationHostIdentityFactory(
    private val configurationKeyer: BackgroundGenerationConfigurationKeyer,
) {
    fun publicIdentity(
        provider: ProviderSetting,
        model: Model,
    ): BackgroundGenerationPublicIdentity {
        val providerKind = provider.learningKind()
        val providerIdentity = CanonicalSha256()
            .string("abi", BACKGROUND_HOST_IDENTITY_ABI)
            .string("type", provider.typeTag())
            .string("provider_id", provider.id.toString())
            .finishHex()
        val modelIdentity = CanonicalSha256()
            .string("abi", BACKGROUND_HOST_IDENTITY_ABI)
            .string("provider_identity", providerIdentity)
            .string("model_id_uuid", model.id.toString())
            .string("provider_model_id", model.modelId)
            .finishHex()
        return BackgroundGenerationPublicIdentity(
            providerKind = providerKind,
            providerIdentityDigest = providerIdentity,
            modelIdentityDigest = modelIdentity,
        )
    }

    fun identify(
        provider: ProviderSetting,
        model: Model,
    ): BackgroundGenerationHostIdentity {
        val public = publicIdentity(provider, model)
        val canonicalConfiguration = CanonicalSha256()
            .string("abi", BACKGROUND_HOST_IDENTITY_ABI)
            .providerConfiguration(provider)
            .modelConfiguration(model)
            .finishHex()
        val keyedConfiguration = configurationKeyer.key(
            canonicalConfigurationDigest = canonicalConfiguration,
            providerId = provider.id.toString(),
            modelId = model.id.toString(),
        )
        return BackgroundGenerationHostIdentity(
            providerKind = public.providerKind,
            providerIdentityDigest = public.providerIdentityDigest,
            modelIdentityDigest = public.modelIdentityDigest,
            configurationDigest = keyedConfiguration,
        )
    }
}

/** ProviderManager/credential state stays behind this request-time handle. */
interface BackgroundTextProviderHandle {
    val cancellationFenceAbi: String?

    suspend fun resolveTrustedContextWindowTokens(model: Model): Int?

    suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}

fun interface BackgroundTextProviderResolver {
    fun resolve(providerSetting: ProviderSetting): BackgroundTextProviderHandle?
}

/** Real ProviderManager adapter; it never copies ProviderSetting credentials. */
class ProviderManagerBackgroundTextProviderResolver(
    private val providerManager: ProviderManager,
) : BackgroundTextProviderResolver {
    override fun resolve(providerSetting: ProviderSetting): BackgroundTextProviderHandle? =
        try {
            when (providerSetting) {
                is ProviderSetting.OpenAI -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.Google -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.Claude -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.AICore -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.LiteRtLocal -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.Codex -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
            }
        } catch (_: Exception) {
            null
        }

    private fun <T : ProviderSetting> wrap(
        setting: T,
        provider: Provider<T>,
    ): BackgroundTextProviderHandle = object : BackgroundTextProviderHandle {
        override val cancellationFenceAbi: String? =
            (provider as? FencedTextGenerationProvider)?.cancellationFenceAbi
                ?.takeIf(SAFE_CAPABILITY_ABI::matches)

        override suspend fun resolveTrustedContextWindowTokens(model: Model): Int? =
            provider.resolveTrustedContextWindowTokens(setting, model)

        override suspend fun streamText(
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = provider.streamText(setting, messages, params)
    }
}

/**
 * Shared production host for claim-time resolution, execution-time binding, live authorization,
 * and the final dispatch fence. P0's policy source is disabled, so merely registering this host
 * cannot issue a provider request or enable any Learning feature flag.
 */
class SettingsBackedBackgroundGenerationHost(
    private val settingsSource: BackgroundGenerationSettingsSource,
    private val identityFactory: BackgroundGenerationHostIdentityFactory,
    private val providerResolver: BackgroundTextProviderResolver,
) : BackgroundGenerationBinder, BackgroundGenerationAuthorizationGate {

    /**
     * Claim-time selection for Learning. Ambiguous authorization is fail-closed: a future UI may
     * persist a preferred model, but the runtime never chooses one heuristically.
     */
    fun resolveSingleAuthorizedForClaim(): LearningModelResolution {
        val snapshot = safeSnapshot()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val matches = snapshot.providers.flatMap { provider ->
            provider.models.mapNotNull { model ->
                val public = runCatching { identityFactory.publicIdentity(provider, model) }
                    .getOrNull() ?: return@mapNotNull null
                if (public.modelIdentityDigest in snapshot.userPolicy.authorizedModelIdentityDigests) {
                    model.id
                } else {
                    null
                }
            }
        }.distinct()
        val selected = matches.singleOrNull()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.NO_CONFIGURATION,
            )
        return resolveForClaim(selected)
    }

    /** Future P1 claimers use this; P0 receives BACKGROUND_NOT_AUTHORIZED without keying secrets. */
    fun resolveForClaim(modelId: Uuid): LearningModelResolution {
        val snapshot = safeSnapshot()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val match = snapshot.findByModelId(modelId)
            ?: return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        val (provider, model) = match
        if (provider is ProviderSetting.AICore) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.AICORE_EXCLUDED)
        }
        if (!provider.enabled || model.providerOverwrite != null) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val public = identityFactory.publicIdentity(provider, model)
        val policy = snapshot.userPolicy
        if (
            !policy.backgroundWorkAuthorized ||
            public.modelIdentityDigest !in policy.authorizedModelIdentityDigests
        ) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        val handle = providerResolver.resolve(provider)
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        if (handle.cancellationFenceAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        }
        val identity = try {
            identityFactory.identify(provider, model)
        } catch (_: Exception) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.INVALID_IDENTITY,
            )
        }
        return LearningModelResolver.resolve(
            candidate = LearningModelCandidate(
                providerKind = identity.providerKind,
                providerIdentityDigest = identity.providerIdentityDigest,
                modelIdentityDigest = identity.modelIdentityDigest,
                configurationDigest = identity.configurationDigest,
                userExplicitlyAuthorizedForBackground = true,
            ),
            policy = LearningModelResolutionPolicy(
                allowRemoteReflection = policy.allowRemoteReflection,
                providerIdentityDigestsWithProvenCancellation = setOf(
                    identity.providerIdentityDigest,
                ),
            ),
        )
    }

    override suspend fun bind(
        frozenModel: ResolvedLearningModel,
    ): BackgroundGenerationBindingResult = when (val resolution = resolveExact(frozenModel)) {
        is HostResolution.Unavailable -> BackgroundGenerationBindingResult.Unavailable(
            resolution.reason,
        )
        is HostResolution.Ready -> BackgroundGenerationBindingResult.Bound(
            HostExecution(
                frozenModel = frozenModel,
                model = resolution.model,
                handle = resolution.handle,
                liveValidation = { validateExact(frozenModel) },
            ),
        )
    }

    override fun isAuthorized(frozenModel: ResolvedLearningModel): Boolean =
        resolveExact(frozenModel) is HostResolution.Ready

    private fun validateExact(
        frozenModel: ResolvedLearningModel,
    ): BackgroundBindingUnavailableReason? = when (val resolution = resolveExact(frozenModel)) {
        is HostResolution.Ready -> null
        is HostResolution.Unavailable -> resolution.reason
    }

    private fun resolveExact(frozenModel: ResolvedLearningModel): HostResolution {
        val snapshot = safeSnapshot()
            ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.NO_CONFIGURATION)
        }
        if (frozenModel.providerKind == LearningProviderKind.AICORE) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER)
        }
        val match = snapshot.findByPublicIdentity(frozenModel, identityFactory)
            ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        val (provider, model) = match
        val policy = snapshot.userPolicy
        if (
            !policy.backgroundWorkAuthorized ||
            frozenModel.modelIdentityDigest !in policy.authorizedModelIdentityDigests
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        if (!provider.enabled) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.PROVIDER_DISABLED)
        }
        if (provider is ProviderSetting.AICore || model.providerOverwrite != null) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER)
        }
        if (
            frozenModel.providerKind == LearningProviderKind.REMOTE &&
            !policy.allowRemoteReflection
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        val handle = providerResolver.resolve(provider)
            ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER,
            )
        if (handle.cancellationFenceAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CANCELLATION_UNSAFE,
            )
        }
        val currentIdentity = try {
            identityFactory.identify(provider, model)
        } catch (_: Exception) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        }
        if (
            currentIdentity.providerKind != frozenModel.providerKind ||
            currentIdentity.providerIdentityDigest != frozenModel.providerIdentityDigest ||
            currentIdentity.modelIdentityDigest != frozenModel.modelIdentityDigest ||
            !constantTimeDigestEquals(
                currentIdentity.configurationDigest,
                frozenModel.configurationDigest,
            )
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        }
        return HostResolution.Ready(model = model, handle = handle)
    }

    private fun safeSnapshot(): BackgroundGenerationSettingsSnapshot? = try {
        settingsSource.current()
    } catch (_: Exception) {
        null
    }
}

private sealed interface HostResolution {
    data class Ready(
        val model: Model,
        val handle: BackgroundTextProviderHandle,
    ) : HostResolution

    data class Unavailable(
        val reason: BackgroundBindingUnavailableReason,
    ) : HostResolution
}

private class HostExecution(
    override val frozenModel: ResolvedLearningModel,
    override val model: Model,
    private val handle: BackgroundTextProviderHandle,
    private val liveValidation: () -> BackgroundBindingUnavailableReason?,
) : BackgroundGenerationExecution {
    override suspend fun resolveTrustedContextWindowTokens(): Int? =
        handle.resolveTrustedContextWindowTokens(model)

    override fun validateBeforeDispatch(): BackgroundBindingUnavailableReason? =
        liveValidation()

    override suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        check(liveValidation() == null) { "Background binding changed before dispatch" }
        return handle.streamText(messages, params)
    }
}

private fun BackgroundGenerationSettingsSnapshot.findByModelId(
    modelId: Uuid,
): Pair<ProviderSetting, Model>? {
    val matches = providers.flatMap { provider ->
        provider.models.filter { it.id == modelId }.map { provider to it }
    }
    return matches.singleOrNull()
}

private fun BackgroundGenerationSettingsSnapshot.findByPublicIdentity(
    frozen: ResolvedLearningModel,
    identityFactory: BackgroundGenerationHostIdentityFactory,
): Pair<ProviderSetting, Model>? {
    val matches = providers.flatMap { provider ->
        provider.models.mapNotNull { model ->
            val identity = try {
                identityFactory.publicIdentity(provider, model)
            } catch (_: Exception) {
                null
            }
            if (
                identity?.providerKind == frozen.providerKind &&
                identity.providerIdentityDigest == frozen.providerIdentityDigest &&
                identity.modelIdentityDigest == frozen.modelIdentityDigest
            ) {
                provider to model
            } else {
                null
            }
        }
    }
    return matches.singleOrNull()
}

private fun ProviderSetting.learningKind(): LearningProviderKind = when (this) {
    is ProviderSetting.AICore -> LearningProviderKind.AICORE
    is ProviderSetting.LiteRtLocal -> LearningProviderKind.LOCAL_LITERT
    else -> LearningProviderKind.REMOTE
}

private fun ProviderSetting.typeTag(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
    is ProviderSetting.AICore -> "aicore"
    is ProviderSetting.LiteRtLocal -> "local_litert"
    is ProviderSetting.Codex -> "codex"
}

private fun CanonicalSha256.providerConfiguration(
    provider: ProviderSetting,
): CanonicalSha256 {
    string("provider_type", provider.typeTag())
    string("provider_id", provider.id.toString())
    bool("provider_enabled", provider.enabled)
    when (provider) {
        is ProviderSetting.OpenAI -> {
            string("openai_api_key", provider.apiKey)
            string("openai_base_url", provider.baseUrl)
            string("openai_chat_path", provider.chatCompletionsPath)
            bool("openai_responses", provider.useResponseApi)
            bool("openai_prompt_cache", provider.promptCaching)
            bool("openai_history_reasoning", provider.includeHistoryReasoning)
            nullableString("openrouter_sort", provider.routing.sort)
            strings("openrouter_order", provider.routing.order)
            strings("openrouter_only", provider.routing.only)
            strings("openrouter_ignore", provider.routing.ignore)
            bool("openrouter_fallbacks", provider.routing.allowFallbacks)
            bool("openrouter_require_params", provider.routing.requireParameters)
            nullableString("openrouter_data_collection", provider.routing.dataCollection)
            bool("openrouter_zdr", provider.routing.zdr)
            strings("openrouter_quantizations", provider.routing.quantizations)
            nullableDouble("openrouter_max_prompt", provider.routing.maxPricePrompt)
            nullableDouble("openrouter_max_completion", provider.routing.maxPriceCompletion)
        }
        is ProviderSetting.Google -> {
            string("google_api_key", provider.apiKey)
            string("google_base_url", provider.baseUrl)
            bool("google_vertex", provider.vertexAI)
            bool("google_service_account", provider.useServiceAccount)
            string("google_private_key", provider.privateKey)
            string("google_service_email", provider.serviceAccountEmail)
            string("google_location", provider.location)
            string("google_project", provider.projectId)
        }
        is ProviderSetting.Claude -> {
            string("claude_api_key", provider.apiKey)
            string("claude_base_url", provider.baseUrl)
            bool("claude_prompt_cache", provider.promptCaching)
            string("claude_cache_ttl", provider.promptCacheTtl.name)
        }
        is ProviderSetting.AICore -> string("aicore_stage", provider.releaseStage.name)
        is ProviderSetting.LiteRtLocal -> Unit
        is ProviderSetting.Codex -> Unit
    }
    return this
}

private fun CanonicalSha256.modelConfiguration(model: Model): CanonicalSha256 {
    string("model_uuid", model.id.toString())
    string("model_id", model.modelId)
    string("model_type", model.type.name)
    strings("model_input_modalities", model.inputModalities.map { it.name })
    strings("model_output_modalities", model.outputModalities.map { it.name })
    strings("model_abilities", model.abilities.map { it.name })
    strings("model_tools", model.tools.map { it.backgroundIdentityTag() }.sorted())
    nullableInt("model_catalog_context", model.contextLength)
    int("model_user_context", model.userContextWindowTokens)
    nullableInt("model_trusted_context", model.trustedContextWindowTokens)
    strings("model_supported_parameters", model.supportedParameters)
    int("model_custom_header_count", model.customHeaders.size)
    model.customHeaders.forEachIndexed { index, header ->
        string("model_header_${index}_name", header.name)
        string("model_header_${index}_value", header.value)
    }
    int("model_custom_body_count", model.customBodies.size)
    model.customBodies.forEachIndexed { index, body ->
        string("model_body_${index}_key", body.key)
        json("model_body_${index}_value", body.value)
    }
    bool("model_provider_overwrite", model.providerOverwrite != null)
    model.providerOverwrite?.let { overwrite ->
        string("model_overwrite_type", overwrite.typeTag())
        string("model_overwrite_id", overwrite.id.toString())
    }
    return this
}

private class CanonicalSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false

    fun string(label: String, value: String): CanonicalSha256 = apply {
        require(!finished)
        updateUtf8(label)
        updateInt(1)
        updateUtf8(value)
    }

    fun nullableString(label: String, value: String?): CanonicalSha256 = apply {
        if (value == null) {
            updateUtf8(label)
            updateInt(0)
        } else {
            string(label, value)
        }
    }

    fun bool(label: String, value: Boolean): CanonicalSha256 =
        string(label, if (value) "1" else "0")

    fun int(label: String, value: Int): CanonicalSha256 = string(label, value.toString())

    fun nullableInt(label: String, value: Int?): CanonicalSha256 =
        nullableString(label, value?.toString())

    fun nullableDouble(label: String, value: Double?): CanonicalSha256 =
        nullableString(label, value?.toBits()?.toString())

    fun strings(label: String, values: List<String>): CanonicalSha256 = apply {
        int("${label}_count", values.size)
        values.forEachIndexed { index, value -> string("${label}_$index", value) }
    }

    fun json(label: String, element: JsonElement): CanonicalSha256 = apply {
        string("${label}_kind", when (element) {
            JsonNull -> "null"
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> if (element.isString) "string" else "primitive"
        })
        when (element) {
            JsonNull -> Unit
            is JsonObject -> {
                val entries = element.entries.sortedBy { it.key }
                int("${label}_size", entries.size)
                entries.forEachIndexed { index, (key, value) ->
                    string("${label}_${index}_key", key)
                    json("${label}_${index}_value", value)
                }
            }
            is JsonArray -> {
                int("${label}_size", element.size)
                element.forEachIndexed { index, value -> json("${label}_$index", value) }
            }
            is JsonPrimitive -> string("${label}_content", element.content)
        }
    }

    fun finishHex(): String {
        require(!finished)
        finished = true
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun updateUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            updateInt(bytes.size)
            digest.update(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun updateInt(value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
}

private fun constantTimeDigestEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(Charsets.US_ASCII),
        right.toByteArray(Charsets.US_ASCII),
    )

private fun BuiltInTools.backgroundIdentityTag(): String = when (this) {
    BuiltInTools.Search -> "search"
    BuiltInTools.UrlContext -> "url_context"
    BuiltInTools.ImageGeneration -> "image_generation"
}
