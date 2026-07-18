package me.rerere.rikkahub.setup

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SetupTransactionCoordinatorTest {
    @Test
    fun `planning typed changes snapshots them without mutating configuration`() = runBlocking {
        val assistantId = Uuid.random()
        val workspaceId = Uuid.random()
        val backend = InMemorySetupBackend(
            mapOf(
                "assistant:$assistantId:enable_memory" to SetupValue.Bool(false),
                "assistant:$assistantId:workspace" to SetupValue.Id(null),
            ),
        )
        val coordinator = SetupTransactionCoordinator(
            backend = backend,
            auditLedger = SetupAuditLedger.NONE,
        )
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())

        val result = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantFlag(
                    assistantId,
                    SetupAssistantFlag.ENABLE_MEMORY,
                    enabled = true,
                ),
                SetupChange.AssistantWorkspace(assistantId, workspaceId),
            ),
        )

        assertTrue(result.ok)
        assertEquals("SETUP_PLANNED", result.code)
        assertEquals(SetupTransactionStatus.PLANNED, result.transaction?.status)
        assertEquals(
            listOf("assistant_flag", "assistant_workspace"),
            result.transaction?.steps?.map { it.type },
        )
        assertEquals(0, backend.casCalls)
        assertEquals(SetupValue.Bool(false), backend.values.getValue("assistant:$assistantId:enable_memory"))
        assertEquals(SetupValue.Id(null), backend.values.getValue("assistant:$assistantId:workspace"))
    }

    @Test
    fun `apply compare and sets every field then runs targeted doctor checks`() = runBlocking {
        val assistantId = Uuid.random()
        val workspaceId = Uuid.random()
        val backend = InMemorySetupBackend(
            mapOf(
                "assistant:$assistantId:enable_memory" to SetupValue.Bool(false),
                "assistant:$assistantId:workspace" to SetupValue.Id(null),
            ),
        )
        val ledger = RecordingSetupAuditLedger()
        val coordinator = SetupTransactionCoordinator(backend, ledger)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true),
                SetupChange.AssistantWorkspace(assistantId, workspaceId),
            ),
        )

        val applied = coordinator.apply(owner, planned.transaction!!.id)

        assertTrue(applied.ok)
        assertEquals("SETUP_APPLIED", applied.code)
        assertEquals(SetupTransactionStatus.SUCCEEDED, applied.transaction?.status)
        assertEquals(
            listOf(SetupStepStatus.VERIFIED, SetupStepStatus.VERIFIED),
            applied.transaction?.steps?.map { it.status },
        )
        assertEquals(SetupValue.Bool(true), backend.values.getValue("assistant:$assistantId:enable_memory"))
        assertEquals(SetupValue.Id(workspaceId), backend.values.getValue("assistant:$assistantId:workspace"))
        assertEquals(
            listOf("assistant_flag", "assistant_workspace"),
            ledger.opened.single().second,
        )
        assertEquals(SetupAuditStatus.SUCCEEDED, ledger.finished.single().second)
    }

    @Test
    fun `failed targeted verification rolls back applied fields in reverse`() = runBlocking {
        val assistantId = Uuid.random()
        val workspaceId = Uuid.random()
        val memoryKey = "assistant:$assistantId:enable_memory"
        val workspaceKey = "assistant:$assistantId:workspace"
        val backend = InMemorySetupBackend(
            mapOf(
                memoryKey to SetupValue.Bool(false),
                workspaceKey to SetupValue.Id(null),
            ),
        ).apply {
            doctorFailures[workspaceKey] = "WORKSPACE_UNAVAILABLE"
        }
        val ledger = RecordingSetupAuditLedger()
        val coordinator = SetupTransactionCoordinator(backend, ledger)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true),
                SetupChange.AssistantWorkspace(assistantId, workspaceId),
            ),
        )

        val failed = coordinator.apply(owner, planned.transaction!!.id)

        assertEquals(false, failed.ok)
        assertEquals("SETUP_ROLLED_BACK", failed.code)
        assertEquals(SetupTransactionStatus.ROLLED_BACK, failed.transaction?.status)
        assertEquals(
            listOf(SetupStepStatus.ROLLED_BACK, SetupStepStatus.ROLLED_BACK),
            failed.transaction?.steps?.map { it.status },
        )
        assertEquals(SetupValue.Bool(false), backend.values.getValue(memoryKey))
        assertEquals(SetupValue.Id(null), backend.values.getValue(workspaceKey))
        assertEquals(listOf(workspaceKey, memoryKey), backend.rollbackOrder)
        assertEquals(SetupAuditStatus.FAILED, ledger.finished.single().second)
        assertEquals("WORKSPACE_UNAVAILABLE", ledger.finished.single().third)
    }

    @Test
    fun `rollback preserves a field changed concurrently after setup wrote it`() = runBlocking {
        val assistantId = Uuid.random()
        val targetWorkspace = Uuid.random()
        val externalWorkspace = Uuid.random()
        val memoryKey = "assistant:$assistantId:enable_memory"
        val workspaceKey = "assistant:$assistantId:workspace"
        val backend = InMemorySetupBackend(
            mapOf(
                memoryKey to SetupValue.Bool(false),
                workspaceKey to SetupValue.Id(null),
            ),
        ).apply {
            doctorFailures[workspaceKey] = "WORKSPACE_UNAVAILABLE"
            doctorMutations[workspaceKey] = SetupValue.Id(externalWorkspace)
        }
        val coordinator = SetupTransactionCoordinator(backend, SetupAuditLedger.NONE)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true),
                SetupChange.AssistantWorkspace(assistantId, targetWorkspace),
            ),
        )

        val failed = coordinator.apply(owner, planned.transaction!!.id)

        assertEquals("SETUP_PARTIAL_ROLLBACK", failed.code)
        assertEquals(SetupTransactionStatus.PARTIAL_ROLLBACK, failed.transaction?.status)
        assertEquals(
            listOf(SetupStepStatus.ROLLED_BACK, SetupStepStatus.ROLLBACK_CONFLICT),
            failed.transaction?.steps?.map { it.status },
        )
        assertEquals(SetupValue.Bool(false), backend.values.getValue(memoryKey))
        assertEquals(SetupValue.Id(externalWorkspace), backend.values.getValue(workspaceKey))
    }

    @Test
    fun `verify is owner scoped and reports actual target state without writing`() = runBlocking {
        val assistantId = Uuid.random()
        val key = "assistant:$assistantId:enable_memory"
        val backend = InMemorySetupBackend(mapOf(key to SetupValue.Bool(false)))
        val coordinator = SetupTransactionCoordinator(backend, SetupAuditLedger.NONE)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true)),
        )
        val transactionId = planned.transaction!!.id

        val hidden = coordinator.verify(
            SetupOwner(Uuid.random().toString(), Uuid.random().toString()),
            transactionId,
        )
        val mismatch = coordinator.verify(owner, transactionId)
        backend.values[key] = SetupValue.Bool(true)
        val verified = coordinator.verify(owner, transactionId)

        assertEquals("SETUP_TRANSACTION_NOT_FOUND", hidden.code)
        assertEquals("SETUP_VERIFY_FAILED", mismatch.code)
        assertEquals(false, mismatch.checks.single().ok)
        assertEquals("SETUP_VERIFIED", verified.code)
        assertTrue(verified.checks.single().ok)
        assertEquals(0, backend.casCalls)
    }

    @Test
    fun `cancellation compensates applied fields in non cancellable cleanup`() = runBlocking {
        val assistantId = Uuid.random()
        val workspaceId = Uuid.random()
        val memoryKey = "assistant:$assistantId:enable_memory"
        val workspaceKey = "assistant:$assistantId:workspace"
        val backend = InMemorySetupBackend(
            mapOf(
                memoryKey to SetupValue.Bool(false),
                workspaceKey to SetupValue.Id(null),
            ),
        ).apply {
            cancelOnDoctorKey = workspaceKey
        }
        val ledger = RecordingSetupAuditLedger()
        val coordinator = SetupTransactionCoordinator(backend, ledger)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true),
                SetupChange.AssistantWorkspace(assistantId, workspaceId),
            ),
        )

        try {
            coordinator.apply(owner, planned.transaction!!.id)
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation still propagates after compensation.
        }
        backend.cancelOnDoctorKey = null
        val after = coordinator.verify(owner, planned.transaction!!.id)

        assertEquals(SetupTransactionStatus.ROLLED_BACK, after.transaction?.status)
        assertEquals(SetupValue.Bool(false), backend.values.getValue(memoryKey))
        assertEquals(SetupValue.Id(null), backend.values.getValue(workspaceKey))
        assertEquals(SetupAuditStatus.CANCELLED, ledger.finished.single().second)
    }

    @Test
    fun `unexpected backend failure returns a stable result after compensating applied fields`() = runBlocking {
        val assistantId = Uuid.random()
        val workspaceId = Uuid.random()
        val memoryKey = "assistant:$assistantId:enable_memory"
        val workspaceKey = "assistant:$assistantId:workspace"
        val backend = InMemorySetupBackend(
            mapOf(
                memoryKey to SetupValue.Bool(false),
                workspaceKey to SetupValue.Id(null),
            ),
        ).apply {
            throwOnDoctorKey = workspaceKey
        }
        val ledger = RecordingSetupAuditLedger()
        val coordinator = SetupTransactionCoordinator(backend, ledger)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true),
                SetupChange.AssistantWorkspace(assistantId, workspaceId),
            ),
        )

        val failed = coordinator.apply(owner, planned.transaction!!.id)

        assertEquals(false, failed.ok)
        assertEquals("SETUP_ROLLED_BACK", failed.code)
        assertEquals(SetupTransactionStatus.ROLLED_BACK, failed.transaction?.status)
        assertEquals("SETUP_INTERNAL_ERROR", failed.transaction?.lastErrorCode)
        assertEquals(SetupValue.Bool(false), backend.values.getValue(memoryKey))
        assertEquals(SetupValue.Id(null), backend.values.getValue(workspaceKey))
        assertEquals(SetupAuditStatus.FAILED, ledger.finished.single().second)
        assertEquals("SETUP_INTERNAL_ERROR", ledger.finished.single().third)
    }

    private class InMemorySetupBackend(
        initial: Map<String, SetupValue>,
    ) : SetupTransactionBackend {
        val values = initial.toMutableMap()
        var casCalls = 0
        val doctorFailures = mutableMapOf<String, String>()
        val doctorMutations = mutableMapOf<String, SetupValue>()
        val rollbackOrder = mutableListOf<String>()
        var cancelOnDoctorKey: String? = null
        var throwOnDoctorKey: String? = null

        override suspend fun prepare(change: SetupChange): SetupPrepareResult {
            val before = values[change.key]
                ?: return SetupPrepareResult.Rejected("FIELD_NOT_FOUND", change.key)
            return SetupPrepareResult.Prepared(
                SetupPreparedChange(
                    change = change,
                    key = change.key,
                    type = change.type,
                    summary = change.key,
                    before = before,
                    after = when (change) {
                        is SetupChange.AssistantFlag -> SetupValue.Bool(change.enabled)
                        is SetupChange.AssistantWorkspace -> SetupValue.Id(change.workspaceId)
                        else -> error("test adapter does not support ${change.type}")
                    },
                ),
            )
        }

        override suspend fun compareAndSet(
            change: SetupPreparedChange,
            expected: SetupValue,
            update: SetupValue,
        ): SetupCasResult {
            casCalls++
            if (values[change.key] != expected) return SetupCasResult.Conflict
            if (update == change.before && expected == change.after) {
                rollbackOrder += change.key
            }
            values[change.key] = update
            return SetupCasResult.Applied
        }

        override suspend fun doctor(change: SetupPreparedChange): SetupDoctorCheck {
            if (change.key == cancelOnDoctorKey) throw CancellationException("cancel setup")
            if (change.key == throwOnDoctorKey) error("backend exploded with sensitive detail")
            doctorMutations[change.key]?.let { values[change.key] = it }
            return SetupDoctorCheck(
                key = change.key,
                ok = values[change.key] == change.after && change.key !in doctorFailures,
                code = doctorFailures[change.key]
                    ?: if (values[change.key] == change.after) "OK" else "VALUE_MISMATCH",
                detail = change.key,
            )
        }
    }

    private class RecordingSetupAuditLedger : SetupAuditLedger {
        val opened = mutableListOf<Pair<String, List<String>>>()
        val finished = mutableListOf<Triple<String?, SetupAuditStatus, String?>>()

        override suspend fun open(transactionId: String, changeTypes: List<String>): String {
            opened += transactionId to changeTypes
            return "run-$transactionId"
        }

        override suspend fun finish(runId: String?, status: SetupAuditStatus, errorCode: String?) {
            finished += Triple(runId, status, errorCode)
        }
    }
}
