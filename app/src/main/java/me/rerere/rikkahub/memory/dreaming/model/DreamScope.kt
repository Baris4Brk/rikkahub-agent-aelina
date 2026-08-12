package me.rerere.rikkahub.memory.dreaming.model

import kotlin.uuid.Uuid

/**
 * Canonical identifier for exactly one existing Memory authority scope.
 *
 * Private and global memory keep the product's current mutually-exclusive semantics. A private
 * scope is the assistant UUID itself (never `assistant:<uuid>`); the only non-UUID value is
 * [GLOBAL_VALUE]. Parsing is deliberately strict so a storage or IPC boundary cannot silently
 * normalize an ambiguous value into another scope.
 */
@JvmInline
value class DreamScopeId private constructor(val value: String) : Comparable<DreamScopeId> {
    val isGlobal: Boolean
        get() = value == GLOBAL_VALUE

    override fun compareTo(other: DreamScopeId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        const val GLOBAL_VALUE: String = "__global__"

        private val canonicalUuidPattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        val Global: DreamScopeId = DreamScopeId(GLOBAL_VALUE)

        /** Returns null for whitespace, aliases, upper-case UUIDs, and non-canonical UUID text. */
        fun parseOrNull(raw: String?): DreamScopeId? {
            if (raw == GLOBAL_VALUE) return Global
            if (raw == null || !canonicalUuidPattern.matches(raw)) return null
            val parsed = runCatching { Uuid.parse(raw) }.getOrNull() ?: return null
            return raw.takeIf { parsed.toString() == it }?.let(::DreamScopeId)
        }

        fun requireCanonical(raw: String): DreamScopeId =
            requireNotNull(parseOrNull(raw)) {
                "Dream scope must be a canonical lower-case UUID or $GLOBAL_VALUE"
            }

        fun privateScope(assistantId: Uuid): DreamScopeId = DreamScopeId(assistantId.toString())
    }
}
