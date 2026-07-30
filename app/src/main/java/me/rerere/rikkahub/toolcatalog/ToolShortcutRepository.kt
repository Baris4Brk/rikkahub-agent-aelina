package me.rerere.rikkahub.toolcatalog

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorSource
import me.rerere.rikkahub.data.db.AppDatabase
import kotlin.uuid.Uuid

data class ToolShortcutSummary(
    val id: String,
    val toolName: String,
    val source: String,
    val categoryPath: String,
    val risk: String,
    val schemaFingerprint: String,
    val state: String,
    val stateVersion: Long,
    val lastUsedAtMs: Long?,
    val useCount: Long,
    val modelConfirmedAtMs: Long,
)

data class ToolShortcutLibraryDiagnostics(
    val authorityActive: Boolean,
    val totalCount: Int,
    val activeCount: Int,
    val staleCount: Int,
)

sealed interface ToolShortcutMutationResult {
    data class Pinned(val shortcut: ToolShortcutSummary) : ToolShortcutMutationResult
    data class Updated(val stateVersion: Long) : ToolShortcutMutationResult
    data object Missing : ToolShortcutMutationResult
    data object Conflict : ToolShortcutMutationResult
    data object Denied : ToolShortcutMutationResult
    data object Invalid : ToolShortcutMutationResult
}

/** Narrow model-facing contract; the host owns authority and schema verification. */
interface ToolShortcutEditor {
    suspend fun pin(entry: ToolCatalogEntry): ToolShortcutMutationResult
    suspend fun unpin(id: String, expectedVersion: Long): ToolShortcutMutationResult
    suspend fun list(): List<ToolShortcutSummary>
}

/**
 * Infinite, authority-scoped fast-lane library. "Infinite" describes retention: there is no
 * automatic capacity eviction. Provider injection remains deliberately bounded by
 * [ToolDiscoverySession] so a large library can never rebuild the old huge prompt surface.
 */
