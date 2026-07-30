package me.rerere.rikkahub.toolcatalog

import androidx.room.withTransaction
import java.security.MessageDigest
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorSource
import me.rerere.rikkahub.data.db.AppDatabase

enum class ToolExperienceOutcomeKind {
    HOST_COMPLETED,
    STANDARD_SUCCESS,
    RUNTIME_CONFIRMED;

    val confidence: ToolExperienceConfidence
        get() = if (this == HOST_COMPLETED) ToolExperienceConfidence.OBSERVED else ToolExperienceConfidence.VERIFIED
}

data class ToolExperienceSignal(
    val authoritySubjectId: String,
    val origin: ToolCallOrigin,
    val executionId: String,
    val toolName: String,
    val toolNames: List<String>,
    val categoryPath: String,
    val schemaFingerprint: String,
    val source: ToolDescriptorSource,
    val outcome: ToolExperienceOutcomeKind,
)

data class ToolExperienceLibraryDiagnostics(
    val authorityActive: Boolean,
    val totalCount: Int,
    val activeCount: Int,
    val staleCount: Int,
    val redactionViolationCount: Int,
)

sealed interface ToolExperienceMutationResult {
    data class Updated(val stateVersion: Long) : ToolExperienceMutationResult
    data object Missing : ToolExperienceMutationResult
    data object Conflict : ToolExperienceMutationResult
    data object Invalid : ToolExperienceMutationResult
    data object Denied : ToolExperienceMutationResult
}

/**
 * Durable, authority-scoped tool procedure library. All public writes are versioned and every
 * execution-derived write is reduced to stable ids before it reaches Room.
 */
