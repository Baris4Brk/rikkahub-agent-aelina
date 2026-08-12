package me.rerere.rikkahub.learning.adapters

import me.rerere.rikkahub.learning.api.IdentityContextProvider
import me.rerere.rikkahub.learning.api.IdentityContextRequest
import me.rerere.rikkahub.learning.api.IdentityContextResult
import me.rerere.rikkahub.learning.api.IdentityContextUnavailableReason

/**
 * Fail-closed Dreaming boundary for P0.
 *
 * The current Dreaming implementation exposes review-oriented reads and runtime projection
 * models, but no frozen public read API that accepts a Learning scope and returns a bounded,
 * relevant identity projection. This adapter therefore does not query Dream DAOs or reinterpret
 * internal snapshot tables. Once Dreaming owns such an API, that authority-owned port can replace
 * this implementation without changing Learning consumers.
 */
object DreamingIdentityAdapter : IdentityContextProvider {
    override suspend fun queryRelevantIdentity(
        request: IdentityContextRequest,
    ): IdentityContextResult = IdentityContextResult.Unavailable(
        IdentityContextUnavailableReason.PUBLIC_READ_API_UNAVAILABLE,
    )

    override fun toString(): String = "DreamingIdentityAdapter(publicReadApi=unavailable)"
}
