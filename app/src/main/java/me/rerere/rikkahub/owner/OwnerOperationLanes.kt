package me.rerere.rikkahub.owner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded lock stripes keep request idempotency and same-resource writes ordered without making a
 * slow Provider probe or service start block every unrelated Owner operation in the application.
 */
class OwnerOperationLanes(
    stripeCount: Int = DEFAULT_STRIPES,
) {
    private val requestStripes = List(stripeCount.requireValid()) { Mutex() }
    private val resourceStripes = List(stripeCount) { Mutex() }

    suspend fun <T> withOperation(request: OwnerOperationRequest, block: suspend () -> T): T =
        requestMutex(request.requestId).withLock {
            if (request.actions.all { it.risk == OwnerOperationRisk.READ_ONLY }) {
                block()
            } else {
                resourceMutex(resourceLaneKey(request)).withLock { block() }
            }
        }

    suspend fun <T> withRequestId(requestId: String, block: suspend () -> T): T =
        requestMutex(requestId).withLock { block() }

    internal fun resourceLaneKey(request: OwnerOperationRequest): String {
        val ids = request.actions.flatMap { action ->
            action.arguments.entries.mapNotNull { (key, value) ->
                key.takeIf { it.endsWith("_id") || it in RESOURCE_KEYS }
                    ?.let { "$key=${value.toString().take(160)}" }
            }
        }.distinct()
        val target = ids.singleOrNull() ?: "family"
        return "${request.authoritySubjectId}|${request.family.name}|$target"
    }

    private fun requestMutex(key: String): Mutex = requestStripes[stripe(key, requestStripes.size)]
    private fun resourceMutex(key: String): Mutex = resourceStripes[stripe(key, resourceStripes.size)]

    private fun stripe(key: String, size: Int): Int = (key.hashCode() and Int.MAX_VALUE) % size

    private fun Int.requireValid(): Int = apply { require(this >= 8) { "Owner lanes require at least 8 stripes" } }

    private companion object {
        const val DEFAULT_STRIPES = 64
        val RESOURCE_KEYS = setOf("skill_name", "screen", "repair", "runtime")
    }
}
