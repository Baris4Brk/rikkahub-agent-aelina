package me.rerere.rikkahub.data.capability

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin

/** Persisted scoped grants. This table stores no secret material or tool arguments. */
@Entity(
    tableName = "capability_grants",
    indices = [
        Index(name = "idx_capability_grants_subject", value = ["subject_id", "subject_type"]),
        Index(name = "idx_capability_grants_active", value = ["revoked", "expires_at_ms"]),
    ],
)
data class CapabilityGrantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "subject_type")
    val subjectType: String,
    @ColumnInfo(name = "capability_key")
    val capabilityKey: String,
    @ColumnInfo(name = "resource_kind")
    val resourceKind: String,
    @ColumnInfo(name = "resource_identifier")
    val resourceIdentifier: String,
    @ColumnInfo(name = "allowed_origins")
    val allowedOrigins: String,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long? = null,
    @ColumnInfo(name = "revoked")
    val revoked: Boolean = false,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Dao
interface CapabilityGrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CapabilityGrantEntity)

    @Update
    suspend fun update(entity: CapabilityGrantEntity)

    @Query(
        "SELECT * FROM capability_grants WHERE revoked = 0 " +
            "AND (expires_at_ms IS NULL OR expires_at_ms > :nowMs)",
    )
    suspend fun listActive(nowMs: Long): List<CapabilityGrantEntity>

    @Query("SELECT * FROM capability_grants WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CapabilityGrantEntity?
}

/**
 * In-memory snapshot backed by Room. A cold process fails closed until [refresh] has completed;
 * callers never block a tool gate on disk I/O. The UI/admission layer can call [upsert] or
 * [revoke] and observes the new snapshot synchronously after the write returns.
 */
class CapabilityGrantRepository(
    private val dao: CapabilityGrantDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val snapshot = AtomicReference<List<AccessGrant>>(emptyList())

    fun current(): Collection<AccessGrant> = snapshot.get()

    suspend fun refresh(): List<AccessGrant> {
        val now = nowMs()
        val stored = dao.listActive(now)
        val upgraded = stored.map { entity ->
            val normalized = normalizeStoredGrantOrigins(
                subjectType = SubjectType.entries.firstOrNull { it.name == entity.subjectType },
                origins = parseStoredOrigins(entity.allowedOrigins),
            )
            val serialized = serializeStoredOrigins(normalized)
            if (serialized != entity.allowedOrigins) {
                entity.copy(allowedOrigins = serialized, updatedAtMs = now).also { dao.update(it) }
            } else {
                entity
            }
        }
        val active = upgraded.mapNotNull { entity -> entity.toAccessGrant() }
        snapshot.set(active)
        return active
    }

    suspend fun upsert(grant: AccessGrant) {
        val now = nowMs()
        val existing = dao.getById(grant.id)
        dao.upsert(
            CapabilityGrantEntity(
                id = grant.id,
                subjectId = grant.subjectId,
                subjectType = grant.subjectType.name,
                capabilityKey = grant.capability.value,
                resourceKind = grant.resourceKind,
                resourceIdentifier = grant.resourceIdentifier,
                allowedOrigins = serializeStoredOrigins(grant.allowedOrigins),
                scope = grant.scope.name,
                expiresAtMs = grant.expiresAtMs,
                revoked = grant.revoked,
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now,
            ),
        )
        refresh()
    }

    suspend fun revoke(id: String): Boolean {
        val existing = dao.getById(id) ?: return false
        dao.update(existing.copy(revoked = true, updatedAtMs = nowMs()))
        refresh()
        return true
    }

    private fun CapabilityGrantEntity.toAccessGrant(): AccessGrant? = runCatching {
        AccessGrant(
            id = id,
            subjectId = subjectId,
            subjectType = SubjectType.entries.first { it.name == subjectType },
            capability = CapabilityKey.of(capabilityKey),
            resourceKind = resourceKind,
            resourceIdentifier = resourceIdentifier,
            allowedOrigins = normalizeStoredGrantOrigins(
                subjectType = SubjectType.entries.first { it.name == subjectType },
                origins = parseStoredOrigins(allowedOrigins),
            ),
            scope = GrantScope.entries.first { it.name == scope },
            expiresAtMs = expiresAtMs,
            revoked = revoked,
        )
    }.getOrNull()
}

private val LEGACY_LOCAL_SECOND_USER_ORIGINS = setOf(
    ToolCallOrigin.LocalChat,
    ToolCallOrigin.SystemAssistant,
)

/** Upgrade only the exact legacy local set; custom or remote grants retain their original scope. */
internal fun normalizeStoredGrantOrigins(
    subjectType: SubjectType?,
    origins: Set<ToolCallOrigin>,
): Set<ToolCallOrigin> = if (
    subjectType == SubjectType.LOCAL_SECOND_USER &&
    origins == LEGACY_LOCAL_SECOND_USER_ORIGINS
) {
    InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER
} else {
    origins
}

private fun parseStoredOrigins(raw: String): Set<ToolCallOrigin> = raw.split('|')
    .mapNotNull { name -> ToolCallOrigin.entries.firstOrNull { it.name == name } }
    .toSet()

private fun serializeStoredOrigins(origins: Set<ToolCallOrigin>): String =
    ToolCallOrigin.entries.filter(origins::contains).joinToString("|") { it.name }
