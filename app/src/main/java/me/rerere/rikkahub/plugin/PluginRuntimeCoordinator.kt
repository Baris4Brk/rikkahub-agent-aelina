package me.rerere.rikkahub.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PluginInvocation(
    val pluginId: String,
    val handler: String,
    val kind: PluginInvocationKind,
    val inputJson: String,
    val assistantEnabledPluginIds: Set<String>,
    val stateProjection: String = "{}",
    val timeoutMs: Long = DEFAULT_PLUGIN_TIMEOUT_MS,
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    val origin: ToolCallOrigin,
    val isHeadless: Boolean = false,
    val isSubAgent: Boolean = false,
)

interface PluginRuntimeTransport {
    suspend fun invoke(request: PluginRuntimeRequest): PluginRuntimeResponse
    suspend fun cancel(invocationId: String)
}

fun interface PluginInvocationRunner {
    suspend fun invoke(invocation: PluginInvocation): PluginRuntimeResponse
}

class PluginRuntimeCoordinator(
    private val registry: PluginRegistryStore,
    private val transport: PluginRuntimeTransport,
    private val hostRpcGateway: PluginHostRpcGateway,
    private val isRuntimeEnabled: () -> Boolean,
    private val auditStore: PluginAuditStore? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PluginInvocationRunner {
    private val activeInvocations = ConcurrentHashMap.newKeySet<String>()

    override suspend fun invoke(invocation: PluginInvocation): PluginRuntimeResponse {
        if (!isRuntimeEnabled()) return rejected("plugin_runtime_disabled")
        if (invocation.origin != ToolCallOrigin.LocalChat || invocation.isHeadless ||
            invocation.isSubAgent || invocation.assistantId.isBlank() ||
            invocation.conversationId.isBlank() || invocation.runId.isBlank()
        ) {
            return rejected("plugin_invocation_surface_not_allowed")
        }
        val record = registry.get(invocation.pluginId)
            ?: return rejected("plugin_not_found")
        if (!record.enabled || record.reviewStatus != PluginReviewStatus.APPROVED) {
            return rejected("plugin_not_approved")
        }
        if (record.id !in invocation.assistantEnabledPluginIds) {
            return rejected("plugin_not_enabled_for_assistant")
        }
        if (!handlerDeclared(record.manifest, invocation.kind, invocation.handler)) {
            return rejected("plugin_handler_not_declared")
        }
        if (invocation.inputJson.length > PluginRuntimeRequestValidator.MAX_INPUT_CHARS ||
            runCatching { JSON.parseToJsonElement(invocation.inputJson) }.isFailure
        ) return rejected("plugin_input_invalid")

        val invocationId = "pl_${UUID.randomUUID().toString().replace("-", "")}" 
        val token = randomToken()
        val request = PluginRuntimeRequest(
            invocationId = invocationId,
            rpcToken = token,
            pluginId = record.id,
            pluginIdHash = PluginManifestValidator.pluginIdHash(record.id),
            entry = record.manifest.entry,
            handler = invocation.handler,
            inputJson = invocation.inputJson,
            kind = invocation.kind,
            timeoutMs = invocation.timeoutMs.coerceIn(
                PluginRuntimeRequestValidator.MIN_TIMEOUT_MS,
                PluginRuntimeRequestValidator.MAX_TIMEOUT_MS,
            ),
        )
        hostRpcGateway.register(
            PluginHostInvocationContext(
                invocationId = invocationId,
                rpcToken = token,
                pluginId = record.id,
                permissions = record.permissions,
                stateProjection = invocation.stateProjection.take(MAX_STATE_CHARS),
            )
        )
        activeInvocations += invocationId
        return try {
            val response = transport.invoke(request)
            auditStore?.record(invocation, response)
            recordOutcome(record.id, response.ok)
            response
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { transport.cancel(invocationId) }
            throw cancelled
        } catch (_: Throwable) {
            recordOutcome(record.id, success = false)
            PluginRuntimeResponse(
                ok = false,
                invocationId = invocationId,
                errorCode = "plugin_runtime_unavailable",
            ).also { response -> auditStore?.record(invocation, response) }
        } finally {
            activeInvocations -= invocationId
            hostRpcGateway.unregister(invocationId)
        }
    }

    suspend fun cancelAll(): Int {
        val ids = activeInvocations.toList()
        ids.forEach { invocationId -> runCatching { transport.cancel(invocationId) } }
        return ids.size
    }

    private fun recordOutcome(pluginId: String, success: Boolean) {
        val now = nowMs()
        runCatching {
            registry.update(pluginId) { current ->
                if (success) {
                    current.copy(failureTimestampsMs = emptyList())
                } else {
                    val failures = (current.failureTimestampsMs.filter {
                        now - it <= FAILURE_WINDOW_MS
                    } + now).takeLast(FAILURE_LIMIT)
                    if (failures.size >= FAILURE_LIMIT) {
                        current.copy(
                            enabled = false,
                            reviewStatus = PluginReviewStatus.QUARANTINED,
                            failureTimestampsMs = failures,
                        )
                    } else {
                        current.copy(failureTimestampsMs = failures)
                    }
                }
            }
        }
    }

    private fun handlerDeclared(
        manifest: PluginManifestV1,
        kind: PluginInvocationKind,
        handler: String,
    ): Boolean = when (kind) {
        PluginInvocationKind.TOOL -> manifest.tools.any { it.handler == handler }
        PluginInvocationKind.PROMPT_HOOK -> manifest.hooks.promptHandler == handler
        PluginInvocationKind.INTERCEPT_HOOK -> manifest.hooks.interceptHandler == handler
        PluginInvocationKind.OBSERVER_HOOK -> manifest.hooks.observerHandler == handler
    }

    private fun rejected(code: String) = PluginRuntimeResponse(
        ok = false,
        invocationId = "rejected",
        errorCode = code,
    )

    private fun randomToken(): String = ByteArray(32).also(SECURE_RANDOM::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val FAILURE_WINDOW_MS = 10 * 60_000L
        const val FAILURE_LIMIT = 3
        const val MAX_STATE_CHARS = 8 * 1024
        val SECURE_RANDOM = SecureRandom()
        val JSON = Json { ignoreUnknownKeys = false }
    }
}

class AndroidPluginRuntimeTransport(
    context: Context,
    private val hostRpcGateway: PluginHostRpcGateway,
) : PluginRuntimeTransport {
    private val appContext = context.applicationContext
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    @Volatile private var service: IPluginRuntimeService? = null
    @Volatile private var pendingConnection: CompletableDeferred<IPluginRuntimeService>? = null

    private val host = object : IPluginRuntimeHost.Stub() {
        override fun handleRpc(requestJson: String): String = hostRpcGateway.handleRpc(requestJson)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = IPluginRuntimeService.Stub.asInterface(binder)
            if (connected == null) {
                pendingConnection?.completeExceptionally(
                    IllegalStateException("plugin_runtime_bind_failed")
                )
            } else {
                service = connected
                pendingConnection?.complete(connected)
            }
            pendingConnection = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            pendingConnection?.completeExceptionally(
                IllegalStateException("plugin_runtime_binder_died")
            )
            pendingConnection = null
        }
    }

    override suspend fun invoke(request: PluginRuntimeRequest): PluginRuntimeResponse {
        PluginRuntimeRequestValidator.validate(request)
        val connected = connect().getOrThrow()
        val encoded = JSON.encodeToString(request)
        val raw = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                transportScope.launch {
                    runCatching { connected.cancel(request.invocationId) }
                }
            }
            transportScope.launch {
                runCatching { connected.invoke(encoded, host) }
                    .onSuccess { response ->
                        if (continuation.isActive) continuation.resume(response)
                    }
                    .onFailure { failure ->
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
            }
        }
        return runCatching { JSON.decodeFromString<PluginRuntimeResponse>(raw) }
            .getOrElse {
                PluginRuntimeResponse(
                    ok = false,
                    invocationId = request.invocationId,
                    errorCode = "plugin_runtime_response_invalid",
                )
            }
    }

    override suspend fun cancel(invocationId: String) {
        if (!invocationId.matches(Regex("[A-Za-z0-9_-]{8,96}"))) return
        val connected = service?.takeIf { it.asBinder().isBinderAlive } ?: return
        withContext(Dispatchers.IO) { runCatching { connected.cancel(invocationId) } }
    }

    private suspend fun connect(): Result<IPluginRuntimeService> = runCatching {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@runCatching it }
        connectionMutex.withLock {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            val deferred = CompletableDeferred<IPluginRuntimeService>()
            pendingConnection = deferred
            val bound = appContext.bindService(
                Intent(appContext, PluginRuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            check(bound) { "plugin_runtime_bind_failed" }
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
        val JSON = Json { ignoreUnknownKeys = false; explicitNulls = false }
    }
}

const val DEFAULT_PLUGIN_TIMEOUT_MS = 10_000L
