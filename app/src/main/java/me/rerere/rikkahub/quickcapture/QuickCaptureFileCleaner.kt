package me.rerere.rikkahub.quickcapture

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.files.FilesManager

const val QUICK_CAPTURE_FILE_PREFIX = "quick-capture-"
const val QUICK_CAPTURE_ORPHAN_AGE_MS = 10L * 60L * 1_000L

data class QuickCaptureCleanupResult(
    val examined: Int,
    val deleted: Int,
    val retained: Int,
)

fun shouldDeleteQuickCaptureFile(
    displayName: String,
    createdAtMs: Long,
    nowMs: Long,
    referenced: Boolean,
): Boolean = displayName.startsWith(QUICK_CAPTURE_FILE_PREFIX) &&
    nowMs - createdAtMs >= QUICK_CAPTURE_ORPHAN_AGE_MS &&
    !referenced

/**
 * Conservative orphan sweep. It does not infer attachment ownership from filenames: each old
 * candidate is retained whenever a persisted message node or active durable command mentions its
 * exact app-private file URI.
 */
class QuickCaptureFileCleaner(
    private val filesManager: FilesManager,
    private val conversationDao: ConversationDAO,
    private val messageNodeDao: MessageNodeDAO,
    private val pendingCommandDao: PendingChatCommandDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun cleanup(): QuickCaptureCleanupResult = withContext(Dispatchers.IO) {
        val candidates = filesManager.list().filter { it.displayName.startsWith(QUICK_CAPTURE_FILE_PREFIX) }
        if (candidates.isEmpty()) return@withContext QuickCaptureCleanupResult(0, 0, 0)
        val activePayloads = pendingCommandDao.listActive().map { it.payloadJson }
        val nodesByConversation = conversationDao.getAllIds().associateWith { id ->
            messageNodeDao.getNodesOfConversation(id).map { node -> node.messages }
        }
        var deleted = 0
        var retained = 0
        candidates.forEach { entity ->
            val uri = filesManager.getFile(entity).toUri().toString()
            val referenced = activePayloads.any { it.contains(uri) } ||
                nodesByConversation.values.any { messages -> messages.any { it.contains(uri) } }
            if (shouldDeleteQuickCaptureFile(entity.displayName, entity.createdAt, nowMs(), referenced)) {
                if (filesManager.deleteManagedFile(entity)) deleted++ else retained++
            } else {
                retained++
            }
        }
        QuickCaptureCleanupResult(candidates.size, deleted, retained)
    }
}
