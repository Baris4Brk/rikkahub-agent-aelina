package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.display.DisplayProvisionerLifecycleListener
import me.rerere.rikkahub.display.PrivilegedDisplayBridgePort
import me.rerere.rikkahub.display.PrivilegedDisplayInfo

/** Narrow connector exposed by [ShizukuBridgeManager] for the dedicated display Binder only. */
interface ManagedDisplayBridgeConnector {
    suspend fun createManagedDisplayResponse(): Result<String>
    suspend fun closeManagedDisplayResponse(displayId: Int): Result<String>
    fun addDisplayBridgeDeathListener(listener: () -> Unit): AutoCloseable
}

/**
 * App-process adapter for the typed UserService display Binder.
 *
 * A malformed response, an unavailable Binder, or a primary-display identity is a hard failure.
 * The caller never receives a substitute display id and therefore cannot accidentally fall back
 * onto the user's physical screen.
 */
class ShizukuDisplayBridgePort(
    private val connector: ManagedDisplayBridgeConnector,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PrivilegedDisplayBridgePort {
    @Volatile
    private var lifecycleListener: DisplayProvisionerLifecycleListener? = null

    @Suppress("unused")
    private val deathSubscription = connector.addDisplayBridgeDeathListener {
        lifecycleListener?.onProvisionerDied(null)
    }

    override suspend fun createManagedDisplay(): Result<PrivilegedDisplayInfo> =
        call(connector::createManagedDisplayResponse).mapCatching { response ->
            require(response.ok) { ManagedDisplayBridgeWire.failureCode(response) }
            val displayId = requireNotNull(response.displayId) {
                "display_capability_unavailable"
            }
            require(me.rerere.rikkahub.display.DisplayCapability.CREATE in response.capabilities) {
                "display_capability_unavailable"
            }
            PrivilegedDisplayInfo(displayId, response.capabilities)
        }.mapFailureToDisplayCode()

    override suspend fun closeManagedDisplay(displayId: Int): Result<Unit> {
        if (displayId <= 0) {
            return Result.failure(IllegalArgumentException("display_primary_forbidden"))
        }
        return call { connector.closeManagedDisplayResponse(displayId) }.mapCatching { response ->
            require(response.ok) { ManagedDisplayBridgeWire.failureCode(response) }
            require(response.displayId == displayId) { "display_capability_unavailable" }
            Unit
        }.mapFailureToDisplayCode()
    }

    override fun setDisplayLifecycleListener(listener: DisplayProvisionerLifecycleListener?) {
        lifecycleListener = listener
    }

    private suspend fun call(
        request: suspend () -> Result<String>,
    ): Result<ManagedDisplayBridgeResponse> = withContext(dispatcher) {
        request().fold(
            onSuccess = { response ->
                runCatching { ManagedDisplayBridgeWire.decode(response) }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private fun <T> Result<T>.mapFailureToDisplayCode(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = {
            val code = it.message?.takeIf { message ->
                message.matches(Regex("[a-z0-9_]{3,80}"))
            } ?: "display_capability_unavailable"
            Result.failure(IllegalStateException(code))
        },
    )
}
