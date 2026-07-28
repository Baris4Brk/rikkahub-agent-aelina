package me.rerere.rikkahub.pet

import androidx.room.withTransaction
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.PetDialogueDao
import me.rerere.rikkahub.data.db.entity.PetDialogueRevisionEntity
import me.rerere.rikkahub.data.db.entity.PetDialogueSessionEntity
import me.rerere.rikkahub.data.db.entity.PetDialogueTurnEntity
import me.rerere.rikkahub.data.db.entity.PetHandoffRequestEntity

data class PetDialogueTurnDraft(
    val inputKind: PetDialogueInputKind,
    val userText: String? = null,
    val interactionJson: String? = null,
    val assistantText: String? = null,
    val action: PetAction? = null,
    val handoff: PetHandoffDraft? = null,
)

data class PetHandoffDraft(
    val mode: PetHandoffMode,
    val title: String,
    val request: String,
)

data class ActivePetDialogue(
    val session: PetDialogueSessionEntity,
    val turns: List<PetDialogueTurnEntity>,
)

sealed interface PetArchiveResult {
    data class Archived(
        val archivedSessionId: String,
        val newSessionId: String,
    ) : PetArchiveResult

    data object Empty : PetArchiveResult
}

sealed interface PetMetadataResult {
    data class Updated(val stateVersion: Long) : PetMetadataResult
    data object Missing : PetMetadataResult
    data object Conflict : PetMetadataResult
    data object InvalidState : PetMetadataResult
}

/**
 * Transaction boundary for the immutable 20-round pet sidecar.
 *
 * All rolling, archiving and editable diary metadata mutations pass through this class. The
 * nullable unique activeOwnerKey also keeps the single-active-session invariant in SQLite.
 */