class ToolShortcutRepository(
    private val database: AppDatabase,
    private val dao: ToolShortcutDao = database.toolShortcutDao(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { Uuid.random().toString() },
) : ToolShortcutEditor {
    override suspend fun pin(entry: ToolCatalogEntry): ToolShortcutMutationResult {
        if (!ToolFastLanePolicy.isPinnable(entry)) return ToolShortcutMutationResult.Invalid
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return ToolShortcutMutationResult.Denied
        val now = nowMs()
        return database.withTransaction {
            val existing = dao.getBySignature(subject, entry.toolName, entry.schemaFingerprint)
            if (existing != null) {
                if (existing.state == ToolShortcutState.ACTIVE.name) {
                    return@withTransaction ToolShortcutMutationResult.Pinned(existing.toSummary())
                }
                val updated = dao.setState(
                    id = existing.shortcutId,
                    subjectId = subject,
                    expectedVersion = existing.stateVersion,
                    state = ToolShortcutState.ACTIVE.name,
                    nowMs = now,
                )
                return@withTransaction if (updated == 1) {
                    ToolShortcutMutationResult.Pinned(checkNotNull(dao.get(existing.shortcutId)).toSummary())
                } else {
                    ToolShortcutMutationResult.Conflict
                }
            }
            val created = ToolShortcutEntity(
                shortcutId = idGenerator(),
                authoritySubjectId = subject,
                toolName = entry.toolName,
                source = entry.source.name,
                categoryPath = entry.categoryPath.take(MAX_CATEGORY_CHARS),
                risk = entry.risk?.name ?: "UNKNOWN",
                schemaFingerprint = entry.schemaFingerprint.take(MAX_FINGERPRINT_CHARS),
                state = ToolShortcutState.ACTIVE.name,
                stateVersion = 0,
                createdAtMs = now,
                updatedAtMs = now,
                lastUsedAtMs = null,
                useCount = 0,
                modelConfirmedAtMs = now,
            )
            dao.insert(created)
            dao.getBySignature(subject, entry.toolName, entry.schemaFingerprint)
                ?.let { ToolShortcutMutationResult.Pinned(it.toSummary()) }
                ?: ToolShortcutMutationResult.Conflict
        }
    }

    override suspend fun unpin(id: String, expectedVersion: Long): ToolShortcutMutationResult {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return ToolShortcutMutationResult.Denied
        val now = nowMs()
        return database.withTransaction {
            val existing = dao.get(id) ?: return@withTransaction ToolShortcutMutationResult.Missing
            if (existing.authoritySubjectId != subject) return@withTransaction ToolShortcutMutationResult.Denied
            val updated = dao.setState(
                id = id,
                subjectId = subject,
                expectedVersion = expectedVersion,
                state = ToolShortcutState.DISABLED.name,
                nowMs = now,
            )
            when {
                updated == 1 -> ToolShortcutMutationResult.Updated(checkNotNull(dao.get(id)).stateVersion)
                dao.get(id) == null -> ToolShortcutMutationResult.Missing
                else -> ToolShortcutMutationResult.Conflict
            }
        }
    }

    override suspend fun list(): List<ToolShortcutSummary> {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId ?: return emptyList()
        return dao.listActive(subject, MAX_MODEL_LIST).map { it.toSummary() }
    }

    fun observeLibrary(subjectId: String, limit: Int = UI_LIBRARY_LIMIT): Flow<List<ToolShortcutEntity>> =
        dao.observeLibrary(subjectId, limit.coerceIn(1, UI_LIBRARY_LIMIT))

    suspend fun diagnostics(): ToolShortcutLibraryDiagnostics {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return ToolShortcutLibraryDiagnostics(false, 0, 0, 0)
        val rows = dao.listForDiagnostics(subject, DIAGNOSTIC_LIMIT)
        return ToolShortcutLibraryDiagnostics(
            authorityActive = true,
            totalCount = rows.size,
            activeCount = rows.count { it.state == ToolShortcutState.ACTIVE.name },
            staleCount = rows.count {
                it.state == ToolShortcutState.STALE_SCHEMA.name ||
                    it.state == ToolShortcutState.STALE_AUTHORITY.name
            },
        )
    }

    /** Returns only active metadata; the caller still verifies it against this run's snapshot. */
    suspend fun selectForPrompt(userText: String, limit: Int = MAX_PROMPT_SHORTCUTS): List<ToolShortcutSummary> {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId ?: return emptyList()
        return ToolShortcutRelevance.select(
            query = userText,
            shortcuts = dao.listActive(subject, MAX_PROMPT_SHORTCUT_SCAN).map { it.toSummary() },
            max = limit,
        )
    }

    /** A successful tracked call refreshes an existing entry; it never auto-pins a tool. */
    suspend fun recordSuccessfulUse(entry: ToolCatalogEntry, authoritySubjectId: String) {
        if (!ToolFastLanePolicy.isPinnable(entry)) return
        val current = SecondUserAuthorityRegistry.current()?.subjectId ?: return
        if (current != authoritySubjectId) return
        repeat(MAX_CAS_ATTEMPTS) {
            val shortcut = dao.getBySignature(current, entry.toolName, entry.schemaFingerprint) ?: return
            if (shortcut.state != ToolShortcutState.ACTIVE.name) return
            if (dao.markUsed(shortcut.shortcutId, current, shortcut.stateVersion, nowMs()) == 1) return
        }
    }

    /** Schema changes never inherit a fast-lane lease. It remains visible as stale history. */
    suspend fun reconcileSnapshot(snapshot: ToolCatalogSnapshot) {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId ?: return
        dao.listActive(subject, SCHEMA_RECONCILE_LIMIT).forEach { shortcut ->
            val entry = snapshot.entry(shortcut.toolName)
            if (entry == null || entry.schemaFingerprint != shortcut.schemaFingerprint) {
                repeat(MAX_CAS_ATTEMPTS) {
                    val current = dao.get(shortcut.shortcutId) ?: return@repeat
                    if (current.state != ToolShortcutState.ACTIVE.name) return@repeat
                    if (dao.markStaleSchema(current.shortcutId, current.stateVersion, nowMs()) == 1) {
                        return@forEach
                    }
                }
            }
        }
    }

    suspend fun setState(
        id: String,
        expectedVersion: Long,
        state: ToolShortcutState,
    ): ToolShortcutMutationResult {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return ToolShortcutMutationResult.Denied
        if (state !in setOf(ToolShortcutState.ACTIVE, ToolShortcutState.DISABLED)) {
            return ToolShortcutMutationResult.Denied
        }
        return database.withTransaction {
            val existing = dao.get(id) ?: return@withTransaction ToolShortcutMutationResult.Missing
            if (existing.authoritySubjectId != subject) return@withTransaction ToolShortcutMutationResult.Denied
            if (state == ToolShortcutState.ACTIVE && existing.state !in setOf(
                    ToolShortcutState.ACTIVE.name,
                    ToolShortcutState.DISABLED.name,
                )
            ) {
                return@withTransaction ToolShortcutMutationResult.Denied
            }
            if (dao.setState(id, subject, expectedVersion, state.name, nowMs()) == 1) {
                ToolShortcutMutationResult.Updated(checkNotNull(dao.get(id)).stateVersion)
            } else {
                ToolShortcutMutationResult.Conflict
            }
        }
    }

    suspend fun invalidateAuthoritySubjects(subjectIds: Set<String>): Int {
        if (subjectIds.isEmpty()) return 0
        return database.withTransaction {
            dao.staleAuthoritySubjects(subjectIds.toList(), nowMs())
        }
    }

    private fun ToolShortcutEntity.toSummary() = ToolShortcutSummary(
        id = shortcutId,
        toolName = toolName,
        source = source,
        categoryPath = categoryPath,
        risk = risk,
        schemaFingerprint = schemaFingerprint,
        state = state,
        stateVersion = stateVersion,
        lastUsedAtMs = lastUsedAtMs,
        useCount = useCount,
        modelConfirmedAtMs = modelConfirmedAtMs,
    )

    private companion object {
        const val MAX_CATEGORY_CHARS = 120
        const val MAX_FINGERPRINT_CHARS = 128
        const val MAX_PROMPT_SHORTCUTS = 6
        const val MAX_PROMPT_SHORTCUT_SCAN = 512
        const val MAX_MODEL_LIST = 64
        const val SCHEMA_RECONCILE_LIMIT = 10_000
        const val UI_LIBRARY_LIMIT = 1_000
        const val DIAGNOSTIC_LIMIT = 10_000
        const val MAX_CAS_ATTEMPTS = 3
    }
}

/** Pure policy shared by the repository and the model-facing fast-lane tool. */
object ToolFastLanePolicy {
    fun isPinnable(entry: ToolCatalogEntry): Boolean =
        !entry.externalUntrusted &&
            entry.source in setOf(ToolDescriptorSource.STATIC_CAPABILITY, ToolDescriptorSource.INTERNAL) &&
            entry.toolName !in setOf(
                ToolDiscoverySession.TOOL_CATALOG_SEARCH,
                ToolDiscoverySession.TOOL_CATALOG_LIST,
                ToolDiscoverySession.TOOL_CATALOG_OPEN,
                ToolDiscoverySession.TOOL_EXPERIENCE_UPDATE,
                ToolDiscoverySession.TOOL_FAST_LANE_MANAGE,
                "ask_user",
            )
}
