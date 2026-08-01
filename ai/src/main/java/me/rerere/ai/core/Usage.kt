package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // Provider-reported generation cost in USD (OpenRouter `usage.cost`). Null when the
    // provider doesn't report it. Nullable + defaulted so older persisted messages decode fine.
    val cost: Double? = null,
    // The legacy counters above are cumulative for one assistant turn. A tool-heavy turn can
    // contain many provider calls, so also retain the latest provider snapshot. Defaults keep
    // persisted pre-upgrade JSON compatible without a Room migration.
    val latestPromptTokens: Int = 0,
    val latestCompletionTokens: Int = 0,
    val latestCachedTokens: Int = 0,
    val providerCallCount: Int = 0,
)

/** Most recent provider request, with a legacy fallback for old persisted messages. */
val TokenUsage.currentPromptTokens: Int
    get() = if (providerCallCount > 0) latestPromptTokens else promptTokens

val TokenUsage.currentCompletionTokens: Int
    get() = if (providerCallCount > 0) latestCompletionTokens else completionTokens

val TokenUsage.currentCachedTokens: Int
    get() = if (providerCallCount > 0) {
        latestCachedTokens.coerceAtMost(currentPromptTokens)
    } else {
        cachedTokens.coerceAtMost(currentPromptTokens)
    }

val TokenUsage.currentFreshPromptTokens: Int
    get() = (currentPromptTokens - currentCachedTokens).coerceAtLeast(0)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = tokenSum(promptTokens, completionTokens)
    // A positive prompt count starts a new provider usage snapshot. cachedTokens=0 is a valid
    // value for that snapshot, not "field missing": retaining an older cache count here can
    // produce impossible rows such as prompt=25k, cached=336k after a tool-loop continuation.
    val cachedTokens = when {
        other.cachedTokens > 0 -> other.cachedTokens
        other.promptTokens > 0 -> 0
        else -> this?.cachedTokens ?: 0
    }.coerceIn(0, promptTokens)
    val cost = other.cost ?: this?.cost
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cost = cost,
        latestPromptTokens = when {
            other.latestPromptTokens > 0 -> other.latestPromptTokens
            other.promptTokens > 0 -> other.promptTokens
            else -> this?.latestPromptTokens ?: 0
        },
        latestCompletionTokens = when {
            other.latestCompletionTokens > 0 -> other.latestCompletionTokens
            other.completionTokens > 0 -> other.completionTokens
            else -> this?.latestCompletionTokens ?: 0
        },
        latestCachedTokens = when {
            other.latestPromptTokens > 0 || other.latestCachedTokens > 0 ->
                other.latestCachedTokens.coerceAtMost(other.latestPromptTokens)
            other.promptTokens > 0 -> other.cachedTokens.coerceAtMost(other.promptTokens)
            else -> this?.latestCachedTokens ?: 0
        },
        providerCallCount = maxOf(this?.providerCallCount ?: 0, other.providerCallCount),
    )
}

/** Add one complete provider-call usage snapshot to the running assistant-turn total. */
fun TokenUsage?.accumulate(other: TokenUsage): TokenUsage {
    val right = other.normalized().asLatestProviderCall()
    val left = this?.normalized() ?: return right
    val promptTokens = tokenSum(left.promptTokens, right.promptTokens)
    val completionTokens = tokenSum(left.completionTokens, right.completionTokens)
    val cachedTokens = tokenSum(left.cachedTokens, right.cachedTokens)
        .coerceAtMost(promptTokens)
    val cost = when {
        left.cost == null -> right.cost
        right.cost == null -> left.cost
        else -> left.cost + right.cost
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        totalTokens = tokenSum(promptTokens, completionTokens),
        cost = cost,
        latestPromptTokens = right.promptTokens,
        latestCompletionTokens = right.completionTokens,
        latestCachedTokens = right.cachedTokens,
        providerCallCount = tokenSum(
            left.providerCallCount.takeIf { it > 0 } ?: 1,
            right.providerCallCount.takeIf { it > 0 } ?: 1,
        ),
    )
}

/** Enforce the invariants expected by the UI and persistence layer for one usage snapshot. */
fun TokenUsage.normalized(): TokenUsage {
    val prompt = promptTokens.coerceAtLeast(0)
    val completion = completionTokens.coerceAtLeast(0)
    return copy(
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cachedTokens.coerceIn(0, prompt),
        totalTokens = tokenSum(prompt, completion),
        latestPromptTokens = latestPromptTokens.coerceIn(0, prompt),
        latestCompletionTokens = latestCompletionTokens.coerceIn(0, completion),
        latestCachedTokens = latestCachedTokens.coerceIn(
            0,
            latestPromptTokens.takeIf { it > 0 } ?: prompt,
        ),
        providerCallCount = providerCallCount.coerceAtLeast(0),
    )
}

private fun TokenUsage.asLatestProviderCall(): TokenUsage = copy(
    latestPromptTokens = promptTokens,
    latestCompletionTokens = completionTokens,
    latestCachedTokens = cachedTokens,
    providerCallCount = providerCallCount.takeIf { it > 0 } ?: 1,
)

private fun tokenSum(left: Int, right: Int): Int =
    (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
