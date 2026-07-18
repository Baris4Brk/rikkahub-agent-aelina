package me.rerere.rikkahub.data.ai.transformers

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

const val WORKSPACE_RULE_FILE_MAX_CHARS = 16_384
const val WORKSPACE_RULE_TOTAL_MAX_CHARS = 32_768
private const val WORKSPACE_RULE_FILE_MAX_BYTES = WORKSPACE_RULE_FILE_MAX_CHARS * 4

data class WorkspaceRuleFileMetadata(
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

interface WorkspaceRulesFileSource {
    suspend fun stat(workspaceId: String, path: String): WorkspaceRuleFileMetadata?
    suspend fun read(workspaceId: String, path: String, maxBytes: Int): ByteArray?
}

data class WorkspaceRule(
    val source: String,
    val content: String,
    val truncated: Boolean,
)

data class WorkspaceRulesSnapshot(
    val rules: List<WorkspaceRule>,
) {
    val totalContentChars: Int = rules.sumOf { it.content.length }

    fun toPrompt(): String {
        if (rules.isEmpty()) return ""
        return buildString {
            appendLine("<workspace_rules>")
            rules.forEach { rule ->
                append("  <rule source=\"")
                append(escapeXmlAttribute(rule.source))
                append('"')
                if (rule.truncated) append(" truncated=\"true\"")
                appendLine(">")
                appendLine(escapeXmlText(rule.content).prependIndent("    "))
                appendLine("  </rule>")
            }
            append("</workspace_rules>")
        }
    }
}

/**
 * Resolves bounded project instructions independently of PRoot/shell readiness.
 *
 * Candidate discovery, strict decoding, cache invalidation and priority budgeting are hidden
 * behind [resolve]. Higher-priority rules consume the shared budget first; the returned snapshot
 * remains ordered from low to high priority so later rules can override earlier guidance.
 */
class WorkspaceRulesResolver(
    private val source: WorkspaceRulesFileSource,
) {
    private data class CacheKey(
        val workspaceId: String,
        val path: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
    )

    private sealed interface CachedRule {
        data class Valid(val content: String, val truncated: Boolean) : CachedRule
        data object Invalid : CachedRule
    }

    private val cache = ConcurrentHashMap<CacheKey, CachedRule>()

    suspend fun resolve(
        workspaceId: String,
        cwd: String?,
    ): WorkspaceRulesSnapshot {
        val loaded = candidatePaths(cwd).mapNotNull { path ->
            load(workspaceId, path)?.let { cached ->
                WorkspaceRule(path, cached.content, cached.truncated)
            }
        }
        var remaining = WORKSPACE_RULE_TOTAL_MAX_CHARS
        val selectedHighToLow = arrayListOf<WorkspaceRule>()
        loaded.asReversed().forEach { rule ->
            if (remaining <= 0) return@forEach
            val kept = rule.content.take(remaining)
            if (kept.isNotEmpty()) {
                selectedHighToLow += rule.copy(
                    content = kept,
                    truncated = rule.truncated || kept.length < rule.content.length,
                )
                remaining -= kept.length
            }
        }
        return WorkspaceRulesSnapshot(selectedHighToLow.asReversed())
    }

    private suspend fun load(
        workspaceId: String,
        path: String,
    ): CachedRule.Valid? {
        val metadata = runCatching { source.stat(workspaceId, path) }.getOrNull()
            ?: return null
        if (metadata.sizeBytes <= 0L) return null
        val key = CacheKey(workspaceId, path, metadata.sizeBytes, metadata.modifiedAtMs)
        when (val cached = cache[key]) {
            is CachedRule.Valid -> return cached
            CachedRule.Invalid -> return null
            null -> Unit
        }

        cache.keys
            .filter { old -> old.workspaceId == workspaceId && old.path == path && old != key }
            .forEach(cache::remove)
        val readLimit = min(metadata.sizeBytes, WORKSPACE_RULE_FILE_MAX_BYTES.toLong()).toInt()
        val bytes = runCatching { source.read(workspaceId, path, readLimit) }.getOrNull()
        if (bytes == null) {
            cache[key] = CachedRule.Invalid
            return null
        }
        val byteTruncated = metadata.sizeBytes > bytes.size
        val decoded = decodeUtf8Strict(bytes, allowTrailingPartial = byteTruncated)
        if (decoded == null) {
            cache[key] = CachedRule.Invalid
            return null
        }
        val content = decoded.take(WORKSPACE_RULE_FILE_MAX_CHARS)
        if (content.isBlank()) {
            cache[key] = CachedRule.Invalid
            return null
        }
        return CachedRule.Valid(
            content = content,
            truncated = byteTruncated || content.length < decoded.length,
        ).also { cache[key] = it }
    }
}

internal fun workspaceRuleCandidatePaths(cwd: String?): List<String> = buildList {
    add("/workspace/RULES.md")
    add("/workspace/AGENTS.md")
    var current = "/workspace"
    normalizedWorkspaceCwdSegments(cwd).forEach { segment ->
        current += "/$segment"
        add("$current/AGENTS.md")
    }
    add("/workspace/.rikkahub/AGENTS.md")
}.distinct()

private fun candidatePaths(cwd: String?): List<String> = workspaceRuleCandidatePaths(cwd)

private fun normalizedWorkspaceCwdSegments(cwd: String?): List<String> {
    val normalized = cwd.orEmpty().trim().replace('\\', '/')
    if (normalized.contains('\u0000')) return emptyList()
    val relative = when {
        normalized.isBlank() || normalized == "/workspace" -> ""
        normalized.startsWith("/workspace/") -> normalized.removePrefix("/workspace/")
        normalized.startsWith('/') -> return emptyList()
        else -> normalized.trimStart('/')
    }
    val segments = relative.split('/').filter { it.isNotBlank() && it != "." }
    if (segments.any { it == ".." }) return emptyList()
    return segments
}

private fun decodeUtf8Strict(bytes: ByteArray, allowTrailingPartial: Boolean): String? {
    val drops = if (allowTrailingPartial) 0..min(3, bytes.size) else 0..0
    for (drop in drops) {
        val length = bytes.size - drop
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString()
        }.getOrNull()
        if (decoded != null) return decoded
    }
    return null
}

internal fun escapeXmlText(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun escapeXmlAttribute(value: String): String = escapeXmlText(value)
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
