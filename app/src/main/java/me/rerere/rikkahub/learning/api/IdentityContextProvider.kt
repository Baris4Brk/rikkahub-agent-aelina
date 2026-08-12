package me.rerere.rikkahub.learning.api

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.TaskSignatureV1

private const val MAX_IDENTITY_CONTEXT_ITEMS = 32
private const val MAX_IDENTITY_CONTEXT_CHARS = 32_768
private const val MAX_IDENTITY_ITEM_CHARS = 8_192

/**
 * Hard request bounds for a small, read-only identity projection.
 *
 * The budget is not permission to read another scope and it is not added on top of the host's
 * context-window budget. A caller must account the returned block inside its existing budget.
 */
data class IdentityContextBudget(
    val maxItems: Int,
    val maxChars: Int,
) {
    init {
        require(maxItems in 1..MAX_IDENTITY_CONTEXT_ITEMS) { "Unsafe identity item budget" }
        require(maxChars in 1..MAX_IDENTITY_CONTEXT_CHARS) { "Unsafe identity character budget" }
    }
}

/**
 * A scope-bound query. [taskSignature] is a weak, allowlisted retrieval hint only; it is never an
 * authority, scope, permission, or stable user identity.
 */
class IdentityContextRequest(
    val expectedScope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val budget: IdentityContextBudget,
) {
    override fun toString(): String =
        "IdentityContextRequest(scope=${expectedScope.kind}, task=<opaque>, budget=$budget)"
}

enum class IdentityContextKind {
    CURRENT_PROJECT,
    ACTIVE_PLAN,
    ACTIVE_CONSTRAINT,
}

/**
 * One ephemeral, untrusted identity summary. It must never be interpreted as an instruction or a
 * tool grant. The text is deliberately redacted from [toString].
 */
class IdentityContextItem internal constructor(
    val kind: IdentityContextKind,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Identity context item is blank" }
        require(text.length <= MAX_IDENTITY_ITEM_CHARS) { "Identity context item is too large" }
        require(text.none(Char::isISOControl)) { "Identity context item contains control text" }
    }

    override fun toString(): String = "IdentityContextItem(kind=$kind, text=<redacted>)"
}

/**
 * A bounded, scope-checked projection produced by an authority-owned public read API.
 *
 * It intentionally carries no Dreaming row IDs. Consumers may use it as untrusted context during
 * the current operation, but must not persist it as a second persona/profile authority.
 */
class ActiveIdentityBlock internal constructor(
    val items: List<IdentityContextItem>,
) {
    init {
        require(items.isNotEmpty()) { "An available identity block must not be empty" }
        require(items.size <= MAX_IDENTITY_CONTEXT_ITEMS) { "Identity block has too many items" }
        require(items.sumOf { it.text.length } <= MAX_IDENTITY_CONTEXT_CHARS) {
            "Identity block is too large"
        }
    }

    override fun toString(): String =
        "ActiveIdentityBlock(items=${items.size}, text=<redacted>)"
}

enum class IdentityContextUnavailableReason {
    DISABLED,
    PUBLIC_READ_API_UNAVAILABLE,
    TIMEOUT,
    SOURCE_FAILURE,
    INVALID_PROJECTION,
}

sealed interface IdentityContextResult {
    data class Available(val block: ActiveIdentityBlock) : IdentityContextResult

    data class Unavailable(
        val reason: IdentityContextUnavailableReason,
    ) : IdentityContextResult
}

/** Dreaming remains the sole authority; Learning receives only this bounded read projection. */
fun interface IdentityContextProvider {
    suspend fun queryRelevantIdentity(request: IdentityContextRequest): IdentityContextResult
}
