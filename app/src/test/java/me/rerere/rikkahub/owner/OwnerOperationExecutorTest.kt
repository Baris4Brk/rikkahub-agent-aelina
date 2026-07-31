package me.rerere.rikkahub.owner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.owner.db.HostOperationDao
import me.rerere.rikkahub.owner.db.HostOperationEntity
import me.rerere.rikkahub.owner.db.HostOperationEventEntity
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OwnerOperationExecutorTest {
    private val assistantId = Uuid.parse("51000000-0000-0000-0000-000000000001")
    private val conversationId = Uuid.parse("52000000-0000-0000-0000-000000000002")
    private val authority = SecondUserAdmissionSnapshot.create(
        assistantId, conversationId, 42, ToolCallOrigin.LocalChat,
    )

    @After
    fun clear() = SecondUserAuthorityRegistry.install(null)

    @Test
    fun `request id is globally idempotent and does not replay apply`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        val dao = FakeHostOperationDao()
        var applies = 0
        val handler = SuccessfulHandler { applies++ }
        val executor = OwnerOperationExecutor(dao, handler, isEmergencyStopActive = { false }, nowMs = { 100 })
        val request = request("request-idempotent-001", 1)

        val first = executor.execute(request, context())
        val second = executor.execute(request, context())

        assertTrue(first.ok)
        assertEquals(OwnerOperationState.COMMITTED, first.state)
        assertTrue(second.replayed)
        assertEquals(1, applies)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), dao.events(request.requestId).map { it.sequence })
    }

    @Test
    fun `request id reuse with different arguments is rejected without replay`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        val dao = FakeHostOperationDao()
        var applies = 0
        val executor = OwnerOperationExecutor(dao, SuccessfulHandler { applies++ }, { false })
        val first = request("request-argument-conflict", 1)
        val different = first.copy(
            actions = listOf(
                first.actions.single().copy(
                    arguments = JsonObject(
                        mapOf("target" to kotlinx.serialization.json.JsonPrimitive("different")),
                    ),
                ),
            ),
        )

        assertTrue(executor.execute(first, context()).ok)
        val replay = executor.execute(different, context())

        assertFalse(replay.ok)
        assertEquals("OWNER_REQUEST_ID_CONFLICT", replay.code)
        assertEquals(1, applies)
    }

    @Test
    fun `failed later action compensates earlier reversible action`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        val dao = FakeHostOperationDao()
        var compensated = 0
        val handler = object : SuccessfulHandler() {
            override suspend fun apply(
                index: Int,
                request: OwnerOperationRequest,
                action: OwnerAction,
                context: PrivilegedSessionContext,
            ): OwnerAppliedAction = if (index == 1) {
                OwnerAppliedAction(OwnerActionResult(index, action.type, false, "SECOND_FAILED", "failed"))
            } else super.apply(index, request, action, context)

            override suspend fun compensate(
                request: OwnerOperationRequest,
                action: OwnerAction,
                applied: OwnerAppliedAction,
                context: PrivilegedSessionContext,
            ): OwnerCompensationResult {
                compensated++
                return OwnerCompensationResult(true, "RESTORED")
            }
        }
        val result = OwnerOperationExecutor(dao, handler, { false }).execute(
            request("request-rollback-0001", 2), context(),
        )

        assertFalse(result.ok)
        assertEquals(OwnerOperationState.ROLLED_BACK, result.state)
        assertEquals(1, compensated)
    }

    @Test
    fun `stale epoch is rejected before ledger or handler mutation`() = runBlocking {
        SecondUserAuthorityRegistry.install(
            SecondUserAdmissionSnapshot.create(
                assistantId, conversationId, authority.authorityEpoch + 1, ToolCallOrigin.LocalChat,
            ),
        )
        val dao = FakeHostOperationDao()
        var applies = 0
        val result = OwnerOperationExecutor(dao, SuccessfulHandler { applies++ }, { false })
            .execute(request("request-stale-epoch", 1), context())

        assertFalse(result.ok)
        assertEquals("SECOND_USER_AUTHORITY_STALE", result.code)
        assertEquals(0, applies)
        assertEquals(0, dao.size)
    }

    @Test
    fun `known secret is rejected before ordinary owner arguments reach the ledger`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        val dao = FakeHostOperationDao()
        var applies = 0
        val executor = OwnerOperationExecutor(
            dao = dao,
            handler = SuccessfulHandler { applies++ },
            isEmergencyStopActive = { false },
            containsRuntimeSecret = { it.contains("known-secret") },
        )
        val unsafe = request("request-secret-egress", 1).copy(
            actions = listOf(
                OwnerAction(
                    "provider_create",
                    JsonObject(mapOf("base_url" to kotlinx.serialization.json.JsonPrimitive("https://example.test/known-secret"))),
                    OwnerOperationRisk.REVERSIBLE_WRITE,
                ),
            ),
            family = OwnerToolFamily.PROVIDER,
        )

        val result = executor.execute(unsafe, context())

        assertFalse(result.ok)
        assertEquals("SECRET_EGRESS_DENIED", result.code)
        assertEquals(0, dao.size)
        assertEquals(0, applies)
    }

    @Test
    fun `later action validates against state created earlier in the same call`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        var created = false
        val handler = object : SuccessfulHandler() {
            override suspend fun validate(
                request: OwnerOperationRequest,
                action: OwnerAction,
                context: PrivilegedSessionContext,
            ): OwnerActionValidation = if (action.type == "use_created" && !created) {
                OwnerActionValidation(false, "NOT_CREATED", "resource missing")
            } else OwnerActionValidation(true, "VALID", "valid")

            override suspend fun apply(
                index: Int,
                request: OwnerOperationRequest,
                action: OwnerAction,
                context: PrivilegedSessionContext,
            ): OwnerAppliedAction {
                if (action.type == "create") created = true
                return super.apply(index, request, action, context)
            }
        }
        val chained = request("request-chained-actions", 2).copy(
            actions = listOf(
                OwnerAction("create", JsonObject(emptyMap()), OwnerOperationRisk.REVERSIBLE_WRITE),
                OwnerAction("use_created", JsonObject(emptyMap()), OwnerOperationRisk.REVERSIBLE_WRITE),
            ),
        )

        val result = OwnerOperationExecutor(FakeHostOperationDao(), handler, { false })
            .execute(chained, context())

        assertTrue(result.ok)
        assertTrue(created)
        assertEquals(2, result.actions.size)
    }

    @Test
    fun `process only compensation receipts are closed after commit`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        var closed = false
        val handler = object : SuccessfulHandler() {
            override suspend fun apply(
                index: Int,
                request: OwnerOperationRequest,
                action: OwnerAction,
                context: PrivilegedSessionContext,
            ) = OwnerAppliedAction(
                OwnerActionResult(index, action.type, true, "APPLIED", "applied"),
                AutoCloseable { closed = true },
            )
        }

        val result = OwnerOperationExecutor(FakeHostOperationDao(), handler, { false })
            .execute(request("request-close-receipt", 1), context())

        assertTrue(result.ok)
        assertTrue(closed)
    }

    @Test
    fun `emergency stop raised during apply compensates before commit`() = runBlocking {
        SecondUserAuthorityRegistry.install(authority)
        var emergency = false
        var compensated = false
        val handler = object : SuccessfulHandler() {
            override suspend fun apply(
                index: Int,
                request: OwnerOperationRequest,
                action: OwnerAction,
                context: PrivilegedSessionContext,
            ): OwnerAppliedAction {
                val result = super.apply(index, request, action, context)
                emergency = true
                return result
            }

            override suspend fun compensate(
                request: OwnerOperationRequest,
                action: OwnerAction,
                applied: OwnerAppliedAction,
                context: PrivilegedSessionContext,
            ): OwnerCompensationResult {
                compensated = true
                return OwnerCompensationResult(true, "RESTORED")
            }
        }

        val result = OwnerOperationExecutor(
            dao = FakeHostOperationDao(),
            handler = handler,
            isEmergencyStopActive = { emergency },
        ).execute(request("request-emergency-stop", 1), context())

        assertFalse(result.ok)
        assertEquals(OwnerOperationState.ROLLED_BACK, result.state)
        assertTrue(compensated)
    }

    private fun request(id: String, count: Int) = OwnerOperationRequest(
        requestId = id,
        family = OwnerToolFamily.UI,
        actions = (0 until count).map {
            OwnerAction("action_$it", JsonObject(emptyMap()), OwnerOperationRisk.REVERSIBLE_WRITE)
        },
        authoritySubjectId = authority.subjectId,
        authorityEpoch = authority.authorityEpoch,
        assistantId = assistantId.toString(),
        conversationId = conversationId.toString(),
        modelId = "model",
        providerId = "provider",
    )

    private fun context() = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = conversationId,
        identityName = "owner",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = false,
        authoritySubjectId = authority.subjectId,
        authorityEpoch = authority.authorityEpoch,
    )
}

