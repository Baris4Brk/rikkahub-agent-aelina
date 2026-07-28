package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pet_dialogue_sessions",
    indices = [
        Index("assistantId"),
        Index("privilegedConversationId"),
        Index(value = ["assistantId", "status"]),
        Index(value = ["assistantId", "localDate"]),
        Index(value = ["activeOwnerKey"], unique = true),
        Index("deletedAtMs"),
    ],
)
data class PetDialogueSessionEntity(
    @PrimaryKey val sessionId: String,
    val assistantId: String,
    val privilegedConversationId: String,
    val localDate: String,
    val zoneId: String,
    val activeOwnerKey: String?,
    val status: String,
    val archiveReason: String?,
    val title: String,
    val summary: String,
    val notes: String,
    val tagsJson: String,
    val summaryState: String,
    val stateVersion: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?,
    val deletedAtMs: Long?,
)

@Entity(
    tableName = "pet_dialogue_turns",
    foreignKeys = [
        ForeignKey(
            entity = PetDialogueSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "sequence"], unique = true),
        Index("handoffRequestId"),
    ],
)
data class PetDialogueTurnEntity(
    @PrimaryKey val turnId: String,
    val sessionId: String,
    val sequence: Int,
    val inputKind: String,
    val userText: String?,
    val interactionJson: String?,
    val assistantText: String?,
    val action: String?,
    val handoffRequestId: String?,
    val createdAtMs: Long,
)

@Entity(
    tableName = "pet_handoff_requests",
    foreignKeys = [
        ForeignKey(
            entity = PetDialogueSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PetDialogueTurnEntity::class,
            parentColumns = ["turnId"],
            childColumns = ["turnId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("turnId"),
        Index(value = ["assistantId", "status"]),
        Index("targetCommandId"),
        Index("expiresAtMs"),
    ],
)
data class PetHandoffRequestEntity(
    @PrimaryKey val requestId: String,
    val sessionId: String,
    val turnId: String,
    val assistantId: String,
    val privilegedConversationId: String,
    val mode: String,
    val status: String,
    val title: String,
    val request: String,
    val targetCommandId: String?,
    val stateVersion: Long,
    val createdAtMs: Long,
    val submittedAtMs: Long?,
    val resolvedAtMs: Long?,
    val expiresAtMs: Long?,
)

@Entity(
    tableName = "pet_dialogue_revisions",
    foreignKeys = [
        ForeignKey(
            entity = PetDialogueSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "revision"], unique = true),
        Index("createdAtMs"),
    ],
)
data class PetDialogueRevisionEntity(
    @PrimaryKey val revisionId: String,
    val sessionId: String,
    val revision: Long,
    val actor: String,
    val operation: String,
    val previousTitle: String,
    val previousSummary: String,
    val previousNotes: String,
    val previousTagsJson: String,
    val previousStatus: String,
    val createdAtMs: Long,
)