class PetDialogueRepository(
    private val database: AppDatabase,
    private val dao: PetDialogueDao = database.petDialogueDao(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idGenerator: () -> String = { Uuid.random().toString() },
    private val summaryScheduler: PetSummaryScheduler = PetSummaryScheduler { },
) {
    private val mutationMutex = Mutex()

    fun observeActive(
        assistantId: String,
        privilegedConversationId: String,
    ): Flow<ActivePetDialogue?> = dao.observeActiveSession(assistantId, privilegedConversationId)
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(null)
            } else {
                dao.observeTurns(session.sessionId).flatMapLatest { turns ->
                    flowOf(ActivePetDialogue(session, turns))
                }
            }
        }

    fun observeArchives(assistantId: String, limit: Int = 100) =
        dao.observeArchives(assistantId, limit.coerceIn(1, 500))

    fun observeRevisions(sessionId: String) = dao.observeRevisions(sessionId)

    fun observeDiaryIndex(assistantId: String, limit: Int = 500) =
        dao.observeDiaryIndex(assistantId, limit.coerceIn(1, 500))

    fun observePendingHandoffs(assistantId: String) = dao.observePendingHandoffs(assistantId)

    suspend fun ensureActive(
        assistantId: String,
        privilegedConversationId: String,
        zoneId: ZoneId = clock.zone,
    ): PetDialogueSessionEntity {
        val archived = mutableListOf<String>()
        val result = mutationMutex.withLock {
            database.withTransaction {
                dao.expireHandoffs(clock.millis())
                ensureActiveLocked(assistantId, privilegedConversationId, now(zoneId), archived)
            }
        }
        archived.forEach(summaryScheduler::schedule)
        return result
    }

    suspend fun append(
        assistantId: String,
        privilegedConversationId: String,
        draft: PetDialogueTurnDraft,
        zoneId: ZoneId = clock.zone,
    ): ActivePetDialogue {
        validateTurn(draft)
        val archived = mutableListOf<String>()
        val result = mutationMutex.withLock {
            database.withTransaction {
                dao.expireHandoffs(clock.millis())
                val timestamp = now(zoneId)
                var session = ensureActiveLocked(assistantId, privilegedConversationId, timestamp, archived)
                var count = dao.countTurns(session.sessionId)
                if (count >= MAX_PET_DIALOGUE_ROUNDS) {
                    archiveLocked(session, PetDialogueArchiveReason.CAPACITY, timestamp)
                    archived += session.sessionId
                    session = createActive(assistantId, privilegedConversationId, timestamp)
                    count = 0
                }

            val turnId = idGenerator()
            val handoffId = draft.handoff?.let { idGenerator() }
            val turn = PetDialogueTurnEntity(
                turnId = turnId,
                sessionId = session.sessionId,
                sequence = count + 1,
                inputKind = draft.inputKind.name,
                userText = draft.userText,
                interactionJson = draft.interactionJson,
                assistantText = draft.assistantText,
                action = draft.action?.name,
                handoffRequestId = handoffId,
                createdAtMs = timestamp.toInstant().toEpochMilli(),
            )
            dao.insertTurn(turn)
            draft.handoff?.let { handoff ->
                dao.insertHandoff(
                    PetHandoffRequestEntity(
                        requestId = checkNotNull(handoffId),
                        sessionId = session.sessionId,
                        turnId = turnId,
                        assistantId = assistantId,
                        privilegedConversationId = privilegedConversationId,
                        mode = handoff.mode.name,
                        status = PetHandoffStatus.DRAFT.name,
                        title = handoff.title.trim().take(160),
                        request = handoff.request.trim().take(2_000),
                        targetCommandId = null,
                        stateVersion = 0,
                        createdAtMs = timestamp.toInstant().toEpochMilli(),
                        submittedAtMs = null,
                        resolvedAtMs = null,
                        expiresAtMs = timestamp.plusHours(HANDOFF_EXPIRY_HOURS).toInstant().toEpochMilli(),
                    ),
                )
            }
                ActivePetDialogue(session, dao.getTurns(session.sessionId))
            }
        }
        archived.forEach(summaryScheduler::schedule)
        return result
    }

    suspend fun archiveNow(
        assistantId: String,
        privilegedConversationId: String,
        zoneId: ZoneId = clock.zone,
    ): PetArchiveResult {
        val archived = mutableListOf<String>()
        val result = mutationMutex.withLock {
            database.withTransaction {
                val timestamp = now(zoneId)
                val session = ensureActiveLocked(assistantId, privilegedConversationId, timestamp, archived)
                if (dao.countTurns(session.sessionId) == 0) {
                    PetArchiveResult.Empty
                } else {
                    archiveLocked(session, PetDialogueArchiveReason.MANUAL, timestamp)
                    archived += session.sessionId
                    val replacement = createActive(assistantId, privilegedConversationId, timestamp)
                    PetArchiveResult.Archived(session.sessionId, replacement.sessionId)
                }
            }
        }
        archived.forEach(summaryScheduler::schedule)
        return result
    }

    suspend fun updateMetadata(
        sessionId: String,
        expectedVersion: Long,
        title: String,
        summary: String,
        notes: String,
        tagsJson: String,
        summaryState: PetSummaryState,
        actor: String,
    ): PetMetadataResult = mutationMutex.withLock {
        database.withTransaction {
            val current = dao.getSession(sessionId) ?: return@withTransaction PetMetadataResult.Missing
            if (current.stateVersion != expectedVersion) return@withTransaction PetMetadataResult.Conflict
            val nowMs = clock.millis()
            val changed = dao.updateMetadata(
                sessionId = sessionId,
                expectedVersion = expectedVersion,
                title = title.trim().take(160),
                summary = summary.trim().take(4_000),
                notes = notes.trim().take(8_000),
                tagsJson = tagsJson.take(MAX_TAGS_JSON_CHARS),
                summaryState = summaryState.name,
                nowMs = nowMs,
            )
            if (changed != 1) return@withTransaction PetMetadataResult.Conflict
            insertRevision(current, actor, "UPDATE_METADATA", nowMs)
            PetMetadataResult.Updated(expectedVersion + 1)
        }
    }

    suspend fun softDelete(
        sessionId: String,
        expectedVersion: Long,
        actor: String,
    ): PetMetadataResult = changeArchiveStatus(
        sessionId = sessionId,
        expectedVersion = expectedVersion,
        expectedStatus = PetDialogueSessionStatus.ARCHIVED,
        nextStatus = PetDialogueSessionStatus.SOFT_DELETED,
        actor = actor,
        operation = "SOFT_DELETE",
    )

    suspend fun restore(
        sessionId: String,
        expectedVersion: Long,
        actor: String,
    ): PetMetadataResult = changeArchiveStatus(
        sessionId = sessionId,
        expectedVersion = expectedVersion,
        expectedStatus = PetDialogueSessionStatus.SOFT_DELETED,
        nextStatus = PetDialogueSessionStatus.ARCHIVED,
        actor = actor,
        operation = "RESTORE",
    )

    suspend fun purgeExpiredTrash(retentionDays: Long = 30): Int =
        dao.purgeDeleted(clock.millis() - retentionDays.coerceAtLeast(1) * MILLIS_PER_DAY)

    suspend fun getSession(sessionId: String) = dao.getSession(sessionId)

    suspend fun getTurns(sessionId: String) = dao.getTurns(sessionId)

    suspend fun getRevisions(sessionId: String) = dao.getRevisions(sessionId)

    suspend fun deletePermanently(sessionId: String, assistantId: String, expectedVersion: Long): Boolean =
        mutationMutex.withLock {
            database.withTransaction { dao.deletePermanently(sessionId, assistantId, expectedVersion) == 1 }
        }

    private suspend fun changeArchiveStatus(
        sessionId: String,
        expectedVersion: Long,
        expectedStatus: PetDialogueSessionStatus,
        nextStatus: PetDialogueSessionStatus,
        actor: String,
        operation: String,
    ): PetMetadataResult = mutationMutex.withLock {
        database.withTransaction {
            val current = dao.getSession(sessionId) ?: return@withTransaction PetMetadataResult.Missing
            if (current.stateVersion != expectedVersion) return@withTransaction PetMetadataResult.Conflict
            if (current.status != expectedStatus.name) return@withTransaction PetMetadataResult.InvalidState
            val nowMs = clock.millis()
            val changed = dao.setStatus(
                sessionId = sessionId,
                expectedVersion = expectedVersion,
                nextStatus = nextStatus.name,
                deletedAtMs = if (nextStatus == PetDialogueSessionStatus.SOFT_DELETED) nowMs else null,
                nowMs = nowMs,
            )
            if (changed != 1) return@withTransaction PetMetadataResult.Conflict
            insertRevision(current, actor, operation, nowMs)
            PetMetadataResult.Updated(expectedVersion + 1)
        }
    }

    private suspend fun ensureActiveLocked(
        assistantId: String,
        privilegedConversationId: String,
        timestamp: ZonedDateTime,
        archived: MutableList<String>,
    ): PetDialogueSessionEntity {
        val existing = dao.getActiveSession(assistantId, privilegedConversationId)
            ?: return createActive(assistantId, privilegedConversationId, timestamp)
        val date = timestamp.toLocalDate().toString()
        if (existing.localDate == date && existing.zoneId == timestamp.zone.id) return existing

        if (dao.countTurns(existing.sessionId) == 0) {
            check(
                dao.rollEmptySessionDate(
                    sessionId = existing.sessionId,
                    expectedVersion = existing.stateVersion,
                    localDate = date,
                    zoneId = timestamp.zone.id,
                    nowMs = timestamp.toInstant().toEpochMilli(),
                ) == 1,
            ) { "pet_dialogue_empty_roll_conflict" }
            return checkNotNull(dao.getSession(existing.sessionId))
        }

        archiveLocked(existing, PetDialogueArchiveReason.DAILY, timestamp)
        archived += existing.sessionId
        return createActive(assistantId, privilegedConversationId, timestamp)
    }

    private suspend fun archiveLocked(
        session: PetDialogueSessionEntity,
        reason: PetDialogueArchiveReason,
        timestamp: ZonedDateTime,
    ) {
        check(dao.countTurns(session.sessionId) > 0) { "pet_dialogue_empty_archive" }
        check(
            dao.archiveSession(
                sessionId = session.sessionId,
                expectedVersion = session.stateVersion,
                nextStatus = PetDialogueSessionStatus.ARCHIVED.name,
                archiveReason = reason.name,
                summaryState = PetSummaryState.PENDING.name,
                nowMs = timestamp.toInstant().toEpochMilli(),
            ) == 1,
        ) { "pet_dialogue_archive_conflict" }
    }

    private suspend fun createActive(
        assistantId: String,
        privilegedConversationId: String,
        timestamp: ZonedDateTime,
    ): PetDialogueSessionEntity {
        val nowMs = timestamp.toInstant().toEpochMilli()
        val session = PetDialogueSessionEntity(
            sessionId = idGenerator(),
            assistantId = assistantId,
            privilegedConversationId = privilegedConversationId,
            localDate = timestamp.toLocalDate().toString(),
            zoneId = timestamp.zone.id,
            activeOwnerKey = "$assistantId:$privilegedConversationId",
            status = PetDialogueSessionStatus.ACTIVE.name,
            archiveReason = null,
            title = "",
            summary = "",
            notes = "",
            tagsJson = "[]",
            summaryState = PetSummaryState.NONE.name,
            stateVersion = 0,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            archivedAtMs = null,
            deletedAtMs = null,
        )
        dao.insertSession(session)
        return session
    }

    private suspend fun insertRevision(
        previous: PetDialogueSessionEntity,
        actor: String,
        operation: String,
        nowMs: Long,
    ) {
        dao.insertRevision(
            PetDialogueRevisionEntity(
                revisionId = idGenerator(),
                sessionId = previous.sessionId,
                revision = previous.stateVersion + 1,
                actor = actor.take(160),
                operation = operation,
                previousTitle = previous.title,
                previousSummary = previous.summary,
                previousNotes = previous.notes,
                previousTagsJson = previous.tagsJson,
                previousStatus = previous.status,
                createdAtMs = nowMs,
            ),
        )
    }

    private fun validateTurn(draft: PetDialogueTurnDraft) {
        when (draft.inputKind) {
            PetDialogueInputKind.TEXT -> {
                require(!draft.userText.isNullOrBlank()) { "pet_dialogue_text_empty" }
                require(codePointCount(draft.userText) <= MAX_PET_INPUT_CODE_POINTS) {
                    "pet_dialogue_text_too_long"
                }
                require(draft.interactionJson == null) { "pet_dialogue_text_has_touch_payload" }
            }
            PetDialogueInputKind.TOUCH -> {
                require(!draft.interactionJson.isNullOrBlank()) { "pet_dialogue_touch_empty" }
                require(draft.userText == null) { "pet_dialogue_touch_has_text" }
            }
        }
        draft.assistantText?.let {
            require(codePointCount(it) <= MAX_PET_RESPONSE_CODE_POINTS) { "pet_dialogue_response_too_long" }
        }
    }

    private fun now(zoneId: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(clock.instant(), zoneId)

    private fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

    private companion object {
        const val HANDOFF_EXPIRY_HOURS = 2L
        const val MAX_TAGS_JSON_CHARS = 4_000
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
