package me.rerere.rikkahub.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetriever
import me.rerere.rikkahub.data.repository.MemorySearchIndex
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.store.DreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.RoomDreamObserverStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomMemoryAuthorityJournalInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var store: RoomMemoryProcessingStore
    private var generatedId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = newStore(RoomDreamObserverStore(database, database.dreamDao()))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun noOpDoesNotAdvanceEpochAndObserverFailureRollsBackAuthority() = runBlocking {
        val created = create(SCOPE_A, "same", nowMs = 10)
        assertEquals(1L, database.dreamDao().getScopeState(SCOPE_A)?.memoryEpoch)

        val noOp = store.mutate(
            MemoryMutationCommand.Update(
                memoryId = created.memoryId,
                expectedScopeId = SCOPE_A,
                expectedRevision = created.revision,
                content = "same",
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
            nowMs = 20,
        )
        assertEquals(created, noOp)
        assertEquals(1L, database.dreamDao().getScopeState(SCOPE_A)?.memoryEpoch)

        val delegate = RoomDreamObserverStore(database, database.dreamDao())
        val failingObserver = object : DreamObserverStore by delegate {
            override suspend fun recordAuthorityChangesInCurrentTransaction(
                request: RecordAuthorityChangesRequest,
            ) = error("forced_observer_failure")
        }
        val failure = runCatching {
            newStore(failingObserver).mutate(
                createCommand(SCOPE_B, "must rollback"),
                nowMs = 30,
            )
        }
        assertTrue(failure.isFailure)
        assertTrue(database.memoryDao().getMemoriesOfAssistant(SCOPE_B, Long.MAX_VALUE).isEmpty())
        assertNull(database.dreamDao().getScopeState(SCOPE_B))
    }

    @Test
    fun sourceBatchUsesOneEpochPerScopeAndPrivatePurgeUsesSummaryReceipt() = runBlocking {
        create(SCOPE_A, "private source", "conversation", listOf("message"), 10)
        create(GLOBAL, "global source", "conversation", listOf("message"), 11)

        store.invalidateSources(
            MemorySourceInvalidationBatch(
                conversationId = "conversation",
                scopes = listOf(
                    MemoryScopeSourceInvalidation(SCOPE_A, removedMessageIds = setOf("message")),
                    MemoryScopeSourceInvalidation(GLOBAL, removedMessageIds = setOf("message")),
                ),
            ),
            nowMs = 20,
        )
        listOf(SCOPE_A, GLOBAL).forEach { scopeId ->
            assertEquals(2L, database.dreamDao().getScopeState(scopeId)?.memoryEpoch)
            val changes = database.dreamDao().listChanges(scopeId, 1, 2)
            assertEquals(1, changes.map { it.memoryEpoch }.distinct().size)
            assertEquals(
                setOf(AuthorityEntityKind.MEMORY.name, AuthorityEntityKind.SOURCE.name),
                changes.mapTo(mutableSetOf()) { it.entityKind },
            )
        }

        store.purgeScope(SCOPE_A, nowMs = 30)
        val privateState = database.dreamDao().getScopeState(SCOPE_A)!!
        val purge = database.dreamDao().listChanges(SCOPE_A, 2, privateState.memoryEpoch)
        assertEquals(listOf(AuthorityEntityKind.SCOPE_PURGE.name), purge.map { it.entityKind })
        store.purgeScope(SCOPE_A, nowMs = 40)
        assertEquals(privateState.memoryEpoch, database.dreamDao().getScopeState(SCOPE_A)?.memoryEpoch)
    }

    private suspend fun create(
        scopeId: String,
        content: String,
        conversationId: String? = null,
        messageIds: List<String> = emptyList(),
        nowMs: Long,
    ): MemoryMutationResult.Applied = store.mutate(
        createCommand(scopeId, content, conversationId, messageIds),
        nowMs,
    ) as MemoryMutationResult.Applied

    private fun createCommand(
        scopeId: String,
        content: String,
        conversationId: String? = null,
        messageIds: List<String> = emptyList(),
    ) = MemoryMutationCommand.Create(
        scopeId = scopeId,
        title = content,
        content = content,
        approvalSource = MemoryApprovalSource.MANUAL_UI,
        sourceType = if (conversationId == null) "MANUAL" else "AUTO_EXTRACTION",
        sourceConversationId = conversationId,
        sourceMessageIds = messageIds,
        originAssistantId = SCOPE_A.takeIf { scopeId == GLOBAL } ?: scopeId,
    )

    private fun newStore(observer: DreamObserverStore) = RoomMemoryProcessingStore(
        database = database,
        memoryDao = database.memoryDao(),
        memoryV2Dao = database.memoryV2Dao(),
        retriever = MemoryRetriever(MemorySearchIndex { _, _, _ -> emptyList() }),
        json = Json {},
        dreamObserverStore = observer,
        idGenerator = { "journal-id-${generatedId++}" },
    )

    private companion object {
        const val SCOPE_A = "11111111-1111-1111-1111-111111111111"
        const val SCOPE_B = "22222222-2222-2222-2222-222222222222"
        const val GLOBAL = MemoryRepository.GLOBAL_MEMORY_ID
    }
}
