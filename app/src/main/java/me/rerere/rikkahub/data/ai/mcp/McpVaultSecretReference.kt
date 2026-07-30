package me.rerere.rikkahub.data.ai.mcp

import kotlin.uuid.Uuid

/**
 * A non-secret Settings value that points at a Vault slot. It is valid only as the entire value
 * of a sensitive MCP request header; the transport resolves it locally immediately before it
 * connects. The reference contains no credential bytes and is safe to persist/redact/display.
 */
object McpVaultSecretReference {
    private const val PREFIX = "rikkahub-vault-slot:"
    private val SLOT_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")

    fun encode(slotId: String): String = "$PREFIX$slotId"

    fun slotIdOrNull(value: String): String? = value
        .trim()
        .removePrefix(PREFIX)
        .takeIf { value.trim().startsWith(PREFIX) && SLOT_ID.matches(it) }

    fun isReference(value: String): Boolean = slotIdOrNull(value) != null

    /** Stable, non-secret binding identity. Header index keeps duplicate HTTP headers distinct. */
    fun bindingTarget(serverId: Uuid, headerName: String, headerIndex: Int): String =
        "${serverId}:${headerIndex}:${headerName.trim().lowercase()}".take(160)
}
