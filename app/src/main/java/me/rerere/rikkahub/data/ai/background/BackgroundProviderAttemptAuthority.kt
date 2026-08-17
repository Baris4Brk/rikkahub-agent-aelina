package me.rerere.rikkahub.data.ai.background

import me.rerere.ai.core.TokenUsage

/**
 * Opaque durable authority for one already-reserved provider attempt.
 *
 * Implementations are issued only by the Learning job store after the job lease and budget
 * reservation commit together. No provider byte may be sent until [markDispatchStarted] commits.
 * The interface intentionally exposes neither Room nor a mutable row.
 */
interface BackgroundProviderAttemptAuthority {
    val stableProviderIdempotencyKey: String

    /**
     * Legacy source-compatible name for the exact dispatch attestation. For LOCAL this is the
     * frozen runtime/artifact digest; for REMOTE it is the frozen official transport/request
     * contract digest. New dispatch code must use [expectedDispatchAttestationSha256].
     */
    val expectedRuntimeAttestationSha256: String

    val expectedDispatchAttestationSha256: String
        get() = expectedRuntimeAttestationSha256

    /** Throws or returns false without dispatch authority; the provider must then send zero bytes. */
    suspend fun markDispatchStarted(observedDispatchAttestationSha256: String): Boolean

    /** Releases only a reservation that is still proven NOT_DISPATCHED. */
    suspend fun releaseUndispatched(): Boolean

    /** Freezes the observed terminal/usage fact. UNKNOWN usage remains null, never zero. */
    suspend fun markTerminal(
        outcome: BackgroundProviderTerminalOutcome,
        usage: BackgroundProviderUsage,
    ): Boolean
}

enum class BackgroundProviderTerminalOutcome {
    SUCCESS,
    DEFERRED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}

data class BackgroundProviderUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val costMicros: Long?,
) {
    init {
        listOfNotNull(inputTokens, outputTokens, costMicros).forEach { value ->
            require(value >= 0L) { "Negative provider usage" }
        }
    }

    override fun toString(): String = "BackgroundProviderUsage(<redacted>)"

    companion object {
        val UNKNOWN = BackgroundProviderUsage(null, null, null)

        fun from(usage: TokenUsage?): BackgroundProviderUsage = BackgroundProviderUsage(
            inputTokens = usage?.promptTokens?.toLong()?.takeIf { it >= 0L },
            outputTokens = usage?.completionTokens?.toLong()?.takeIf { it >= 0L },
            costMicros = usage?.cost?.toProviderMicrosOrNull(),
        )
    }
}

private fun Double.toProviderMicrosOrNull(): Long? = runCatching {
    require(isFinite() && this >= 0.0)
    java.math.BigDecimal.valueOf(this)
        .multiply(java.math.BigDecimal.valueOf(1_000_000L))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
