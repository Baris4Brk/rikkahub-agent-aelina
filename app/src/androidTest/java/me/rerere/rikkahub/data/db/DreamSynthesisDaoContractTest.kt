package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.data.db.dao.DreamSynthesisDao
import me.rerere.rikkahub.data.db.entity.DreamClaimEntity
import me.rerere.rikkahub.data.db.entity.DreamClaimVersionEntity
import me.rerere.rikkahub.data.db.entity.DreamClaimVersionSourceEntity
import me.rerere.rikkahub.data.db.entity.DreamRunEntity
import me.rerere.rikkahub.data.db.entity.DreamSnapshotEntity
import me.rerere.rikkahub.data.db.entity.MemoryScopeChangeEntity
import me.rerere.rikkahub.data.db.entity.MemoryScopeStateEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class DreamSynthesisDaoContractTest {
    private lateinit var db: AppDatabase
    private lateinit var observerDao: DreamDao
    private lateinit var synthesisDao: DreamSynthesisDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        observerDao = db.dreamDao()
        synthesisDao = db.dreamSynthesisDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun snapshotCommit_isOwnerFencedDoubleCas_andAdvancesSynthesisPruneWatermark() = runBlocking {
        seedAuthorityAndRunningRun()
        insertDerivedGraph()

        assertEquals(0L, observerDao.getSafeChangePruneWatermark(SCOPE))
        assertEquals(0, observerDao.pruneChangesThrough(SCOPE, 1))
        assertEquals(
            0,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = "wrong-owner",
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 0,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "SNAPSHOT_COMMITTED",
                nowMs = 30,
            ),
        )
        assertEquals(
            0,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = OWNER,
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 1,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "STALE_SYNTHESIS_WATERMARK",
                nowMs = 30,
            ),
        )
        assertEquals(
            1,
            synthesisDao.recordRunSynthesisAudit(
                runId = RUN,
                scopeId = SCOPE,
                leaseOwner = OWNER,
                modelIdentityDigest = HASH_A,
                providerKind = "REMOTE",
                promptContractVersion = "dream-proposal-v1",
                validatorVersion = "dream-validator-v1",
                inputMemoryCount = 1,
                inputTokens = 100,
                outputClaimCount = 1,
                outputTokens = 50,
                inputManifestHash = HASH_B,
                outputManifestHash = HASH_C,
                nowMs = 30,
            ),
        )
        assertEquals(
            1,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = OWNER,
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 0,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "SNAPSHOT_COMMITTED",
                nowMs = 30,
            ),
        )
        assertEquals(
            0,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = OWNER,
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 0,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "STALE_RETRY",
                nowMs = 31,
            ),
        )

        val state = checkNotNull(observerDao.getScopeState(SCOPE))
        assertEquals(1L, state.dreamStateRevision)
        assertEquals(2L, state.lastAppliedMemoryEpoch)
        assertEquals(SNAPSHOT, state.activeSnapshotId)
        assertEquals(SNAPSHOT, synthesisDao.getCurrentSnapshot(SCOPE)?.snapshotId)
        assertEquals(HASH_A, observerDao.getRun(RUN, SCOPE)?.modelIdentityDigest)

        assertEquals(2L, observerDao.getSafeChangePruneWatermark(SCOPE))
        assertEquals(1, observerDao.pruneChangesThrough(SCOPE, 1))
        assertEquals(1, observerDao.countChangesThrough(SCOPE, 2))
    }

    @Test
    fun snapshotCommit_neverMovesAppliedMemoryWatermarkBackwards() = runBlocking {
        seedAuthorityAndRunningRun()
        insertDerivedGraph()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE memory_scope_state SET last_applied_memory_epoch = 3 " +
                "WHERE scope_id = '$SCOPE'",
        )

        assertEquals(
            0,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = OWNER,
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 3,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "SNAPSHOT_COMMITTED",
                nowMs = 30,
            ),
        )
        val state = checkNotNull(observerDao.getScopeState(SCOPE))
        assertEquals(3L, state.lastAppliedMemoryEpoch)
        assertEquals(0L, state.dreamStateRevision)
        assertNull(state.activeSnapshotId)
    }

    @Test
    fun runAudit_preservesUnmeasuredTokenCountsAsNull() = runBlocking {
        seedAuthorityAndRunningRun()

        assertEquals(
            1,
            synthesisDao.recordRunSynthesisAudit(
                runId = RUN,
                scopeId = SCOPE,
                leaseOwner = OWNER,
                modelIdentityDigest = HASH_A,
                providerKind = "ON_DEVICE",
                promptContractVersion = "dream-proposal-v1",
                validatorVersion = "dream-validator-v1",
                inputMemoryCount = 2,
                inputTokens = null,
                outputClaimCount = 1,
                outputTokens = null,
                inputManifestHash = HASH_B,
                outputManifestHash = HASH_C,
                nowMs = 30,
            ),
        )
        val run = checkNotNull(observerDao.getRun(RUN, SCOPE))
        assertNull(run.inputTokens)
        assertNull(run.outputTokens)
        assertEquals(2, run.inputMemoryCount)
        assertEquals(1, run.outputClaimCount)
    }

    @Test
    fun unchangedActiveClaim_revalidationAdvancesOnlyItsGuardedEpoch() = runBlocking {
        seedAuthorityAndRunningRun()
        insertDerivedGraph()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE dream_claims SET last_validated_memory_epoch = 1 " +
                "WHERE claim_id = '$CLAIM' AND scope_id = '$SCOPE'",
        )

        assertEquals(
            0,
            synthesisDao.touchClaimValidationEpochCas(
                scopeId = OTHER_SCOPE,
                claimId = CLAIM,
                expectedRevision = 2,
                targetEpoch = 2,
                nowMs = 10,
            ),
        )
        assertEquals(
            0,
            synthesisDao.touchClaimValidationEpochCas(
                scopeId = SCOPE,
                claimId = CLAIM,
                expectedRevision = 1,
                targetEpoch = 2,
                nowMs = 10,
            ),
        )
        assertEquals(
            0,
            synthesisDao.touchClaimValidationEpochCas(
                scopeId = SCOPE,
                claimId = CLAIM,
                expectedRevision = 2,
                targetEpoch = 1,
                nowMs = 10,
            ),
        )
        assertEquals(
            1,
            synthesisDao.touchClaimValidationEpochCas(
                scopeId = SCOPE,
                claimId = CLAIM,
                expectedRevision = 2,
                targetEpoch = 2,
                nowMs = 10,
            ),
        )
        val revalidated = checkNotNull(synthesisDao.getClaim(CLAIM, SCOPE))
        assertEquals(2L, revalidated.claimRevision)
        assertEquals(2L, revalidated.lastValidatedMemoryEpoch)
        assertEquals(20L, revalidated.updatedAtMs)
        assertEquals(
            0,
            synthesisDao.touchClaimValidationEpochCas(
                scopeId = SCOPE,
                claimId = CLAIM,
                expectedRevision = 2,
                targetEpoch = 2,
                nowMs = 30,
            ),
        )

        db.openHelper.writableDatabase.execSQL(
            "UPDATE dream_claims SET state = 'REJECTED' " +
                "WHERE claim_id = '$CLAIM' AND scope_id = '$SCOPE'",
        )
        assertEquals(
            0,
            synthesisDao.touchClaimValidationEpochCas(
                scopeId = SCOPE,
                claimId = CLAIM,
                expectedRevision = 2,
                targetEpoch = 3,
                nowMs = 30,
            ),
        )
    }

    @Test
    fun privacyRevisionCas_advancesWithoutActiveSnapshot_andRejectsStaleRevision() = runBlocking {
        seedAuthorityAndRunningRun()

        assertEquals(
            1,
            synthesisDao.advancePrivacyRevisionCas(
                scopeId = SCOPE,
                expectedMemoryEpoch = 2,
                expectedDreamRevision = 0,
                expectedActiveSnapshotId = null,
                clearActiveSnapshot = false,
                reasonCode = "PRIVACY_ERASED",
                nowMs = 30,
            ),
        )
        assertEquals(
            0,
            synthesisDao.advancePrivacyRevisionCas(
                scopeId = SCOPE,
                expectedMemoryEpoch = 2,
                expectedDreamRevision = 0,
                expectedActiveSnapshotId = null,
                clearActiveSnapshot = false,
                reasonCode = "STALE_PRIVACY_RETRY",
                nowMs = 31,
            ),
        )
        val state = checkNotNull(observerDao.getScopeState(SCOPE))
        assertEquals(1L, state.dreamStateRevision)
        assertNull(state.activeSnapshotId)
    }

    @Test
    fun privacyRevisionCas_preservesUnaffectedActiveSnapshot() = runBlocking {
        seedAuthorityAndRunningRun()
        insertDerivedGraph()
        assertEquals(
            1,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = OWNER,
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 0,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "SNAPSHOT_COMMITTED",
                nowMs = 30,
            ),
        )

        assertEquals(
            1,
            synthesisDao.advancePrivacyRevisionCas(
                scopeId = SCOPE,
                expectedMemoryEpoch = 2,
                expectedDreamRevision = 1,
                expectedActiveSnapshotId = SNAPSHOT,
                clearActiveSnapshot = false,
                reasonCode = "PRIVACY_ERASED",
                nowMs = 40,
            ),
        )
        val state = checkNotNull(observerDao.getScopeState(SCOPE))
        assertEquals(2L, state.dreamStateRevision)
        assertEquals(SNAPSHOT, state.activeSnapshotId)
    }

    @Test
    fun privacyScrub_isChildFirstAndLeavesNoDerivedTextBeforeAuthorityDelete() = runBlocking {
        seedAuthorityAndRunningRun()
        insertDerivedGraph()
        insertOtherScopeDerivedGraph()
        assertEquals(
            1,
            synthesisDao.commitActiveSnapshotCas(
                scopeId = SCOPE,
                runId = RUN,
                leaseOwner = OWNER,
                baseMemoryEpoch = 2,
                baseDreamRevision = 0,
                expectedLastAppliedMemoryEpoch = 0,
                expectedActiveSnapshotId = null,
                newSnapshotId = SNAPSHOT,
                fullRebuildAtMs = null,
                reasonCode = "SNAPSHOT_COMMITTED",
                nowMs = 30,
            ),
        )

        assertEquals(1, synthesisDao.countMemoryRevisionPins(MEMORY_ID, 1))
        assertEquals(1, synthesisDao.countMemoryRevisionPins(EXTRA_MEMORY_ID, 1))
        assertEquals(2, synthesisDao.listClaimVersions(CLAIM, SCOPE).size)
        assertTrue(synthesisDao.listClaimVersions(CLAIM, OTHER_SCOPE).isEmpty())
        assertEquals(0, synthesisDao.deleteSourcesForClaim(CLAIM, OTHER_SCOPE))
        assertEquals(1, synthesisDao.countMemoryRevisionPins(MEMORY_ID, 1))
        assertTrue(deleteMemoryRevision().isFailure)
        assertTrue(deleteMemory().isFailure)
        assertEquals(
            listOf(CLAIM),
            synthesisDao.findClaimVersionsByMemory(MEMORY_ID, 1).map { it.claimId },
        )

        assertEquals(
            1,
            synthesisDao.tombstoneClaimAndScrub(
                CLAIM,
                SCOPE,
                "PRIVACY_ERASED",
                40,
            ),
        )
        var versionsCleared = 0
        synthesisDao.listClaimVersions(CLAIM, SCOPE).forEach { version ->
            versionsCleared += synthesisDao.scrubClaimVersion(
                version.claimId,
                version.claimRevision,
            )
        }
        assertEquals(2, versionsCleared)
        assertEquals(1, synthesisDao.tombstoneSnapshotAndScrub(SNAPSHOT, SCOPE))
        assertEquals(
            1,
            synthesisDao.advancePrivacyRevisionCas(
                scopeId = SCOPE,
                expectedMemoryEpoch = 2,
                expectedDreamRevision = 1,
                expectedActiveSnapshotId = SNAPSHOT,
                clearActiveSnapshot = true,
                reasonCode = "PRIVACY_ERASED",
                nowMs = 40,
            ),
        )
        assertEquals(2, synthesisDao.deleteSourcesForClaim(CLAIM, SCOPE))

        val claim = checkNotNull(synthesisDao.getClaim(CLAIM, SCOPE))
        assertEquals("TOMBSTONED", claim.state)
        assertEquals("", claim.title)
        assertEquals("", claim.statement)
        assertTrue(
            synthesisDao.listClaimVersions(CLAIM, SCOPE).all {
                it.canonicalClaimJson.isEmpty()
            },
        )
        val snapshot = checkNotNull(synthesisDao.getSnapshot(SNAPSHOT, SCOPE))
        assertEquals("TOMBSTONED", snapshot.status)
        assertEquals("", snapshot.canonicalPayloadJson)
        assertNull(observerDao.getScopeState(SCOPE)?.activeSnapshotId)
        assertEquals(2L, observerDao.getScopeState(SCOPE)?.dreamStateRevision)
        assertNull(synthesisDao.getCurrentSnapshot(SCOPE))
        assertEquals(0, synthesisDao.countMemoryRevisionPins(MEMORY_ID, 1))
        assertEquals(0, synthesisDao.countMemoryRevisionPins(EXTRA_MEMORY_ID, 1))
        assertEquals(1, synthesisDao.countMemoryRevisionPins(OTHER_MEMORY_ID, 1))
        assertEquals(
            "{\"statement\":\"other scope text\"}",
            synthesisDao.getClaimVersion(OTHER_CLAIM, 1)?.canonicalClaimJson,
        )

        assertTrue(deleteMemoryRevision().isSuccess)
        assertTrue(deleteMemory().isSuccess)
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

    private suspend fun seedAuthorityAndRunningRun() {
        observerDao.insertScopeStateIfAbsent(
            MemoryScopeStateEntity(
                scopeId = SCOPE,
                memoryEpoch = 2,
                observerCheckpointEpoch = 2,
                updatedAtMs = 10,
            ),
        )
        observerDao.insertChanges(
            listOf(
                change(1, "memory-1"),
                change(2, "memory-2"),
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content, revision, content_hash) " +
                "VALUES($MEMORY_ID, '$SCOPE', '$PRIVATE_TEXT', 1, '$HASH_A')",
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_revisions(id, memory_id, revision, operation, actor, " +
                "created_at_ms) VALUES('memory-revision-1', $MEMORY_ID, 1, " +
                "'CREATE', 'SYSTEM', 9)",
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content, revision, content_hash) " +
                "VALUES($EXTRA_MEMORY_ID, '$SCOPE', 'extra authority', 1, '$HASH_C')",
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_revisions(id, memory_id, revision, operation, actor, " +
                "created_at_ms) VALUES('memory-revision-3', $EXTRA_MEMORY_ID, 1, " +
                "'CREATE', 'SYSTEM', 9)",
        )
        observerDao.insertRun(
            DreamRunEntity(
                runId = RUN,
                scopeId = SCOPE,
                mode = "INCREMENTAL",
                baseMemoryEpoch = 0,
                baseObserverCheckpointEpoch = 0,
                createdAtMs = 10,
                updatedAtMs = 10,
            ),
        )
        assertEquals(1, observerDao.acquireScopeLease(SCOPE, RUN, 100, 20, "RUN_CLAIMED"))
        assertEquals(
            1,
            observerDao.startRunMirror(RUN, SCOPE, 2, 2, 0, OWNER, 100, 20),
        )
    }

    private suspend fun insertDerivedGraph() {
        synthesisDao.insertClaim(
            DreamClaimEntity(
                claimId = CLAIM,
                scopeId = SCOPE,
                claimRevision = 2,
                claimKey = "project/a",
                storageClass = "EPISODIC",
                epistemicType = "PROJECT_STATE",
                title = PRIVATE_TEXT,
                statement = PRIVATE_TEXT,
                state = "ACTIVE_CONTEXTUAL",
                confidence = 0.9,
                temporalState = "CURRENT",
                learnedAtMs = 10,
                sourceTimezone = "Asia/Shanghai",
                claimHash = HASH_B,
                createdByRunId = RUN,
                lastValidatedMemoryEpoch = 2,
                createdAtMs = 20,
                updatedAtMs = 20,
            ),
        )
        synthesisDao.insertClaimVersion(
            DreamClaimVersionEntity(
                claimId = CLAIM,
                claimRevision = 1,
                canonicalClaimJson = "{\"statement\":\"$PRIVATE_TEXT\"}",
                contentHash = HASH_B,
                sourceManifestHash = HASH_C,
                reasonCode = "CREATED",
                createdByRunId = RUN,
                createdAtMs = 20,
            ),
        )
        synthesisDao.insertClaimVersion(
            DreamClaimVersionEntity(
                claimId = CLAIM,
                claimRevision = 2,
                canonicalClaimJson = "{\"statement\":\"$PRIVATE_TEXT\"}",
                contentHash = HASH_B,
                sourceManifestHash = HASH_C,
                reasonCode = "UPDATED",
                createdByRunId = RUN,
                createdAtMs = 21,
            ),
        )
        synthesisDao.insertClaimVersionSources(
            listOf(
                DreamClaimVersionSourceEntity(
                    claimId = CLAIM,
                    claimRevision = 1,
                    memoryId = MEMORY_ID,
                    memoryRevision = 1,
                    memorySemanticHash = HASH_A,
                    supportType = "SUPPORTS",
                    createdAtMs = 20,
                ),
                DreamClaimVersionSourceEntity(
                    claimId = CLAIM,
                    claimRevision = 2,
                    memoryId = EXTRA_MEMORY_ID,
                    memoryRevision = 1,
                    memorySemanticHash = HASH_C,
                    supportType = "SUPPORTS",
                    createdAtMs = 21,
                ),
            ),
        )
        synthesisDao.insertSnapshot(
            DreamSnapshotEntity(
                snapshotId = SNAPSHOT,
                scopeId = SCOPE,
                snapshotRevision = 1,
                sourceMemoryEpoch = 2,
                committedDreamRevision = 1,
                status = "ACTIVE",
                canonicalPayloadJson = "{\"statement\":\"$PRIVATE_TEXT\"}",
                payloadSha256 = HASH_C,
                compilerRevision = "dream-compiler-v1",
                estimatedTokens = 10,
                claimCount = 1,
                createdByRunId = RUN,
                createdAtMs = 20,
                reasonCode = "COMMITTED",
            ),
        )
    }

    private suspend fun insertOtherScopeDerivedGraph() {
        observerDao.insertScopeStateIfAbsent(
            MemoryScopeStateEntity(
                scopeId = OTHER_SCOPE,
                memoryEpoch = 1,
                observerCheckpointEpoch = 1,
                updatedAtMs = 10,
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content, revision, content_hash) " +
                "VALUES($OTHER_MEMORY_ID, '$OTHER_SCOPE', 'other scope text', 1, '$HASH_B')",
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_revisions(id, memory_id, revision, operation, actor, " +
                "created_at_ms) VALUES('memory-revision-2', $OTHER_MEMORY_ID, 1, " +
                "'CREATE', 'SYSTEM', 9)",
        )
        synthesisDao.insertClaim(
            DreamClaimEntity(
                claimId = OTHER_CLAIM,
                scopeId = OTHER_SCOPE,
                claimRevision = 1,
                claimKey = "other/project",
                storageClass = "EPISODIC",
                epistemicType = "PROJECT_STATE",
                title = "other scope text",
                statement = "other scope text",
                state = "ACTIVE_CONTEXTUAL",
                confidence = 0.9,
                temporalState = "CURRENT",
                learnedAtMs = 10,
                sourceTimezone = "Asia/Shanghai",
                claimHash = HASH_C,
                createdByRunId = RUN,
                lastValidatedMemoryEpoch = 1,
                createdAtMs = 20,
                updatedAtMs = 20,
            ),
        )
        synthesisDao.insertClaimVersion(
            DreamClaimVersionEntity(
                claimId = OTHER_CLAIM,
                claimRevision = 1,
                canonicalClaimJson = "{\"statement\":\"other scope text\"}",
                contentHash = HASH_C,
                sourceManifestHash = HASH_B,
                reasonCode = "CREATED",
                createdByRunId = RUN,
                createdAtMs = 20,
            ),
        )
        synthesisDao.insertClaimVersionSources(
            listOf(
                DreamClaimVersionSourceEntity(
                    claimId = OTHER_CLAIM,
                    claimRevision = 1,
                    memoryId = OTHER_MEMORY_ID,
                    memoryRevision = 1,
                    memorySemanticHash = HASH_B,
                    supportType = "SUPPORTS",
                    createdAtMs = 20,
                ),
            ),
        )
    }

    private fun change(epoch: Long, id: String) = MemoryScopeChangeEntity(
        scopeId = SCOPE,
        memoryEpoch = epoch,
        entityKind = "MEMORY",
        entityId = id,
        entityRevision = 1,
        operation = "UPDATE",
        reasonCode = "AUTHORITY_CHANGED",
        createdAtMs = epoch * 10,
    )

    private fun deleteMemoryRevision() = runCatching {
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM memory_revisions WHERE memory_id = $MEMORY_ID AND revision = 1",
        )
    }

    private fun deleteMemory() = runCatching {
        db.openHelper.writableDatabase.execSQL("DELETE FROM MemoryEntity WHERE id = $MEMORY_ID")
    }

    private companion object {
        const val SCOPE = "11111111-1111-1111-1111-111111111111"
        const val OTHER_SCOPE = "22222222-2222-2222-2222-222222222222"
        const val RUN = "00000000-0000-0000-0000-000000000001"
        const val OWNER = "worker-a"
        const val CLAIM = "claim-a"
        const val OTHER_CLAIM = "claim-b"
        const val SNAPSHOT = "snapshot-a"
        const val MEMORY_ID = 1
        const val OTHER_MEMORY_ID = 2
        const val EXTRA_MEMORY_ID = 3
        const val PRIVATE_TEXT = "derived private text"
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
