package me.rerere.rikkahub.execution

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ManagedExecutionLedgerRecord(
    val schemaVersion: Int = 1,
    val executionId: String,
    val runtime: String,
    val nativeId: String,
    val ownerAssistantId: String,
    val ownerConversationId: String,
    val ownerOrigin: String,
    val status: String,
    /** Non-secret lookup label used to reopen a saved SSH profile after process restart. */
    val profileName: String? = null,
    val pid: Long? = null,
    val processGroupId: Long? = null,
    val processStartTicks: Long? = null,
    val tokenHash: String? = null,
    val stdoutPath: String? = null,
    val stderrPath: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(schemaVersion == 1)
        require(executionId.isNotBlank())
        require(runtime.isNotBlank())
        require(nativeId.isNotBlank())
        require(ownerAssistantId.isNotBlank())
        require(ownerConversationId.isNotBlank())
        require(profileName == null || (profileName.isNotBlank() && '\u0000' !in profileName))
        require(!stdoutPath.orEmpty().contains('\u0000'))
        require(!stderrPath.orEmpty().contains('\u0000'))
    }
}

interface ManagedExecutionLedger {
    suspend fun list(): List<ManagedExecutionLedgerRecord>
    suspend fun upsert(record: ManagedExecutionLedgerRecord)
    suspend fun remove(executionId: String)
}

internal object ManagedExecutionLedgerCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(records: List<ManagedExecutionLedgerRecord>): String =
        json.encodeToString(records.take(MAX_LEDGER_RECORDS))

    fun decode(raw: String): List<ManagedExecutionLedgerRecord> = runCatching {
        json.decodeFromString<List<ManagedExecutionLedgerRecord>>(raw)
            .filter { it.schemaVersion == 1 }
            .distinctBy(ManagedExecutionLedgerRecord::executionId)
            .take(MAX_LEDGER_RECORDS)
    }.getOrDefault(emptyList())

    private const val MAX_LEDGER_RECORDS = 128
}

class AtomicFileManagedExecutionLedger(
    context: Context,
) : ManagedExecutionLedger {
    private val mutex = Mutex()
    private val atomicFile = AtomicFile(
        File(context.filesDir, "managed-execution/ledger-v1.json").also { file ->
            file.parentFile?.mkdirs()
        }
    )

    override suspend fun list(): List<ManagedExecutionLedgerRecord> = mutex.withLock {
        readLocked()
    }

    override suspend fun upsert(record: ManagedExecutionLedgerRecord) = mutex.withLock {
        val updated = readLocked().associateByTo(linkedMapOf()) { it.executionId }
        updated[record.executionId] = record
        writeLocked(updated.values.toList())
    }

    override suspend fun remove(executionId: String) = mutex.withLock {
        writeLocked(readLocked().filterNot { it.executionId == executionId })
    }

    private suspend fun readLocked(): List<ManagedExecutionLedgerRecord> = withContext(Dispatchers.IO) {
        if (!atomicFile.baseFile.isFile) return@withContext emptyList()
        runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                ManagedExecutionLedgerCodec.decode(reader.readText())
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun writeLocked(records: List<ManagedExecutionLedgerRecord>) =
        withContext(Dispatchers.IO) {
            val output = atomicFile.startWrite()
            try {
                val writer = output.writer(Charsets.UTF_8)
                writer.write(ManagedExecutionLedgerCodec.encode(records))
                writer.flush()
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
        }
}
