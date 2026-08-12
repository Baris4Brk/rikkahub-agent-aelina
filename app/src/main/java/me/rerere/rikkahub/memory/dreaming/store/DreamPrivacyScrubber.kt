package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.requireDreamBoundedText

sealed interface DreamPrivacyTarget {
    data class AuthorityMemory(val memoryId: String) : DreamPrivacyTarget {
        init {
            requireDreamBoundedText(memoryId, 512, "memoryId")
        }
    }

    data class ConversationSource(
        val conversationId: String,
        val messageIds: Set<String>,
    ) : DreamPrivacyTarget {
        init {
            requireDreamBoundedText(conversationId, 512, "conversationId")
            require(messageIds.isNotEmpty() && messageIds.size <= 4_096)
            messageIds.forEach { requireDreamBoundedText(it, 512, "messageId") }
        }
    }

    data object EntireScope : DreamPrivacyTarget
}

data class DreamPrivacyScrubRequest(
    val scopeId: DreamScopeId,
    val targets: List<DreamPrivacyTarget>,
    val scrubbedAtEpochMs: Long,
) {
    init {
        require(targets.isNotEmpty() && targets.size <= 4_096)
        require(scrubbedAtEpochMs >= 0L)
        require(targets.count { it == DreamPrivacyTarget.EntireScope } <= 1)
        require(DreamPrivacyTarget.EntireScope !in targets || targets.size == 1)
    }
}

sealed interface DreamPrivacyScrubResult {
    data class Scrubbed(
        val claimsTombstoned: Int,
        val versionsCleared: Int,
        val snapshotsTombstoned: Int,
        val sourceRowsDeleted: Int,
        val activeSnapshotCleared: Boolean,
        val nextDreamRevision: Long,
    ) : DreamPrivacyScrubResult {
        init {
            require(claimsTombstoned >= 0 && versionsCleared >= 0 && snapshotsTombstoned >= 0)
            require(sourceRowsDeleted >= 0 && nextDreamRevision >= 0L)
        }
    }

    data class Rejected(val reason: DreamPrivacyScrubRejection) : DreamPrivacyScrubResult
}

enum class DreamPrivacyScrubRejection {
    SCOPE_MISSING,
    BOUNDED_MANIFEST_INVALID,
    ACTIVE_SNAPSHOT_CAS_CONFLICT,
    STORE_CORRUPTION,
}

/**
 * Must be invoked inside the same outer Room transaction, before authority deletion. A successful
 * implementation tombstones claim heads (clearing title/statement), clears version JSON, tombstones
 * and clears snapshot payloads, clears the active pointer with dream-revision CAS, then deletes
 * provenance rows. Any rejection must abort the outer authority mutation.
 */
fun interface DreamPrivacyScrubber {
    suspend fun scrubInCurrentTransaction(request: DreamPrivacyScrubRequest): DreamPrivacyScrubResult
}
