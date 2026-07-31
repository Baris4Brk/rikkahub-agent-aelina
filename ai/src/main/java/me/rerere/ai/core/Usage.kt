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
)

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
    )
}

/** Add one complete provider-call usage snapshot to the running assistant-turn total. */
fun TokenUsage?.accumulate(other: TokenUsage): TokenUsage {
    val right = other.normalized()
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
    )
}

private fun tokenSum(left: Int, right: Int): Int =
    (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
