package me.rerere.rikkahub.learning.storage.restore

import android.content.Context
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Deletes old-timeline quarantine only after the new Learning DB reports complete bootstrap for
 * the exact restored authority stream. Cleanup is exact and non-recursive; any unknown entry keeps
 * the journal in place and blocks another restore for diagnosis.
 */
object ColdRestoreRebuildFinalizer {
    fun completeIfProven(
        context: Context,
        streamId: String,
        bootstrapHeadSeq: Long,
        lastContiguousSeq: Long,
    ): Boolean = completeWithProof(
        context = context,
        streamId = streamId,
        expectedHeadSeq = bootstrapHeadSeq,
        absorbedHeadSeq = lastContiguousSeq,
    )

    /**
     * Completes cleanup when the derived Learning runtime is explicitly disabled.
     *
     * The restored main outbox is the authority and is not pruned in P0, so a future opt-in can
     * rebuild from it. The quarantined Learning database belongs to the previous main timeline
     * and must never be reused. Callers must first strictly validate the installed main stream.
     */
    fun completeWhenDerivedStateDisabled(
        context: Context,
        streamId: String,
        authorityHeadSeq: Long,
    ): Boolean = completeWithProof(
        context = context,
        streamId = streamId,
        expectedHeadSeq = authorityHeadSeq,
        absorbedHeadSeq = authorityHeadSeq,
    )

    private fun completeWithProof(
        context: Context,
        streamId: String,
        expectedHeadSeq: Long,
        absorbedHeadSeq: Long,
    ): Boolean {
        val appData = File(context.applicationInfo.dataDir)
        val stagingValidation = ColdRestoreStagingPaths.verify(appData, context.noBackupFilesDir)
        val staging = (stagingValidation as? ColdRestoreStagingPathValidation.Valid)?.paths
            ?: return false
        if (!Files.exists(staging.pendingJournal, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(staging.lockFile) ||
            !Files.isRegularFile(staging.lockFile, LinkOption.NOFOLLOW_LINKS)
        ) {
            return false
        }
        return runCatching {
            FileChannel.open(
                staging.lockFile,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: return false
                lock.use {
                    completeWhileLocked(
                        staging,
                        streamId,
                        expectedHeadSeq,
                        absorbedHeadSeq,
                    )
                }
            }
        }.getOrDefault(false)
    }

    private fun completeWhileLocked(
        staging: ColdRestoreStagingPaths,
        streamId: String,
        bootstrapHeadSeq: Long,
        lastContiguousSeq: Long,
    ): Boolean {
        var journal = when (val read = ColdRestoreJournalStore(staging.pendingJournal).read()) {
            is ColdRestoreJournalReadResult.Valid -> read.journal
            ColdRestoreJournalReadResult.Missing,
            is ColdRestoreJournalReadResult.Invalid,
            -> return false
        }
        if (journal.phase != ColdRestorePhase.REBUILD_REQUIRED &&
            journal.phase != ColdRestorePhase.COMPLETE
        ) {
            return false
        }
        if (journal.mainStream.streamId != streamId ||
            bootstrapHeadSeq != journal.mainStream.headSeq ||
            lastContiguousSeq < bootstrapHeadSeq
        ) {
            return false
        }
        if (journal.phase == ColdRestorePhase.REBUILD_REQUIRED) {
            val now = System.currentTimeMillis().coerceAtLeast(journal.updatedAtMs)
            val complete = journal.copy(
                stateVersion = journal.stateVersion + 1L,
                phase = ColdRestorePhase.COMPLETE,
                updatedAtMs = now,
            )
            val store = ColdRestoreJournalStore(staging.pendingJournal)
            if (store.transition(journal.requestId, journal.stateVersion, complete) !=
                ColdRestoreJournalWriteResult.Written
            ) {
                val reread = store.read()
                if (reread !is ColdRestoreJournalReadResult.Valid || reread.journal != complete) {
                    return false
                }
            }
            journal = complete
        }

        val databaseDirectory = contextDatabaseDirectory(staging) ?: return false
        val learningDirectory = exactQuarantine(
            databaseDirectory,
            ".learning_runtime_quarantine_",
            requireNotNull(journal.learningQuarantineId),
        ) ?: return false
        val mainDirectory = exactQuarantine(
            databaseDirectory,
            ".rikka_hub_quarantine_",
            requireNotNull(journal.mainQuarantineId),
        ) ?: return false
        if (!deleteExactBatch(learningDirectory, LEARNING_NAMES) ||
            !deleteExactBatch(mainDirectory, MAIN_NAMES)
        ) {
            return false
        }

        val requestDirectory = staging.requestDirectory(journal.requestId)
        if (!deleteExactRequestDirectory(requestDirectory, staging.rootDirectory)) return false
        return Files.deleteIfExists(staging.pendingJournal)
    }
}

private fun contextDatabaseDirectory(staging: ColdRestoreStagingPaths): Path? {
    val noBackup = staging.rootDirectory.parent ?: return null
    val appData = noBackup.parent ?: return null
    val databases = appData.resolve("databases").normalize()
    return databases.takeIf { candidate ->
        candidate.parent == appData && !Files.isSymbolicLink(candidate) &&
            Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { candidate.toFile().canonicalFile.toPath().normalize() == candidate }
                .getOrDefault(false)
    }
}

private fun exactQuarantine(parent: Path, prefix: String, id: String): Path? {
    if (!FINALIZER_ID.matches(id)) return null
    val directory = parent.resolve("$prefix$id").normalize()
    if (directory.parent != parent) return null
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return directory
    return directory.takeIf {
        !Files.isSymbolicLink(it) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { it.toFile().canonicalFile.toPath().normalize() == it }.getOrDefault(false)
    }
}

private fun deleteExactBatch(directory: Path, allowedNames: Set<String>): Boolean {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true
    if (Files.isSymbolicLink(directory) ||
        !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
    ) {
        return false
    }
    val entries = Files.newDirectoryStream(directory).use { it.toList() }
    if (entries.any { entry ->
            entry.parent != directory || entry.fileName.toString() !in allowedNames ||
                Files.isSymbolicLink(entry) ||
                !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
        }
    ) {
        return false
    }
    for (entry in entries) if (!Files.deleteIfExists(entry)) return false
    return Files.deleteIfExists(directory)
}

private fun deleteExactRequestDirectory(directory: Path, parent: Path): Boolean {
    if (directory.parent != parent || !FINALIZER_REQUEST.matches(directory.fileName.toString())) {
        return false
    }
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true
    if (
        Files.isSymbolicLink(directory) ||
        !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
    ) {
        return false
    }
    val entries = Files.newDirectoryStream(directory).use { it.toList() }
    if (entries.any { entry ->
            entry.parent != directory || entry.fileName.toString() != "archive.zip" ||
                Files.isSymbolicLink(entry) ||
                !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
        }
    ) {
        return false
    }
    for (entry in entries) if (!Files.deleteIfExists(entry)) return false
    return Files.deleteIfExists(directory)
}

private val FINALIZER_ID = Regex("[a-zA-Z0-9_-]{16,64}")
private val FINALIZER_REQUEST = Regex("request_[0-9a-f]{32}")
private val LEARNING_NAMES = setOf(
    "learning_runtime.db",
    "learning_runtime.db-wal",
    "learning_runtime.db-shm",
)
private val MAIN_NAMES = setOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm")
