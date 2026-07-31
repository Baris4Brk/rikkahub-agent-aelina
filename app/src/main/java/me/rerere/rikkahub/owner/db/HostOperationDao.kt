package me.rerere.rikkahub.owner.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HostOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOperation(record: HostOperationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEvent(event: HostOperationEventEntity)

    @Transaction
    open suspend fun insertOperationWithInitialEvent(
        record: HostOperationEntity,
        event: HostOperationEventEntity,
    ): Boolean {
        if (insertOperation(record) == -1L) return false
        insertEvent(event)
        return true
    }

    @Query("SELECT * FROM host_operations WHERE request_id = :requestId LIMIT 1")
    abstract suspend fun get(requestId: String): HostOperationEntity?

    @Query("SELECT * FROM host_operations ORDER BY updated_at_ms DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int = 100): Flow<List<HostOperationEntity>>

    @Query(
        "SELECT * FROM host_operations WHERE state IN " +
            "('VALIDATING','APPLYING','VERIFYING','COMPENSATING') ORDER BY updated_at_ms ASC",
    )
    abstract suspend fun getRecoverable(): List<HostOperationEntity>

    @Query("SELECT * FROM host_operation_events WHERE request_id = :requestId ORDER BY sequence ASC")
    abstract suspend fun events(requestId: String): List<HostOperationEventEntity>

    @Query(
        "UPDATE host_operations SET state = :nextState, state_version = state_version + 1, " +
            "recovery_code = :recoveryCode, result_code = :resultCode, updated_at_ms = :updatedAtMs, " +
            "completed_at_ms = :completedAtMs WHERE request_id = :requestId " +
            "AND state_version = :expectedVersion AND state = :expectedState",
    )
    abstract suspend fun compareAndSetState(
        requestId: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        recoveryCode: String?,
        resultCode: String?,
        updatedAtMs: Long,
        completedAtMs: Long?,
    ): Int

    @Transaction
    open suspend fun transition(
        requestId: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        recoveryCode: String?,
        resultCode: String?,
        actionIndex: Int?,
        actionType: String?,
        reasonCode: String?,
        eventId: String,
        createdAtMs: Long,
        completedAtMs: Long? = null,
    ): Boolean {
        val changed = compareAndSetState(
            requestId = requestId,
            expectedState = expectedState,
            expectedVersion = expectedVersion,
            nextState = nextState,
            recoveryCode = recoveryCode,
            resultCode = resultCode,
            updatedAtMs = createdAtMs,
            completedAtMs = completedAtMs,
        )
        if (changed != 1) return false
        insertEvent(
            HostOperationEventEntity(
                eventId = eventId,
                requestId = requestId,
                sequence = expectedVersion + 1,
                previousState = expectedState,
                nextState = nextState,
                actionIndex = actionIndex,
                actionType = actionType,
                reasonCode = reasonCode,
                createdAtMs = createdAtMs,
            ),
        )
        return true
    }

    @Query("DELETE FROM host_operations WHERE request_id IN (:requestIds)")
    abstract suspend fun deleteByIds(requestIds: List<String>): Int

    @Query(
        "SELECT request_id FROM host_operations WHERE state IN " +
            "('COMMITTED','ROLLED_BACK','PARTIAL','NEEDS_ATTENTION','FAILED') " +
            "ORDER BY updated_at_ms DESC LIMIT -1 OFFSET :keep",
    )
    abstract suspend fun terminalIdsBeyond(keep: Int): List<String>

    @Transaction
    open suspend fun trimTerminal(keep: Int = 500): Int {
        val ids = terminalIdsBeyond(keep.coerceAtLeast(0))
        return if (ids.isEmpty()) 0 else deleteByIds(ids)
    }
}

@Dao
interface HostLocalServiceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: HostLocalServiceEntity)

    @Query("SELECT * FROM host_local_services WHERE service_id = :serviceId LIMIT 1")
    suspend fun get(serviceId: String): HostLocalServiceEntity?

    @Query("SELECT * FROM host_local_services ORDER BY updated_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<HostLocalServiceEntity>

    @Query("SELECT * FROM host_local_services WHERE enabled = 1 ORDER BY updated_at_ms DESC")
    fun observeEnabled(): Flow<List<HostLocalServiceEntity>>

    @Query("SELECT * FROM host_local_services WHERE enabled = 1 ORDER BY updated_at_ms ASC")
    suspend fun getEnabled(): List<HostLocalServiceEntity>

    @Query(
        "UPDATE host_local_services SET execution_id = :executionId, health_state = :healthState, " +
            "restart_count = :restartCount, next_probe_at_ms = :nextProbeAtMs, " +
            "last_probe_at_ms = :lastProbeAtMs, last_reason_code = :reasonCode, enabled = :enabled, " +
            "state_version = state_version + 1, updated_at_ms = :updatedAtMs " +
            "WHERE service_id = :serviceId AND state_version = :expectedVersion",
    )
    suspend fun compareAndSetRuntime(
        serviceId: String,
        expectedVersion: Long,
        executionId: String?,
        healthState: String,
        restartCount: Int,
        nextProbeAtMs: Long?,
        lastProbeAtMs: Long?,
        reasonCode: String?,
        enabled: Boolean,
        updatedAtMs: Long,
    ): Int

    @Query("DELETE FROM host_local_services WHERE service_id = :serviceId")
    suspend fun delete(serviceId: String): Int
}