private open class SuccessfulHandler(
    private val onApply: () -> Unit = {},
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction) = true
    override suspend fun validate(request: OwnerOperationRequest, action: OwnerAction, context: PrivilegedSessionContext) =
        OwnerActionValidation(true, "VALID", "valid")
    override suspend fun apply(index: Int, request: OwnerOperationRequest, action: OwnerAction, context: PrivilegedSessionContext): OwnerAppliedAction {
        onApply()
        return OwnerAppliedAction(OwnerActionResult(index, action.type, true, "APPLIED", "applied"), Any())
    }
    override suspend fun verify(request: OwnerOperationRequest, action: OwnerAction, applied: OwnerAppliedAction, context: PrivilegedSessionContext) =
        OwnerActionValidation(true, "VERIFIED", "verified")
    override suspend fun compensate(request: OwnerOperationRequest, action: OwnerAction, applied: OwnerAppliedAction, context: PrivilegedSessionContext) =
        OwnerCompensationResult(true, "RESTORED")
}

private class FakeHostOperationDao : HostOperationDao() {
    private val rows = linkedMapOf<String, HostOperationEntity>()
    private val history = mutableListOf<HostOperationEventEntity>()
    val size: Int get() = rows.size

    override suspend fun insertOperation(record: HostOperationEntity): Long {
        if (rows.containsKey(record.requestId)) return -1
        rows[record.requestId] = record
        return 1
    }
    override suspend fun insertEvent(event: HostOperationEventEntity) { history += event }
    override suspend fun get(requestId: String) = rows[requestId]
    override fun observeRecent(limit: Int): Flow<List<HostOperationEntity>> =
        flowOf(rows.values.toList().takeLast(limit).reversed())
    override suspend fun getRecoverable() = rows.values.filter { it.state in setOf("VALIDATING", "APPLYING", "VERIFYING", "COMPENSATING") }
    override suspend fun events(requestId: String) = history.filter { it.requestId == requestId }.sortedBy { it.sequence }
    override suspend fun compareAndSetState(
        requestId: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        recoveryCode: String?,
        resultCode: String?,
        updatedAtMs: Long,
        completedAtMs: Long?,
    ): Int {
        val old = rows[requestId] ?: return 0
        if (old.state != expectedState || old.stateVersion != expectedVersion) return 0
        rows[requestId] = old.copy(
            state = nextState,
            stateVersion = old.stateVersion + 1,
            recoveryCode = recoveryCode,
            resultCode = resultCode,
            updatedAtMs = updatedAtMs,
            completedAtMs = completedAtMs,
        )
        return 1
    }
    override suspend fun deleteByIds(requestIds: List<String>): Int = requestIds.count { rows.remove(it) != null }
    override suspend fun terminalIdsBeyond(keep: Int): List<String> = rows.values
        .filter { it.state in setOf("COMMITTED", "ROLLED_BACK", "PARTIAL", "NEEDS_ATTENTION", "FAILED") }
        .sortedByDescending { it.updatedAtMs }
        .drop(keep)
        .map { it.requestId }
}
