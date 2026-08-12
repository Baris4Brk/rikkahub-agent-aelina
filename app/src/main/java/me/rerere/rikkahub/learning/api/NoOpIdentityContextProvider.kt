package me.rerere.rikkahub.learning.api

/** Default P0 provider. Absence is explicit and is never misreported as an authoritative empty set. */
object NoOpIdentityContextProvider : IdentityContextProvider {
    override suspend fun queryRelevantIdentity(
        request: IdentityContextRequest,
    ): IdentityContextResult = IdentityContextResult.Unavailable(
        IdentityContextUnavailableReason.DISABLED,
    )

    override fun toString(): String = "NoOpIdentityContextProvider"
}
