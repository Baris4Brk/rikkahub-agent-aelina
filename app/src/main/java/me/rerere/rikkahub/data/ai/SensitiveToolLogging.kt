package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES
import me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
import java.security.MessageDigest

/**
 * Returns whether a tool's arguments must be treated as wholly sensitive.
 *
 * These tools commonly carry commands, typed text, package state, settings values, or
 * accessibility selectors. Field-name redaction is insufficient because their sensitive
 * values often use ordinary keys such as `text`, `command`, or `value`.
 */
internal fun isSensitivePrivilegedTool(toolName: String): Boolean =
    toolName == PRIVILEGED_SHELL_TOOL_NAME ||
        toolName in STRUCTURED_PRIVILEGED_TOOL_NAMES ||
        toolName in STRUCTURED_PRIVILEGED_V2_TOOL_NAMES ||
        toolName in VERIFIED_ACCESSIBILITY_TOOL_NAMES

/** Builds a diagnostic log line without ever including tool arguments. */
internal fun toolExecutionLogSummary(toolName: String, arguments: JsonElement): String {
    val kind = if (isSensitivePrivilegedTool(toolName)) {
        "sensitive privileged tool"
    } else {
        "tool"
    }
    return "generateText: executing $kind $toolName, payloadRedacted=true, " +
        "payloadSha256=${sha256(arguments.toString())}"
}

/** Stable loop-guard signature that never retains or exposes the raw JSON arguments. */
internal fun toolLoopSignature(toolName: String, rawArguments: String): String =
    "$toolName::sha256:${sha256(rawArguments)}"

private fun sha256(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
