package me.rerere.rikkahub.memory.dreaming.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceTombstoneEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.memory.MemorySourceKind
import me.rerere.rikkahub.memory.MemorySourceRole
import me.rerere.rikkahub.memory.memorySourceTextDigest
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomDreamSourceReaderTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: RoomDreamSourceReader

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        reader = RoomDreamSourceReader(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun read_usesOnlySelectedHostSourceAndExactDigest() = runBlocking {
        insertConversation(SCOPE)
        val messageId = "10000000-0000-0000-0000-000000000001"
        val selectedText = "selected authoritative text"
        database.messageNodeDao().insert(
            node(
                messages = listOf(
                    userMessage(messageId, "unselected stale branch"),
                    userMessage(messageId, selectedText),
                ),
                selectedIndex = 1,
            ),
        )

        val exact = locator(SCOPE, messageId, selectedText)
        val stale = locator(SCOPE, messageId, "unselected stale branch")
        val results = reader.read(request(SCOPE, listOf(exact, stale)))

        val found = results[0] as DreamSourceReadResult.Found
        assertEquals(selectedText, found.text)
        assertEquals(1_704_164_645_000L, found.sourceTimestampEpochMs)
        assertEquals(exact.expectedConsumedTextDigest, found.consumedTextDigest)
        assertEquals(
            DreamSourceUnavailableReason.DIGEST_MISMATCH,
            (results[1] as DreamSourceReadResult.Unavailable).reason,
        )
    }

    @Test
    fun read_failsClosedForScopeTombstoneAndBudget() = runBlocking {
        insertConversation(SCOPE)
        val messageId = "10000000-0000-0000-0000-000000000002"
        val text = "private text"
        database.messageNodeDao().insert(node(listOf(userMessage(messageId, text)), 0))
        val exact = locator(SCOPE, messageId, text)

        val wrongScopeResult = reader.read(
            request(OTHER_SCOPE, listOf(locator(OTHER_SCOPE, messageId, text))),
        ).single() as DreamSourceReadResult.Unavailable
        assertEquals(DreamSourceUnavailableReason.SCOPE_MISMATCH, wrongScopeResult.reason)

        val budgetResult = reader.read(request(SCOPE, listOf(exact), maxBytes = 1)).single()
            as DreamSourceReadResult.Unavailable
        assertEquals(DreamSourceUnavailableReason.BUDGET_EXCEEDED, budgetResult.reason)

        database.memoryV2Dao().insertSourceTombstones(
            listOf(
                MemorySourceTombstoneEntity(
                    scopeId = SCOPE,
                    conversationId = CONVERSATION_ID,
                    sourceKind = "MESSAGE",
                    sourceId = messageId,
                    sourceDigest = exact.expectedConsumedTextDigest.value,
                    reasonCode = "TEST_DELETE",
                    tombstonedAtMs = 10L,
                ),
            ),
        )
        val tombstoned = reader.read(request(SCOPE, listOf(exact))).single()
            as DreamSourceReadResult.Unavailable
        assertEquals(DreamSourceUnavailableReason.TOMBSTONED, tombstoned.reason)
    }

    @Test
    fun read_globalScopeMayReadConversationButDoesNotBypassGlobalTombstone() = runBlocking {
        insertConversation(SCOPE)
        val messageId = "10000000-0000-0000-0000-000000000003"
        val text = "shared source"
        database.messageNodeDao().insert(node(listOf(userMessage(messageId, text)), 0))
        val locator = locator(DreamScopeId.GLOBAL_VALUE, messageId, text)

        assertTrue(
            reader.read(request(DreamScopeId.GLOBAL_VALUE, listOf(locator))).single()
                is DreamSourceReadResult.Found,
        )
        database.memoryV2Dao().insertSourceTombstones(
            listOf(
                MemorySourceTombstoneEntity(
                    scopeId = DreamScopeId.GLOBAL_VALUE,
                    conversationId = CONVERSATION_ID,
                    sourceKind = "CONVERSATION",
                    sourceId = CONVERSATION_ID,
                    reasonCode = "TEST_DELETE",
                    tombstonedAtMs = 20L,
                ),
            ),
        )
        val result = reader.read(request(DreamScopeId.GLOBAL_VALUE, listOf(locator))).single()
            as DreamSourceReadResult.Unavailable
        assertEquals(DreamSourceUnavailableReason.TOMBSTONED, result.reason)
    }

    @Test
    fun read_frozenIanaZoneIsStableAcrossReaderRestartAndDstBoundary() = runBlocking {
        insertConversation(SCOPE)
        val messageId = "10000000-0000-0000-0000-000000000004"
        val text = "timezone-bound source"
        val local = LocalDateTime.parse("2024-11-03T01:30:00")
        database.messageNodeDao().insert(
            node(
                messages = listOf(
                    UIMessage(
                        id = Uuid.parse(messageId),
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(text)),
                        createdAt = local,
                    ),
                ),
                selectedIndex = 0,
            ),
        )
        val locator = locator(SCOPE, messageId, text)
        val newYorkRequest = request(
            scopeId = SCOPE,
            locators = listOf(locator),
            sourceTimezoneId = "America/New_York",
        )

        val beforeRestart = reader.read(newYorkRequest).single() as DreamSourceReadResult.Found
        val afterRestart = RoomDreamSourceReader(database).read(newYorkRequest).single()
            as DreamSourceReadResult.Found
        assertEquals(
            local.toInstant(TimeZone.of("America/New_York")).toEpochMilliseconds(),
            beforeRestart.sourceTimestampEpochMs,
        )
        assertEquals(beforeRestart.sourceTimestampEpochMs, afterRestart.sourceTimestampEpochMs)

        val shanghai = reader.read(
            request(
                scopeId = SCOPE,
                locators = listOf(locator),
                sourceTimezoneId = "Asia/Shanghai",
            ),
        ).single() as DreamSourceReadResult.Found
        assertEquals(
            local.toInstant(TimeZone.of("Asia/Shanghai")).toEpochMilliseconds(),
            shanghai.sourceTimestampEpochMs,
        )
        assertTrue(shanghai.sourceTimestampEpochMs != beforeRestart.sourceTimestampEpochMs)
    }

    private suspend fun insertConversation(assistantId: String) {
        database.conversationDao().insert(
            ConversationEntity(
                id = CONVERSATION_ID,
                assistantId = assistantId,
                title = "test",
                nodes = "[]",
                createAt = 1L,
                updateAt = 1L,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
    }

    private fun node(messages: List<UIMessage>, selectedIndex: Int) = MessageNodeEntity(
        id = NODE_ID,
        conversationId = CONVERSATION_ID,
        nodeIndex = 0,
        messages = JsonInstant.encodeToString(messages),
        selectIndex = selectedIndex,
    )

    private fun userMessage(id: String, text: String) = UIMessage(
        id = Uuid.parse(id),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = LocalDateTime.parse("2024-01-02T03:04:05"),
    )

    private fun locator(scopeId: String, messageId: String, text: String) = DreamSourceLocator(
        scopeId = DreamScopeId.requireCanonical(scopeId),
        conversationId = CONVERSATION_ID,
        messageId = messageId,
        role = MemorySourceRole.USER,
        sourceKind = MemorySourceKind.TEXT,
        expectedConsumedTextDigest = DreamSha256(memorySourceTextDigest(text)),
        evidenceGroupId = "capture-1",
    )

    private fun request(
        scopeId: String,
        locators: List<DreamSourceLocator>,
        maxBytes: Int = 64_000,
        sourceTimezoneId: String = "UTC",
    ) = DreamSourceReadRequest(
        scopeId = DreamScopeId.requireCanonical(scopeId),
        frozenNowEpochMs = 1_800_000_000_000L,
        sourceTimezoneId = sourceTimezoneId,
        locators = locators,
        maxTotalUtf8Bytes = maxBytes,
    )

    private companion object {
        const val SCOPE = "10000000-0000-0000-0000-000000000010"
        const val OTHER_SCOPE = "20000000-0000-0000-0000-000000000020"
        const val CONVERSATION_ID = "30000000-0000-0000-0000-000000000030"
        const val NODE_ID = "40000000-0000-0000-0000-000000000040"
    }
}