class ToolExperienceRepository(
    private val database: AppDatabase,
    private val dao: ToolExperienceDao = database.toolExperienceDao(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { Uuid.random().toString() },
) : ToolExperienceLookup, ToolExperienceEditor {
    override suspend fun find(entries: List<ToolCatalogEntry>): List<ToolExperienceSummary> {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId ?: return emptyList()
        val currentEntries = entries.distinctBy(ToolCatalogEntry::toolName).take(MAX_LOOKUP_TOOL_NAMES)
        if (currentEntries.isEmpty()) return emptyList()
        return database.withTransaction {
            val now = nowMs()
            currentEntries.forEach { entry ->
                dao.staleOtherFingerprints(subject, entry.toolName, entry.schemaFingerprint, now)
            }
            dao.findActiveForTools(
                subject,
                currentEntries.map(ToolCatalogEntry::toolName),
                MAX_LOOKUP_RESULTS,
            ).filter { experience ->
                currentEntries.any { entry ->
                    entry.toolName == experience.primaryToolName &&
                        entry.schemaFingerprint == experience.schemaFingerprint
                }
            }.map { it.toSummary() }
        }
    }

    fun observeLibrary(subjectId: String, limit: Int = 500): Flow<List<ToolExperienceEntity>> =
        dao.observeLibrary(subjectId, limit.coerceIn(1, 500))

    fun observeRevisions(experienceId: String) = dao.observeRevisions(experienceId)

    fun observeEvidence(experienceId: String) = dao.observeEvidence(experienceId)

    suspend fun diagnostics(): ToolExperienceLibraryDiagnostics {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return ToolExperienceLibraryDiagnostics(
                authorityActive = false,
                totalCount = 0,
                activeCount = 0,
                staleCount = 0,
                redactionViolationCount = 0,
            )
        val rows = dao.listForDiagnostics(subject, limit = MAX_DIAGNOSTIC_ROWS)
        return ToolExperienceLibraryDiagnostics(
            authorityActive = true,
            totalCount = rows.size,
            activeCount = rows.count { it.state == ToolExperienceState.ACTIVE.name },
            staleCount = rows.count {
                it.state == ToolExperienceState.STALE_SCHEMA.name ||
                    it.state == ToolExperienceState.STALE_AUTHORITY.name
            },
            redactionViolationCount = rows.count { experience ->
                ToolExperienceContentPolicy.normalize(
                    title = experience.title,
                    body = experience.body,
                    tags = parseTags(experience.tagsJson),
                ) == null
            },
        )
    }

    /** Called after a tracked, non-failing second-user tool completion. */
    suspend fun record(signal: ToolExperienceSignal): ToolExperienceSummary? {
        if (signal.authoritySubjectId != SecondUserAuthorityRegistry.current()?.subjectId) return null
        if (signal.origin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return null
        if (signal.source == ToolDescriptorSource.MCP || signal.source == ToolDescriptorSource.PLUGIN) return null
        if (!safeIdentifier(signal.toolName) || !safeIdentifier(signal.executionId)) return null
        val toolNames = signal.toolNames.distinct().filter(::safeIdentifier).take(MAX_TOOL_NAMES)
            .ifEmpty { listOf(signal.toolName) }
        val now = nowMs()
        val result = database.withTransaction {
            // A ToolRuntime completion can be observed more than once while the model/UI
            // stream is replayed. The opaque execution id is the idempotency boundary; a
            // duplicate must not alter prose, confidence, evidence, or stateVersion.
            if (dao.hasEvidenceForExecution(signal.executionId)) {
                return@withTransaction dao.getBySignature(
                    subjectId = signal.authoritySubjectId,
                    toolName = signal.toolName,
                    fingerprint = signal.schemaFingerprint,
                )?.toSummary()
            }
            dao.staleOldAuthorities(signal.authoritySubjectId, now)
            dao.staleOtherFingerprints(signal.authoritySubjectId, signal.toolName, signal.schemaFingerprint, now)
            val existing = dao.getBySignature(
                subjectId = signal.authoritySubjectId,
                toolName = signal.toolName,
                fingerprint = signal.schemaFingerprint,
            )
            val experience = if (existing == null) {
                val created = ToolExperienceEntity(
                    experienceId = idGenerator(),
                    authoritySubjectId = signal.authoritySubjectId,
                    primaryToolName = signal.toolName,
                    toolNamesJson = json.encodeToString(toolNames),
                    categoryPath = signal.categoryPath.take(MAX_CATEGORY_CHARS),
                    schemaFingerprint = signal.schemaFingerprint.take(MAX_FINGERPRINT_CHARS),
                    title = defaultTitle(signal.toolName),
                    body = defaultBody(signal.toolName, signal.categoryPath),
                    tagsJson = "[]",
                    state = ToolExperienceState.ACTIVE.name,
                    confidence = signal.outcome.confidence.name,
                    stateVersion = 0,
                    createdAtMs = now,
                    updatedAtMs = now,
                    lastObservedAtMs = now,
                    lastVerifiedAtMs = now.takeIf { signal.outcome.confidence == ToolExperienceConfidence.VERIFIED },
                )
                dao.insertExperience(created)
                dao.insertRevision(
                    ToolExperienceRevisionEntity(
                        revisionId = idGenerator(),
                        experienceId = created.experienceId,
                        revision = 0,
                        actor = ToolExperienceActor.SYSTEM.name,
                        title = created.title,
                        body = created.body,
                        tagsJson = created.tagsJson,
                        createdAtMs = now,
                    ),
                )
                created
            } else {
                val nextConfidence = if (
                    existing.confidence == ToolExperienceConfidence.VERIFIED.name ||
                    signal.outcome.confidence == ToolExperienceConfidence.VERIFIED
                ) ToolExperienceConfidence.VERIFIED else ToolExperienceConfidence.OBSERVED
                if (existing.state == ToolExperienceState.ACTIVE.name) {
                    dao.touchSuccess(
                        id = existing.experienceId,
                        expectedVersion = existing.stateVersion,
                        confidence = nextConfidence.name,
                        observedAtMs = now,
                        verifiedAtMs = now.takeIf { nextConfidence == ToolExperienceConfidence.VERIFIED },
                        nowMs = now,
                    )
                } else if (existing.state == ToolExperienceState.STALE_SCHEMA.name) {
                    dao.reactivate(
                        id = existing.experienceId,
                        expectedVersion = existing.stateVersion,
                        confidence = nextConfidence.name,
                        observedAtMs = now,
                        verifiedAtMs = now.takeIf { nextConfidence == ToolExperienceConfidence.VERIFIED },
                        nowMs = now,
                    )
                }
                dao.get(existing.experienceId) ?: existing
            }
            // A user-disabled or soft-deleted entry remains an intentional opt-out. The
            // execution can still complete, but it must not silently repopulate the library.
            if (experience.state in setOf(
                    ToolExperienceState.DISABLED.name,
                    ToolExperienceState.SOFT_DELETED.name,
                )
            ) {
                return@withTransaction experience.toSummary()
            }
            dao.insertEvidence(
                ToolExperienceEvidenceEntity(
                    evidenceId = evidenceId(signal.executionId),
                    experienceId = experience.experienceId,
                    executionId = signal.executionId,
                    toolName = signal.toolName,
                    schemaFingerprint = signal.schemaFingerprint.take(MAX_FINGERPRINT_CHARS),
                    outcomeKind = signal.outcome.name,
                    createdAtMs = now,
                ),
            )
            dao.get(experience.experienceId)?.toSummary()
        }
        return result
    }

    override suspend fun edit(
        id: String,
        expectedVersion: Long,
        title: String,
        body: String,
        tags: List<String>,
    ): ToolExperienceEditResult {
        val result = editByActor(
            id = id,
            expectedVersion = expectedVersion,
            title = title,
            body = body,
            tags = tags,
            actor = ToolExperienceActor.SECOND_USER,
        )
        return when (result) {
            is ToolExperienceMutationResult.Updated -> ToolExperienceEditResult.Updated(result.stateVersion)
            ToolExperienceMutationResult.Missing -> ToolExperienceEditResult.Missing
            ToolExperienceMutationResult.Conflict -> ToolExperienceEditResult.Conflict
            ToolExperienceMutationResult.Invalid -> ToolExperienceEditResult.Invalid
            ToolExperienceMutationResult.Denied -> ToolExperienceEditResult.Denied
        }
    }

    suspend fun editByUser(
        id: String,
        expectedVersion: Long,
        title: String,
        body: String,
        tags: List<String>,
    ): ToolExperienceMutationResult = editByActor(
        id, expectedVersion, title, body, tags, ToolExperienceActor.USER,
    )

    private suspend fun editByActor(
        id: String,
        expectedVersion: Long,
        title: String,
        body: String,
        tags: List<String>,
        actor: ToolExperienceActor,
    ): ToolExperienceMutationResult {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return ToolExperienceMutationResult.Denied
        val normalized = ToolExperienceContentPolicy.normalize(title, body, tags)
            ?: return ToolExperienceMutationResult.Invalid
        val result = database.withTransaction {
            val existing = dao.get(id) ?: return@withTransaction ToolExperienceMutationResult.Missing
            if (existing.authoritySubjectId != subject) return@withTransaction ToolExperienceMutationResult.Denied
            val changed = dao.updateEditable(
                id = id,
                subjectId = subject,
                expectedVersion = expectedVersion,
                title = normalized.title,
                body = normalized.body,
                tagsJson = json.encodeToString(normalized.tags),
                nowMs = nowMs(),
            )
            if (changed == 0) {
                if (dao.get(id) == null) ToolExperienceMutationResult.Missing else ToolExperienceMutationResult.Conflict
            } else {
                val updated = checkNotNull(dao.get(id))
                dao.insertRevision(
                    ToolExperienceRevisionEntity(
                        revisionId = idGenerator(),
                        experienceId = updated.experienceId,
                        revision = updated.stateVersion,
                        actor = actor.name,
                        title = updated.title,
                        body = updated.body,
                        tagsJson = updated.tagsJson,
                        createdAtMs = updated.updatedAtMs,
                    ),
                )
                // Offset is zero-based: keep the newest 32 revisions, not 33.
                dao.revisionAtOffset(updated.experienceId, MAX_REVISIONS - 1)?.let { cutoff ->
                    dao.deleteRevisionsThrough(updated.experienceId, cutoff - 1)
                }
                ToolExperienceMutationResult.Updated(updated.stateVersion)
            }
        }
        return result
    }

    suspend fun setState(
        id: String,
        expectedVersion: Long,
        state: ToolExperienceState,
    ): ToolExperienceMutationResult {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId ?: return ToolExperienceMutationResult.Denied
        if (state !in setOf(
                ToolExperienceState.ACTIVE,
                ToolExperienceState.DISABLED,
                ToolExperienceState.SOFT_DELETED,
            )
        ) return ToolExperienceMutationResult.Denied
        val result = database.withTransaction {
            val existing = dao.get(id) ?: return@withTransaction ToolExperienceMutationResult.Missing
            if (existing.authoritySubjectId != subject) return@withTransaction ToolExperienceMutationResult.Denied
            // An old schema/authority cannot be made current by an edit or restore action.
            if (state == ToolExperienceState.ACTIVE && existing.state !in setOf(
                    ToolExperienceState.ACTIVE.name,
                    ToolExperienceState.DISABLED.name,
                    ToolExperienceState.SOFT_DELETED.name,
                )
            ) return@withTransaction ToolExperienceMutationResult.Denied
            val changed = dao.setState(
                id = id,
                subjectId = subject,
                expectedVersion = expectedVersion,
                state = state.name,
                deletedAtMs = nowMs().takeIf { state == ToolExperienceState.SOFT_DELETED },
                nowMs = nowMs(),
            )
            if (changed == 1) {
                ToolExperienceMutationResult.Updated(checkNotNull(dao.get(id)).stateVersion)
            } else {
                ToolExperienceMutationResult.Conflict
            }
        }
        if (result is ToolExperienceMutationResult.Updated) purgeDeleted()
        return result
    }

    suspend fun purgeDeleted(cutoffMs: Long = nowMs() - SOFT_DELETE_RETENTION_MS): Int =
        dao.purgeSoftDeleted(cutoffMs)

    /** Revocation is a host action; old authority-scoped procedures must never remain current. */
    suspend fun invalidateAuthoritySubjects(subjectIds: Set<String>): Int {
        if (subjectIds.isEmpty()) return 0
        return database.withTransaction {
            dao.staleAuthoritySubjects(subjectIds.toList(), nowMs())
        }
    }

    private fun ToolExperienceEntity.toSummary(): ToolExperienceSummary = ToolExperienceSummary(
        id = experienceId,
        toolName = primaryToolName,
        title = title,
        body = body,
        tags = runCatching {
            json.parseToJsonElement(tagsJson).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
            .getOrDefault(emptyList()),
        confidence = confidence,
        stateVersion = stateVersion,
    )

    private fun parseTags(raw: String): List<String> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrDefault(emptyList())

    private fun defaultTitle(toolName: String): String = "复用 $toolName"

    private fun defaultBody(toolName: String, category: String): String = buildString {
        append("适用范围：").append(category.take(MAX_CATEGORY_CHARS)).append("。\n")
        append("1. 先在工具目录打开 ").append(toolName).append(" 的当前定义。\n")
        append("2. 确认目录列出的权限、解锁状态和审批条件。\n")
        append("3. 仅按当前 Schema 填写经过用户确认的参数。\n")
        append("4. 检查返回状态；后台任务还要独立查询运行状态。")
    }

    private fun evidenceId(executionId: String): String = "evidence:" + MessageDigest.getInstance("SHA-256")
        .digest(executionId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun safeIdentifier(value: String): Boolean = value.isNotBlank() && value.length <= 480 &&
        value.none { it == '\n' || it == '\r' || it == '\u0000' }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_TOOL_NAMES = 3
        private const val MAX_LOOKUP_TOOL_NAMES = 4
        private const val MAX_LOOKUP_RESULTS = 2
        private const val MAX_CATEGORY_CHARS = 120
        private const val MAX_FINGERPRINT_CHARS = 128
        private const val MAX_REVISIONS = 32
        private const val MAX_DIAGNOSTIC_ROWS = 1_000
        private const val SOFT_DELETE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Central validation for model and UI authored prose. It is intentionally conservative. */
object ToolExperienceContentPolicy {
    data class Normalized(val title: String, val body: String, val tags: List<String>)

    private val unsafePattern = Regex(
        """(?is)(```|https?://|file://|(?:^|[\s"'])/[A-Za-z0-9_.~/-]+|(?:^|\s)(?:[A-Za-z]:\\|~[/\\])|(?:api[_-]?key|token|password|secret)\s*[:=]|(?:^|\s)(?:curl|wget|rm|sudo|apt|pkg|bash|sh|ls|cat|grep|find|cd|mkdir|chmod|chown|python3?|node|adb|ssh|scp|rsync|tar|zip|unzip|git|npm|bun|gradle|java)\s+)""",
    )
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val credentialToken = Regex("""(?i)\b(?:akia[a-z0-9]{16}|sk-[a-z0-9_-]{12,}|eyj[a-z0-9_-]{20,})\b""")

    fun normalize(title: String, body: String, tags: List<String>): Normalized? {
        val cleanTitle = title.trim().replace(Regex("[\\r\\n]+"), " ")
        val cleanBody = body.trim()
        val cleanTags = tags.map { it.trim() }.filter(String::isNotBlank).distinct().take(MAX_TAGS)
        if (cleanTitle.isBlank() || cleanTitle.length > MAX_TITLE_CHARS ||
            cleanBody.isBlank() || cleanBody.length > MAX_BODY_CHARS
        ) return null
        val all = cleanTitle + "\n" + cleanBody + "\n" + cleanTags.joinToString(" ")
        if (unsafePattern.containsMatchIn(all) || email.containsMatchIn(all) ||
            phone.containsMatchIn(all) || credentialToken.containsMatchIn(all)
        ) return null
        return Normalized(cleanTitle, cleanBody, cleanTags)
    }

    private const val MAX_TITLE_CHARS = 120
    private const val MAX_BODY_CHARS = 1_200
    private const val MAX_TAGS = 12
}
