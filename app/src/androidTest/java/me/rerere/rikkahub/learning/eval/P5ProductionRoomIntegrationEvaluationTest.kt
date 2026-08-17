package me.rerere.rikkahub.learning.eval

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.io.PlatformTestStorageRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ProviderAttemptEvent
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.data.ai.ProviderProgressKind
import me.rerere.rikkahub.data.ai.RECALL_PROMPT_COMPILER_REVISION
import me.rerere.rikkahub.data.ai.RecallPromptBudget
import me.rerere.rikkahub.data.ai.RecallRequestPurpose
import me.rerere.rikkahub.data.ai.compileRecallPrompt
import me.rerere.rikkahub.data.authority.policy.RoomPolicyGrantAuthoritySource
import me.rerere.rikkahub.data.authority.policy.RoomPolicyGrantService
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.episode.LearningCompletionKind
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposureMetadata
import me.rerere.rikkahub.learning.exposure.PolicyExposureOutcomeLinker
import me.rerere.rikkahub.learning.exposure.PolicyExposureOutcomeLinkResult
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.exposure.PolicyExposureStoreResult
import me.rerere.rikkahub.learning.grant.AppFirstPolicyGrantReviewCoordinator
import me.rerere.rikkahub.learning.grant.PolicyGrantCoordinatedReviewResult
import me.rerere.rikkahub.learning.grant.PolicyGrantFence
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewCommand
import me.rerere.rikkahub.learning.handoff.LearningOutboxDraft
import me.rerere.rikkahub.learning.handoff.LearningOutboxAppender
import me.rerere.rikkahub.learning.handoff.RoomLearningOutboxReader
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningFeatureCapabilities
import me.rerere.rikkahub.learning.model.LearningFeatureFlagPolicy
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningFeatureFlags
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.policy.policyArtifactSha256
import me.rerere.rikkahub.learning.policy.policyApplicableCapabilityDigest
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityLedgerWriteResult
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityMatchedAssignmentIntent
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityPersistenceDisposition
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityRuntimeRequest
import me.rerere.rikkahub.learning.policy.runtime.ProductionObservedUtilityRuntime
import me.rerere.rikkahub.learning.policy.runtime.RoomObservedUtilityLedger
import me.rerere.rikkahub.learning.retrieval.LearnedPolicyQuery
import me.rerere.rikkahub.learning.retrieval.applicabilityCohortDigest
import me.rerere.rikkahub.learning.retrieval.PolicyFtsManager
import me.rerere.rikkahub.learning.retrieval.initializePolicyFtsRuntime
import me.rerere.rikkahub.learning.runtime.LearningRuntimeAccess
import me.rerere.rikkahub.learning.runtime.LearningRuntimeFacade
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningEpisodeLessonEntity
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.LearningLessonState
import me.rerere.rikkahub.learning.storage.LearningLessonType
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyEvidencePolarity
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionActor
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionReason
import me.rerere.rikkahub.learning.storage.LearningRewardDimension
import me.rerere.rikkahub.learning.storage.LearningRewardKnowledge
import me.rerere.rikkahub.learning.storage.LearningRewardSignalEntity
import me.rerere.rikkahub.learning.storage.LearningRewardSignalKind
import me.rerere.rikkahub.learning.storage.LearningSourceValidityEntity
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import me.rerere.rikkahub.learning.storage.LearningTraceFeatureEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.PolicyEvidenceEntity
import me.rerere.rikkahub.learning.storage.PolicyRevisionEntity
import me.rerere.rikkahub.learning.storage.PolicyRewardEvidenceEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disposable managed-emulator only. This opens the two real Room database classes through the
 * bundled production SQLite runtime. Never run through connectedAndroidTest or on Honor AAK-AN00.
 */
@RunWith(AndroidJUnit4::class)
class P5ProductionRoomIntegrationEvaluationTest {
    @Test
    fun exactRoomPassAndReopenedCheckedInReplayAbstainAndExport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapters = FrozenProductionComponentReplayV1.adapters
        val manifest = FrozenProductionEvalManifest.freeze(adapters)
        val fourArmAttestation = DisposableDurableFourArmEvaluationHarness(context).evaluate(
            manifest,
            adapters,
        )
        val roomAttestation = DisposableProductionRoomEvaluationHarness(context).evaluate()
        val result = ProductionLearningEvaluationCiEntry.evaluate(
            adapters = adapters,
            baseline = FrozenProductionEvalBaselineV1.baseline,
            currentEnvironmentDigestSha256 =
                FrozenProductionComponentReplayV1.environmentDigestSha256,
            roomIntegration = roomAttestation,
            fourArmRuntime = fourArmAttestation,
        )

        val testStorage = PlatformTestStorageRegistry.getInstance()
        testStorage.openOutputFile(REDACTED_ARTIFACT_FILE).bufferedWriter(Charsets.UTF_8).use {
            it.write(result.artifact.redactedReport)
        }
        testStorage.addOutputProperties(
            mapOf<String, java.io.Serializable>(
                "p5_redacted_artifact_sha256" to result.artifact.artifactDigestSha256,
                "p5_rollout_state" to result.rollout.state.name,
            ),
        )

        assertTrue(result.artifact.redactedReport.isNotEmpty())
        assertEquals(roomAttestation.attestationDigestSha256,
            result.artifact.roomIntegrationDigestSha256)
        assertEquals(fourArmAttestation.attestationDigestSha256,
            result.artifact.fourArmRuntimeDigestSha256)
        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.PERFORMANCE_NOT_ENFORCED,
            result.rollout.reason,
        )
        assertTrue(result.rollout.reason != ProductionRolloutDecisionReason.FROZEN_GATES_PASSED)
        assertEquals(
            FrozenProductionRoomIntegrationContractV1.requiredChecks,
            roomAttestation.observedChecks,
        )
        assertEquals(
            FrozenProductionFourArmRuntimeContractV1.requiredChecks - setOf(
                ProductionFourArmRuntimeCheck.INDEPENDENT_RUNTIME_AUTHORITY_CAPTURED,
                ProductionFourArmRuntimeCheck.INDEPENDENT_JUDGE_SOURCES_OBSERVED,
            ),
            fourArmAttestation.observedChecks,
        )
        assertEquals(ProductionFourArmRuntimeState.ABSTAINED, fourArmAttestation.state)
        assertEquals(
            ProductionFourArmRuntimeReason.CHECKED_IN_REGRESSION_FIXTURE_ONLY,
            fourArmAttestation.reason,
        )
        assertTrue(fourArmAttestation.durableEvidenceDigestSha256 != null)
        val redacted = result.artifact.redactedReport
        FORBIDDEN_ARTIFACT_MARKERS.forEach { marker ->
            assertFalse("redacted artifact contains a fixture marker", redacted.contains(marker))
        }
    }

    private companion object {
        const val REDACTED_ARTIFACT_FILE = "p5-production-eval-redacted.txt"
        val FORBIDDEN_ARTIFACT_MARKERS = listOf(
            P5RoomFixture.CHINESE_QUERY,
            P5RoomFixture.CHINESE_PROCEDURE,
            P5RoomFixture.CONVERSATION_ID,
            P5RoomFixture.POLICY_ID,
            "prompt=",
            "completion=",
            "Bearer ",
            "file://",
            "http://",
            "https://",
        )
    }
}

/**
 * Writes the exact checked-in 20-row authority regression, 80 arm observations and 400 component
 * receipts to a test-private SQLite database, closes it, opens a new helper, then verifies only
 * decoded rows. The origin marker forces an honest ABSTAIN after all regression invariants pass.
 */
private class DisposableDurableFourArmEvaluationHarness(
    private val context: Context,
) {
    suspend fun evaluate(
        manifest: FrozenProductionEvalManifest,
        adapters: ProductionComponentReplayAdapters,
    ): ProductionFourArmRuntimeAttestation {
        var reportDigest = "0".repeat(64)
        return try {
            val preRegistration = DurableFourArmPreRegistration.freeze(manifest)
            val journal = DurableFourArmSQLiteJournal(context)
            journal.commitPreRegistration(preRegistration)
            // No component is invoked until the assignment manifest is durably committed.
            val run = ProductionFourArmFixtureRunner(adapters).run()
            reportDigest = run.report.digestSha256()
            val captured = DurableFourArmRuntimeEvidenceCapture.captureCheckedInFixture(
                manifest,
                preRegistration,
                run,
            )
            val reopened = journal.persistCloseAndReopen(captured, preRegistration)
            ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
                expectedManifest = manifest,
                committedSnapshotDigestSha256 = reopened.committedSnapshotDigestSha256,
                reopenedPreRegistration = reopened.preRegistration,
                reopened = reopened.evidence,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ProductionFourArmRuntimeAttestationFactory.abstained(
                manifestDigestSha256 = manifest.digestSha256,
                reportDigestSha256 = reportDigest,
                reason = ProductionFourArmRuntimeReason.SOURCE_UNAVAILABLE,
            )
        } finally {
            context.deleteDatabase(DurableFourArmSQLiteJournal.DATABASE_FILE)
        }
    }
}

private data class ReopenedFourArmJournal(
    val committedSnapshotDigestSha256: String,
    val preRegistration: DurableFourArmPreRegistration,
    val evidence: DurableFourArmRuntimeEvidence,
)

private class DurableFourArmSQLiteJournal(
    private val context: Context,
) {
    fun persistCloseAndReopen(
        evidence: DurableFourArmRuntimeEvidence,
        preRegistration: DurableFourArmPreRegistration,
    ): ReopenedFourArmJournal {
        val firstOpen = JournalOpenHelper(context)
        try {
            write(firstOpen.writableDatabase, evidence, preRegistration)
        } finally {
            firstOpen.close()
        }
        check(context.getDatabasePath(DATABASE_FILE).isFile)
        val reopened = JournalOpenHelper(context)
        return try {
            read(reopened.readableDatabase)
        } finally {
            reopened.close()
        }
    }

    fun commitPreRegistration(preRegistration: DurableFourArmPreRegistration) {
        context.deleteDatabase(DATABASE_FILE)
        val firstOpen = JournalOpenHelper(context)
        try {
            val database = firstOpen.writableDatabase
            database.beginTransaction()
            try {
                insertMetadata(
                    database,
                    META_PRE_REGISTRATION,
                    preRegistration.canonicalWire(),
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } finally {
            firstOpen.close()
        }
        check(context.getDatabasePath(DATABASE_FILE).isFile)
    }

    private fun write(
        database: SQLiteDatabase,
        evidence: DurableFourArmRuntimeEvidence,
        preRegistration: DurableFourArmPreRegistration,
    ) {
        database.beginTransaction()
        try {
            check(
                DurableFourArmPreRegistration.decode(
                    readMetadata(database, META_PRE_REGISTRATION),
                ) == preRegistration,
            )
            check(evidence.preRegistrationDigestSha256 == preRegistration.digestSha256)
            insertMetadata(database, META_HEADER, evidence.headerWire())
            insertMetadata(database, META_COMMITTED_DIGEST, evidence.snapshotDigestSha256())
            evidence.authorityRecords.forEach { row ->
                insertRow(
                    database,
                    TABLE_AUTHORITY,
                    row.unitId,
                    DurableFourArmRuntimeEvidenceCodec.encodeAuthority(row),
                )
            }
            evidence.observationRecords.forEach { row ->
                insertRow(
                    database,
                    TABLE_OBSERVATION,
                    row.key,
                    DurableFourArmRuntimeEvidenceCodec.encodeObservation(row),
                )
            }
            evidence.receiptRecords.forEach { row ->
                insertRow(
                    database,
                    TABLE_RECEIPT,
                    row.key,
                    DurableFourArmRuntimeEvidenceCodec.encodeReceipt(row),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun read(database: SQLiteDatabase): ReopenedFourArmJournal {
        val header = readMetadata(database, META_HEADER)
        val committed = readMetadata(database, META_COMMITTED_DIGEST)
        val preRegistration = DurableFourArmPreRegistration.decode(
            readMetadata(database, META_PRE_REGISTRATION),
        )
        val evidence = DurableFourArmRuntimeEvidenceCodec.decodeEvidence(
            headerWire = header,
            authorityWires = readRows(database, TABLE_AUTHORITY),
            observationWires = readRows(database, TABLE_OBSERVATION),
            receiptWires = readRows(database, TABLE_RECEIPT),
        )
        return ReopenedFourArmJournal(committed, preRegistration, evidence)
    }

    private fun insertMetadata(database: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues(2).apply {
            put(COLUMN_KEY, key)
            put(COLUMN_VALUE, value)
        }
        database.insertOrThrow(TABLE_METADATA, null, values)
    }

    private fun insertRow(
        database: SQLiteDatabase,
        table: String,
        key: String,
        wire: String,
    ) {
        val values = ContentValues(2).apply {
            put(COLUMN_KEY, key)
            put(COLUMN_WIRE, wire)
        }
        database.insertOrThrow(table, null, values)
    }

    private fun readMetadata(database: SQLiteDatabase, key: String): String =
        database.query(
            TABLE_METADATA,
            arrayOf(COLUMN_VALUE),
            "$COLUMN_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.count == 1)
            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_VALUE))
        }

    private fun readRows(database: SQLiteDatabase, table: String): List<String> =
        database.query(
            table,
            arrayOf(COLUMN_WIRE),
            null,
            null,
            null,
            null,
            "$COLUMN_KEY ASC",
        ).use { cursor ->
            buildList {
                val wireIndex = cursor.getColumnIndexOrThrow(COLUMN_WIRE)
                while (cursor.moveToNext()) add(cursor.getString(wireIndex))
            }
        }

    private class JournalOpenHelper(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_FILE,
        null,
        DATABASE_VERSION,
    ) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE $TABLE_METADATA (" +
                    "$COLUMN_KEY TEXT NOT NULL PRIMARY KEY, " +
                    "$COLUMN_VALUE TEXT NOT NULL)",
            )
            listOf(TABLE_AUTHORITY, TABLE_OBSERVATION, TABLE_RECEIPT).forEach { table ->
                database.execSQL(
                    "CREATE TABLE $table (" +
                        "$COLUMN_KEY TEXT NOT NULL PRIMARY KEY, " +
                        "$COLUMN_WIRE TEXT NOT NULL)",
                )
            }
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("P5 disposable journal has no upgrade path")
        }
    }

    companion object {
        const val DATABASE_FILE = "p5_durable_four_arm_journal.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_METADATA = "runtime_metadata"
        private const val TABLE_AUTHORITY = "authority_trace"
        private const val TABLE_OBSERVATION = "arm_observation"
        private const val TABLE_RECEIPT = "component_receipt"
        private const val COLUMN_KEY = "row_key"
        private const val COLUMN_VALUE = "row_value"
        private const val COLUMN_WIRE = "canonical_wire"
        private const val META_HEADER = "evidence_header"
        private const val META_PRE_REGISTRATION = "pre_registration"
        private const val META_COMMITTED_DIGEST = "committed_snapshot_digest"
    }
}

private class DisposableProductionRoomEvaluationHarness(
    private val context: Context,
) {
    private val checks = linkedSetOf<ProductionRoomIntegrationCheck>()
    private var appDatabase: AppDatabase? = null
    private var runtime: LearningRuntimeFacade? = null
    private var openedLearningDatabase: LearningDatabase? = null

    suspend fun evaluate(): ProductionRoomIntegrationAttestation {
        context.deleteDatabase(P5RoomFixture.PRIMARY_DATABASE_FILE)
        context.deleteDatabase(LearningDatabase.FILE_NAME)
        val attestation = try {
            executeExactScenario()
            if (checks == FrozenProductionRoomIntegrationContractV1.requiredChecks) {
                ProductionRoomIntegrationAttestationFactory.passed(checks)
            } else {
                ProductionRoomIntegrationAttestationFactory.abstained(
                    ProductionRoomIntegrationReason.REQUIRED_BOUNDARY_NOT_OBSERVED,
                    checks,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: P5RoomSemanticFailure) {
            ProductionRoomIntegrationAttestationFactory.rejected(failure.reason, checks)
        } catch (_: Throwable) {
            ProductionRoomIntegrationAttestationFactory.abstained(
                ProductionRoomIntegrationReason.RUNTIME_OR_STORAGE_UNAVAILABLE,
                checks,
            )
        } finally {
            runCatching { openedLearningDatabase?.close() }
            runCatching { runtime?.close() }
            runCatching { appDatabase?.close() }
            openedLearningDatabase = null
            runtime = null
            appDatabase = null
            context.deleteDatabase(P5RoomFixture.PRIMARY_DATABASE_FILE)
            context.deleteDatabase(LearningDatabase.FILE_NAME)
        }
        return attestation
    }

    private suspend fun executeExactScenario() {
        val primary = openPrimaryDatabase().also { appDatabase = it }
        checks += ProductionRoomIntegrationCheck.APP_DATABASE_ROOM_OPENED
        seedAuthoritativeStream(primary)

        val seedDatabase = openLearningDatabase().also { openedLearningDatabase = it }
        seedLearningDatabase(seedDatabase)
        requireBoundary(
            PolicyFtsManager(seedDatabase).searchEligible(
                scope = P5RoomFixture.SCOPE,
                query = P5RoomFixture.CHINESE_QUERY,
                freshAfterMs = 0L,
                limit = 4,
            ).map { it.id } == listOf(P5RoomFixture.POLICY_ID),
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.REAL_FTS5_CHINESE_MATCH
        seedDatabase.close()
        openedLearningDatabase = null

        val outboxReader = RoomLearningOutboxReader(primary)
        val descriptor = outboxReader.inspect()
        requireBoundary(
            descriptor.streamId == P5RoomFixture.STREAM_ID && descriptor.headSequence == 1L,
            ProductionRoomIntegrationReason.EXACT_IDENTITY_OR_AUTHORITY_MISMATCH,
        )
        checks += ProductionRoomIntegrationCheck.AUTHORITATIVE_STREAM_BOUND

        val flags = P5RoomFixture.enabledFeatureFlags()
        fun openFacade() = LearningRuntimeFacade(
            context = context,
            isEnabled = { true },
            outboxReader = outboxReader,
            learningFeatureFlags = flags,
            policyGrantAuthority = RoomPolicyGrantAuthoritySource(primary),
            sqliteOpenHelperFactory = createAppSQLiteOpenHelperFactory(context),
        )
        var facade = openFacade().also { runtime = it }
        requireBoundary(
            facade.withDatabase { session ->
                requireBoundary(
                    session.isCurrent(),
                    ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
                )
            } == LearningRuntimeAccess.Ready,
            ProductionRoomIntegrationReason.RUNTIME_OR_STORAGE_UNAVAILABLE,
        )
        checks += ProductionRoomIntegrationCheck.LEARNING_DATABASE_FACADE_OPENED

        val grantResult = AppFirstPolicyGrantReviewCoordinator(
            authority = RoomPolicyGrantService(primary),
            lifecycle = facade,
            positiveMutations = LearningPositiveMutationGate { true },
        ).review(P5RoomFixture.grantCommand())
        requireBoundary(
            grantResult is PolicyGrantCoordinatedReviewResult.Completed,
            ProductionRoomIntegrationReason.EXACT_IDENTITY_OR_AUTHORITY_MISMATCH,
        )
        val completedGrant = grantResult as PolicyGrantCoordinatedReviewResult.Completed
        requireBoundary(
            completedGrant.authority.policyId == P5RoomFixture.POLICY_ID &&
                RoomPolicyGrantAuthoritySource(primary)
                    .revalidateExact(completedGrant.authority),
            ProductionRoomIntegrationReason.EXACT_IDENTITY_OR_AUTHORITY_MISMATCH,
        )
        checks += ProductionRoomIntegrationCheck.EXACT_GRANT_COMMITTED

        val review = facade.readForReview(P5RoomFixture.ASSISTANT_ID, P5RoomFixture.POLICY_ID)
        requireBoundary(
            review is me.rerere.rikkahub.learning.review.PolicyReviewReadResult.Ready &&
                review.value.item.status == me.rerere.rikkahub.learning.policy
                    .LearningPolicyStatus.ACTIVE,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.POLICY_LIFECYCLE_ACTIVE

        val policyQuery = LearnedPolicyQuery(
            scope = P5RoomFixture.SCOPE,
            consumingAssistantId = P5RoomFixture.ASSISTANT_ID,
            taskSignature = P5RoomFixture.TASK_SIGNATURE,
            query = P5RoomFixture.CHINESE_QUERY,
            maxCandidates = 3,
            maxEstimatedTokens = 256,
        )
        val retrieval = facade.retrieve(policyQuery)
        requireBoundary(
            retrieval.packet.candidates.map { it.policyId } ==
                listOf(P5RoomFixture.POLICY_ID) &&
                retrieval.grantReceipts.singleOrNull()?.authority == completedGrant.authority,
            ProductionRoomIntegrationReason.EXACT_IDENTITY_OR_AUTHORITY_MISMATCH,
        )
        checks += ProductionRoomIntegrationCheck.ACTIVE_POLICY_ROOM_RETRIEVAL

        // A current-stream authority invalidation is a hard pre-provider barrier even when the
        // Policy/grant tuple itself still looks exact. Once the exact job is DONE, the unaffected
        // Policy is available again without mutating its content or grant.
        val barrierDatabase = openLearningDatabase()
        requireBoundary(
            barrierDatabase.jobDao().insertIgnore(P5RoomFixture.pendingInvalidationJob()) != -1L,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        barrierDatabase.close()
        facade.close()
        runtime = null
        facade = openFacade().also { runtime = it }
        requireBoundary(
            facade.withDatabase { session ->
                requireBoundary(
                    session.isCurrent(),
                    ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
                )
            } == LearningRuntimeAccess.Ready,
            ProductionRoomIntegrationReason.RUNTIME_OR_STORAGE_UNAVAILABLE,
        )
        requireBoundary(
            facade.retrieve(policyQuery).packet.candidates.isEmpty() &&
                !facade.revalidateForDispatch(
                    retrieval.grantReceipts,
                    P5RoomFixture.ASSISTANT_ID,
                ),
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        val completedBarrierDatabase = openLearningDatabase()
        completedBarrierDatabase.openHelper.writableDatabase.execSQL(
            "UPDATE learning_jobs SET state = 'DONE', updated_at_ms = 61, " +
                "finished_at_ms = 61 WHERE id = ?",
            arrayOf(P5RoomFixture.PENDING_INVALIDATION_JOB_ID),
        )
        completedBarrierDatabase.close()
        // The mutation above deliberately models another worker/process completing the durable
        // barrier. Reopen the production facade so this assertion verifies persisted state rather
        // than depending on cross-Room invalidation delivery between two test-owned instances.
        facade.close()
        runtime = null
        facade = openFacade().also { runtime = it }
        requireBoundary(
            facade.withDatabase { session ->
                requireBoundary(
                    session.isCurrent(),
                    ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
                )
            } == LearningRuntimeAccess.Ready,
            ProductionRoomIntegrationReason.RUNTIME_OR_STORAGE_UNAVAILABLE,
        )
        requireBoundary(
            facade.revalidateForDispatch(
                retrieval.grantReceipts,
                P5RoomFixture.ASSISTANT_ID,
            ) && facade.retrieve(policyQuery).packet.candidates
                .singleOrNull()?.policyId == P5RoomFixture.POLICY_ID,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )

        val compiled = compileRecallPrompt(
            memory = emptyList(),
            policies = retrieval.packet.candidates,
            budget = RecallPromptBudget(
                maxTokens = 512,
                maxChars = 8_192,
                maxPolicyTokens = 256,
                maxPolicyItems = 3,
            ),
            requestPurpose = RecallRequestPurpose.NORMAL,
        )
        requireBoundary(
            compiled.manifest.actualPolicyItems.singleOrNull()?.id ==
                P5RoomFixture.POLICY_ID &&
                compiled.dropped.none { it.id == P5RoomFixture.POLICY_ID } &&
                compiled.text.contains(P5RoomFixture.CHINESE_PROCEDURE),
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.RECALL_COMPILER_INCLUDED_WHOLE_POLICY

        val candidate = retrieval.packet.candidates.single()
        val policyReference = PolicyExposurePolicyRef(
            policyId = candidate.policyId,
            policyRevision = candidate.policyRevision,
            artifactSha256 = candidate.artifactSha256,
            scope = candidate.scope,
            rank = 1,
            estimatedTokens = candidate.estimatedTokens,
            applicabilityCohortDigest = candidate.applicabilityCohortDigest(),
        )
        val bundle = PolicyExposureBundle.create(listOf(policyReference))
        val reservation = PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = P5RoomFixture.STREAM_ID,
                episodeId = P5RoomFixture.EXPOSURE_EPISODE_ID,
                logicalRunId = P5RoomFixture.LOGICAL_RUN_ID,
                attemptOrdinal = 1,
                policySetDigest = bundle.policySetDigest,
            ),
            bundle = bundle,
        )
        val metadata = P5RoomFixture.exposureMetadata()
        var receipt = facade.reserve(reservation, metadata, 100L).requireAvailable()
        checks += ProductionRoomIntegrationCheck.EXPOSURE_RESERVED

        val assignmentResult = facade.reserveMatched(
            ObservedUtilityMatchedAssignmentIntent(
                reservation = reservation,
                metadata = metadata,
                primaryPolicyId = P5RoomFixture.POLICY_ID,
                arm = ObservedUtilityArm.EXPOSED,
                matchKeyDigest = LearningCanonicalId.digest(
                    "p5-room-matched-key-v1",
                    listOf(FrozenProductionRoomIntegrationContractV1.fixtureDigestSha256),
                ),
                sourceWindowStartMs = 0L,
                sourceWindowEndMs = P5RoomFixture.UTILITY_WINDOW_END_MS,
                eligibilityDeterminedAtMs = 101L,
                assignedAtMs = 101L,
            ),
        )
        requireBoundary(
            assignmentResult is ObservedUtilityLedgerWriteResult.Applied,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        val assignmentId = (assignmentResult as ObservedUtilityLedgerWriteResult.Applied).identity

        receipt = facade.observeMilestone(
            reservation.key.reservationId,
            receipt.stateVersion,
            PolicyExposureState.COMPILED,
            102L,
        ).requireAvailable()
        checks += ProductionRoomIntegrationCheck.EXPOSURE_COMPILED
        receipt = facade.observeMilestone(
            reservation.key.reservationId,
            receipt.stateVersion,
            PolicyExposureState.INJECTED,
            103L,
        ).requireAvailable()
        checks += ProductionRoomIntegrationCheck.EXPOSURE_INJECTED
        receipt = facade.observeProviderAttempt(
            reservation.key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.HostDispatched(1, stream = true),
            104L,
        ).requireAvailable()
        checks += ProductionRoomIntegrationCheck.EXPOSURE_HOST_DISPATCHED
        receipt = facade.observeProviderAttempt(
            reservation.key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.FirstProgress(1, ProviderProgressKind.STREAM_PROGRESS),
            105L,
        ).requireAvailable()
        checks += ProductionRoomIntegrationCheck.EXPOSURE_FIRST_PROGRESS
        receipt = facade.observeProviderAttempt(
            reservation.key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.ResponseFinished(1),
            106L,
        ).requireAvailable()
        checks += ProductionRoomIntegrationCheck.EXPOSURE_RESPONSE_FINISHED
        receipt = facade.observeProviderAttempt(
            reservation.key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.Terminal(1, ProviderAttemptTerminalOutcome.COMPLETED),
            107L,
        ).requireAvailable()
        requireBoundary(
            receipt.terminalOutcome == ProviderAttemptTerminalOutcome.COMPLETED,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.PROVIDER_TERMINAL_COMMITTED

        val terminal = appendAuthoritativeTerminal(primary)
        facade.close()
        runtime = null

        val linkedDatabase = openLearningDatabase().also { openedLearningDatabase = it }
        linkedDatabase.withTransaction {
            requireBoundary(
                linkedDatabase.episodeDao().updateBoundaryIfCurrent(
                    episodeId = P5RoomFixture.EXPOSURE_EPISODE_ID.value,
                    expectedRevision = 1L,
                    expectedStatus = StoredLearningEpisodeStatus.OPEN.name,
                    conversationRevision = 1L,
                    finalCommandId = P5RoomFixture.FINAL_COMMAND_ID,
                    finalCommandRevision = 2L,
                    resultAssistantMessageId = P5RoomFixture.RESULT_MESSAGE_ID,
                    resultAssistantMessageRevision = 1L,
                    generationRunId = P5RoomFixture.LOGICAL_RUN_ID.toString(),
                    executionId = null,
                    taskSignature = P5RoomFixture.TASK_SIGNATURE.value,
                    newStatus = StoredLearningEpisodeStatus.SUCCESS.name,
                    boundaryReason = LearningEpisodeBoundaryReason.FINAL_SAVED.name,
                    finalizedAtMs = 108L,
                    updatedAtMs = 108L,
                ) == 1,
                ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
            )
            requireBoundary(
                linkedDatabase.inboxDao().insertIgnore(
                    terminal.toInboxEntity(ingestedAtMs = 109L, replayGeneration = 0L),
                ) != -1L,
                ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
            )
            requireBoundary(
                linkedDatabase.checkpointDao().advanceContiguously(
                    streamId = P5RoomFixture.STREAM_ID.toString(),
                    replayGeneration = 0L,
                    expectedPreviousSeq = 1L,
                    lastContiguousSeq = terminal.outboxSeq,
                    lastSeenHeadSeq = terminal.outboxSeq,
                    updatedAtMs = 109L,
                ) == 1,
                ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
            )
        }
        val terminalEpisode = requireNotNull(
            linkedDatabase.episodeDao().findEpisode(P5RoomFixture.EXPOSURE_EPISODE_ID.value),
        )
        val link = PolicyExposureOutcomeLinker(linkedDatabase).replayCommittedTerminal(
            terminal.toInboxEntity(ingestedAtMs = 109L, replayGeneration = 0L),
            terminalEpisode,
        )
        requireBoundary(
            link == PolicyExposureOutcomeLinkResult(
                authorityEligible = true,
                scanned = 1,
                applied = 1,
                duplicates = 0,
                conflicts = 0,
                unavailable = 0,
            ),
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.TERMINAL_AUTHORITY_OUTCOME_LINKED

        val assignment = requireNotNull(
            linkedDatabase.observedUtilityDao().findAssignment(assignmentId),
        ).toDomain()
        val ledger = RoomObservedUtilityLedger(
            linkedDatabase,
            linkedDatabase.observedUtilityDao(),
        )
        val utility = ProductionObservedUtilityRuntime(ledger, ledger).evaluate(
            ObservedUtilityRuntimeRequest(
                fence = assignment.fence,
                design = assignment.design,
                expectedCohortDigest = assignment.cohortDigest,
                sourceWindowStartMs = assignment.sourceWindowStartMs,
                sourceWindowEndMs = assignment.sourceWindowEndMs,
            ),
            frozenNowMs = assignment.sourceWindowEndMs,
        )
        val persisted = when (utility) {
            is me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityRuntimeResult.Evaluated ->
                utility.persistence
            is me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityRuntimeResult.Abstained ->
                utility.persistence
        }
        requireBoundary(
            persisted in setOf(
                ObservedUtilityPersistenceDisposition.APPLIED,
                ObservedUtilityPersistenceDisposition.DUPLICATE,
            ) && linkedDatabase.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM learning_observed_utility_evaluation_receipts",
            ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L },
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.OBSERVED_UTILITY_RECEIPT_COMMITTED

        val storedExposure = requireNotNull(
            linkedDatabase.policyExposureDao().findExposure(reservation.key.reservationId),
        )
        val storedPolicy = requireNotNull(
            linkedDatabase.policyDao().findPolicy(P5RoomFixture.POLICY_ID),
        )
        primary.close()
        appDatabase = null
        val reopenedPrimary = openPrimaryDatabase().also { appDatabase = it }
        val reopenedGrant = RoomPolicyGrantAuthoritySource(reopenedPrimary).listExactGranted(
            scope = P5RoomFixture.SCOPE,
            consumingAssistantId = P5RoomFixture.ASSISTANT_ID,
            sourceStreamId = P5RoomFixture.STREAM_ID.toString(),
            limit = 2,
        ).singleOrNull()
        requireBoundary(
            storedExposure.furthestState == PolicyExposureState.OUTCOME_LINKED.name &&
                storedExposure.outcomeSourceType == LearningSourceKind.CONVERSATION_MESSAGE.name &&
                storedPolicy.status == StoredLearningPolicyStatus.ACTIVE.name &&
                storedPolicy.usageCount == 1L &&
                linkedDatabase.observedUtilityDao().findOutcome(assignmentId) != null &&
                reopenedGrant?.let { grant ->
                    grant.policyId == P5RoomFixture.POLICY_ID &&
                        grant.artifactSha256 == P5RoomFixture.POLICY_ARTIFACT
                } == true,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        checks += ProductionRoomIntegrationCheck.EXACT_ROOM_ROWS_RELOADED
    }

    private fun openPrimaryDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        P5RoomFixture.PRIMARY_DATABASE_FILE,
    ).openHelperFactory(createAppSQLiteOpenHelperFactory(context)).build().also {
        it.openHelper.writableDatabase
    }

    private fun openLearningDatabase(): LearningDatabase = Room.databaseBuilder(
        context,
        LearningDatabase::class.java,
        LearningDatabase.FILE_NAME,
    ).openHelperFactory(createAppSQLiteOpenHelperFactory(context))
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                initializePolicyFtsRuntime(context, db)
            }
        })
        .build()
        .also { it.openHelper.writableDatabase }

    private suspend fun seedAuthoritativeStream(database: AppDatabase) {
        requireBoundary(
            database.learningOutboxDao().insertIgnore(
                LearningOutboxDraft(
                    streamId = P5RoomFixture.STREAM_ID,
                    eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
                    source = null,
                    correlation = LearningCorrelation(),
                    terminalStateCode = null,
                    createdAtMs = 1L,
                ).toEntity(),
            ) == 1L,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
    }

    private suspend fun seedLearningDatabase(database: LearningDatabase) {
        database.withTransaction {
            database.checkpointDao().insert(P5RoomFixture.checkpoint())
            database.episodeDao().insertEpisodeIgnore(P5RoomFixture.evidenceEpisode())
            database.episodeDao().insertTraceIgnore(P5RoomFixture.evidenceTrace())
            database.episodeDao().insertLessonIgnore(P5RoomFixture.evidenceLesson())
            database.episodeDao().insertSourceValidityIgnore(
                P5RoomFixture.conversationValidity(),
            )
            database.episodeDao().insertSourceValidityIgnore(P5RoomFixture.rewardValidity())
            database.rewardSignalDao().insertSignalIgnore(P5RoomFixture.rewardSignal())
            database.policyDao().insertPolicy(P5RoomFixture.policy())
            database.policyDao().insertRevision(P5RoomFixture.initialPolicyRevision())
            database.policyDao().insertEvidenceIgnore(P5RoomFixture.policyEvidence())
            database.rewardSignalDao().insertPolicyRewardEvidenceIgnore(
                P5RoomFixture.policyRewardEvidence(),
            )
            database.episodeDao().insertEpisodeIgnore(P5RoomFixture.openExposureEpisode())
        }
    }

    private suspend fun appendAuthoritativeTerminal(
        database: AppDatabase,
    ): me.rerere.rikkahub.learning.handoff.LearningHandoffEvent {
        database.withTransaction {
            LearningOutboxAppender(database).appendInCurrentAuthorityTransaction { stream ->
                LearningOutboxDraft(
                    streamId = stream,
                    eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 2),
                    source = LearningSourceRef(
                        sourceKind = LearningSourceKind.COMMAND,
                        sourceId = P5RoomFixture.FINAL_COMMAND_ID,
                        sourceRevision = 2L,
                        missingRevisionReason = null,
                        databaseStreamId = stream,
                        scope = P5RoomFixture.SCOPE,
                        occurredAtMs = 108L,
                    ),
                    correlation = LearningCorrelation(
                        conversationId = P5RoomFixture.CONVERSATION_ID,
                        conversationSourceRevision = 1L,
                        commandId = P5RoomFixture.FINAL_COMMAND_ID,
                        lineageId = P5RoomFixture.LINEAGE_ID.toString(),
                        branchAnchorMessageId = P5RoomFixture.BRANCH_ANCHOR_ID.toString(),
                        branchAnchorMessageRevision = 1L,
                        completionKindCode = LearningCompletionKind.GENERATION_FINAL_SAVED.name,
                        generationRunId = P5RoomFixture.LOGICAL_RUN_ID.toString(),
                        messageId = P5RoomFixture.RESULT_MESSAGE_ID,
                        messageRevision = 1L,
                    ),
                    terminalStateCode = "COMPLETED",
                    createdAtMs = 108L,
                )
            }
        }
        val descriptor = RoomLearningOutboxReader(database).inspect()
        return RoomLearningOutboxReader(database)
            .readAfterThrough(descriptor, afterSequence = 1L, limit = 2)
            .single()
    }

    private fun PolicyExposureStoreResult.requireAvailable() = when (this) {
        is PolicyExposureStoreResult.Available -> receipt
        is PolicyExposureStoreResult.Conflict -> throw P5RoomSemanticFailure(
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        )
        is PolicyExposureStoreResult.Unavailable -> throw IllegalStateException(
            "p5_room_exposure_storage_unavailable",
        )
    }
}

private object P5RoomFixture {
    const val PRIMARY_DATABASE_FILE = "p5_production_room_app.db"
    val STREAM_ID: Uuid = Uuid.parse("50000000-0000-4000-8000-000000000001")
    val ASSISTANT_ID: Uuid = Uuid.parse("50000000-0000-4000-8000-000000000002")
    val SCOPE: LearningScope.Assistant = LearningScope.Assistant(ASSISTANT_ID)
    val LINEAGE_ID: Uuid = Uuid.parse("50000000-0000-4000-8000-000000000003")
    val BRANCH_ANCHOR_ID: Uuid = Uuid.parse("50000000-0000-4000-8000-000000000004")
    val LOGICAL_RUN_ID: Uuid = Uuid.parse("50000000-0000-4000-8000-000000000005")
    val EXPOSURE_EPISODE_ID: EpisodeId = EpisodeIdFactory.create(
        STREAM_ID,
        LINEAGE_ID,
        BRANCH_ANCHOR_ID,
    )
    val TASK_SIGNATURE: TaskSignatureV1 = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )
    const val POLICY_ID =
        "policy-p5-room-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val EVIDENCE_EPISODE_ID =
        "episode-v1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val CONVERSATION_ID = "p5-room-conversation"
    const val FINAL_COMMAND_ID = "p5-room-final-command"
    const val RESULT_MESSAGE_ID = "p5-room-result-message"
    const val EVIDENCE_MESSAGE_ID = "p5-room-evidence-message"
    const val REWARD_SOURCE_ID = "p5-room-feedback"
    const val REWARD_SIGNAL_ID = "p5-room-reward-signal"
    const val PENDING_INVALIDATION_JOB_ID = "p5-room-pending-invalidation"
    const val CHINESE_QUERY = "中文重试验证"
    const val CHINESE_PROCEDURE = "仅在明确的暂时失败后重试一次"
    const val UTILITY_WINDOW_END_MS = 86_400_000L
    private const val TRIGGER = "中文重试验证"
    private const val PROCEDURE = "先检查前置条件；仅在明确的暂时失败后重试一次"
    private const val VERIFICATION = "核验结果并保留例外"
    private const val BOUNDARY = "当前助手精确任务"
    private const val FAILURE_MODE = "证据不足时保持基线"
    private val MODEL_ID = "1".repeat(64)
    private val PROVIDER_ID = "2".repeat(64)
    private val TEMPLATE_ID = "3".repeat(64)
    private val CONFIGURATION_ID = "4".repeat(64)
    private val MESSAGE_INTEGRITY = "5".repeat(64)
    private val REWARD_INTEGRITY = "6".repeat(64)
    private val EMPTY_CAPABILITY_DIGEST =
        requireNotNull(policyApplicableCapabilityDigest(emptySet()))
    val POLICY_ARTIFACT = policyArtifactSha256(
        type = PolicyCandidateType.PROCEDURE,
        trigger = TRIGGER,
        procedure = PROCEDURE,
        verification = VERIFICATION,
        boundary = BOUNDARY,
        failureMode = FAILURE_MODE,
        applicableToolSchemas = emptySet(),
        applicableModelIdentity = MODEL_ID,
        applicableProviderIdentity = PROVIDER_ID,
        applicableTemplateIdentity = TEMPLATE_ID,
        applicableConfigurationIdentity = CONFIGURATION_ID,
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = EMPTY_CAPABILITY_DIGEST,
        applicableAuthorityDigest = null,
    )

    fun enabledFeatureFlags(): LearningFeatureFlagSource {
        val resolved = LearningFeatureFlagPolicy.resolve(
            LearningFeatureFlags(
                schemaReady = true,
                handoff = true,
                capture = true,
                jobs = true,
                policyCandidate = true,
                policyRetrievalShadow = true,
                policyInjection = true,
            ),
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
            ),
        )
        check(resolved.isValid)
        return LearningFeatureFlagSource { resolved }
    }

    fun grantCommand() = PolicyGrantReviewCommand(
        fence = PolicyGrantFence.GRANT,
        sourceStreamId = STREAM_ID.toString(),
        scope = SCOPE,
        consumingAssistantId = ASSISTANT_ID,
        policyId = POLICY_ID,
        contentRevision = 1L,
        artifactSha256 = POLICY_ARTIFACT,
        expectedGrantStateVersion = 0L,
        frozenNowEpochMs = 50L,
        reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
    )

    fun checkpoint() = LearningStreamCheckpointEntity(
        streamId = STREAM_ID.toString(),
        lastContiguousSeq = 1L,
        lastSeenHeadSeq = 1L,
        replayGeneration = 0L,
        resetReason = null,
        bootstrapState = LearningBootstrapState.COMPLETE.name,
        bootstrapHeadSeq = 1L,
        coverageStartMs = 0L,
        commandCoverageStartMs = 0L,
        executionCoverageStartMs = 0L,
        updatedAtMs = 1L,
        sourceAuthorityCoverageStartMs = 0L,
        feedbackCoverageStartMs = 0L,
    )

    fun pendingInvalidationJob() = LearningJobEntity(
        id = PENDING_INVALIDATION_JOB_ID,
        jobType = LearningJobType.INVALIDATE_SOURCE_V1.name,
        jobSchemaVersion = 1,
        dedupeKey = "p5-room-pending-invalidation-dedupe",
        streamId = STREAM_ID.toString(),
        sourceEventId = "p5-room-pending-invalidation-event",
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = 5,
        notBeforeMs = 60L,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0L,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = 60L,
        updatedAtMs = 60L,
        finishedAtMs = null,
        replayGeneration = 0L,
        algorithmIdentity = "source-invalidation-v1",
        promptIdentity = "no-provider-prompt-v1",
        providerKindIdentity = "none",
        modelIdentity = "no-provider-model-v1",
        providerIdentity = "no-provider-v1",
        providerConfigurationIdentity = "no-provider-configuration-v1",
        providerConfigGeneration = 0L,
        sourceSchemaIdentity = "learning-source-invalidation-event-v2",
        toolsetIdentity = "authority-event-only-v1",
        outputSchemaIdentity = "learning-source-validity-output-v1",
    )

    fun evidenceEpisode() = LearningEpisodeEntity(
        id = EVIDENCE_EPISODE_ID,
        streamId = STREAM_ID.toString(),
        replayGeneration = 0L,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = "p5-room-evidence-conversation",
        conversationRevision = 1L,
        rootCommandId = "p5-room-evidence-command",
        rootCommandRevision = 1L,
        finalCommandId = "p5-room-evidence-command",
        finalCommandRevision = 1L,
        lineageId = "p5-room-evidence-lineage",
        branchAnchorMessageId = EVIDENCE_MESSAGE_ID,
        branchAnchorMessageRevision = 1L,
        resultAssistantMessageId = "p5-room-evidence-result",
        resultAssistantMessageRevision = 1L,
        generationRunId = "p5-room-evidence-run",
        executionId = null,
        taskSignature = TASK_SIGNATURE.value,
        status = StoredLearningEpisodeStatus.SUCCESS.name,
        boundaryReason = LearningEpisodeBoundaryReason.FINAL_SAVED.name,
        revision = 1L,
        startedAtMs = 2L,
        finalizedAtMs = 3L,
        createdAtMs = 2L,
        updatedAtMs = 3L,
    )

    fun openExposureEpisode() = LearningEpisodeEntity(
        id = EXPOSURE_EPISODE_ID.value,
        streamId = STREAM_ID.toString(),
        replayGeneration = 0L,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = CONVERSATION_ID,
        conversationRevision = 1L,
        rootCommandId = "p5-room-root-command",
        rootCommandRevision = 1L,
        finalCommandId = null,
        finalCommandRevision = null,
        lineageId = LINEAGE_ID.toString(),
        branchAnchorMessageId = BRANCH_ANCHOR_ID.toString(),
        branchAnchorMessageRevision = 1L,
        resultAssistantMessageId = null,
        resultAssistantMessageRevision = null,
        generationRunId = LOGICAL_RUN_ID.toString(),
        executionId = null,
        taskSignature = TASK_SIGNATURE.value,
        status = StoredLearningEpisodeStatus.OPEN.name,
        boundaryReason = LearningEpisodeBoundaryReason.COMMAND_ADMITTED.name,
        revision = 1L,
        startedAtMs = 10L,
        finalizedAtMs = null,
        createdAtMs = 10L,
        updatedAtMs = 10L,
    )

    fun evidenceTrace() = LearningTraceFeatureEntity(
        episodeId = EVIDENCE_EPISODE_ID,
        sequence = 1L,
        sourceOrdinal = 0,
        sourceType = LearningSourceKind.CONVERSATION_MESSAGE.name,
        sourceId = EVIDENCE_MESSAGE_ID,
        sourceRevision = 1L,
        missingRevisionReason = null,
        actionType = "MODEL",
        actionName = "assistant.response",
        toolSchemaFingerprint = null,
        outcomeClass = "SUCCESS",
        errorCode = null,
        stateSummary = null,
        observationSummary = "固定非用户语料已完成",
        inputTokenCount = 8L,
        outputTokenCount = 8L,
        toolCount = 0,
        retryCount = 0,
        durationMs = 8L,
        alpha = null,
        quality = 1.0,
        featureSchemaIdentity = "trace-feature-v1",
        createdAtMs = 3L,
    )

    fun evidenceLesson() = LearningEpisodeLessonEntity(
        episodeId = EVIDENCE_EPISODE_ID,
        lessonVersion = 1,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        lessonType = LearningLessonType.SUCCESS_PATTERN.name,
        triggerSummary = TRIGGER,
        observationSummary = "固定非用户语料已完成",
        lessonSummary = PROCEDURE,
        boundarySummary = BOUNDARY,
        evidenceManifestSha256 = MESSAGE_INTEGRITY,
        artifactSha256 = "7".repeat(64),
        producerProviderIdentity = PROVIDER_ID,
        producerProviderKind = "local_litert",
        producerModelIdentity = MODEL_ID,
        producerConfigurationIdentity = CONFIGURATION_ID,
        producerConfigGeneration = 1L,
        algorithmIdentity = "reflection-v1",
        promptIdentity = "reflection-v1",
        templateIdentity = "reflection-v1",
        schemaIdentity = "episode-lesson-v1",
        inputTokenCount = 8L,
        outputTokenCount = 8L,
        estimatedCostMicros = 0L,
        remoteProvider = false,
        state = LearningLessonState.VALID.name,
        createdAtMs = 4L,
        updatedAtMs = 4L,
    )

    fun conversationValidity() = sourceValidity(
        sourceType = LearningSourceKind.CONVERSATION_MESSAGE.name,
        sourceId = EVIDENCE_MESSAGE_ID,
        integrity = MESSAGE_INTEGRITY,
        eventId = "p5-room-message-authority",
    )

    fun rewardValidity() = sourceValidity(
        sourceType = LearningSourceKind.USER_FEEDBACK.name,
        sourceId = REWARD_SOURCE_ID,
        integrity = REWARD_INTEGRITY,
        eventId = "p5-room-feedback-authority",
    )

    private fun sourceValidity(
        sourceType: String,
        sourceId: String,
        integrity: String,
        eventId: String,
    ) = LearningSourceValidityEntity(
        streamId = STREAM_ID.toString(),
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        sourceType = sourceType,
        sourceId = sourceId,
        sourceRevision = 1L,
        previousSourceRevision = null,
        state = LearningSourceValidityState.VALID.name,
        integritySha256 = integrity,
        invalidationReason = null,
        authorityEventId = eventId,
        replayGeneration = 0L,
        occurredAtMs = 2L,
        updatedAtMs = 2L,
    )

    fun rewardSignal() = LearningRewardSignalEntity(
        id = REWARD_SIGNAL_ID,
        episodeId = EVIDENCE_EPISODE_ID,
        streamId = STREAM_ID.toString(),
        replayGeneration = 0L,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        authorityEventId = "p5-room-feedback-authority",
        sourceType = LearningSourceKind.USER_FEEDBACK.name,
        sourceId = REWARD_SOURCE_ID,
        sourceRevision = 1L,
        sourceIntegritySha256 = REWARD_INTEGRITY,
        dimension = LearningRewardDimension.USER.name,
        signalKind = LearningRewardSignalKind.EXPLICIT_USER_FEEDBACK.name,
        knowledge = LearningRewardKnowledge.KNOWN.name,
        valueMilli = 1_000,
        unknownReason = null,
        occurredAtMs = 3L,
        createdAtMs = 3L,
    )

    fun policy() = LearningPolicyEntity(
        id = POLICY_ID,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        taskSignature = TASK_SIGNATURE.value,
        policyType = PolicyCandidateType.PROCEDURE.name,
        triggerSummary = TRIGGER,
        procedureSummary = PROCEDURE,
        verificationSummary = VERIFICATION,
        boundarySummary = BOUNDARY,
        failureModeSummary = FAILURE_MODE,
        stateVersion = 1L,
        contentRevision = 1L,
        artifactSha256 = POLICY_ARTIFACT,
        compilerAbi = "p5-room-policy-v1",
        status = StoredLearningPolicyStatus.SHADOW.name,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
        applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity(MODEL_ID),
        applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity(PROVIDER_ID),
        applicableTemplateIdentity = TEMPLATE_ID,
        applicableConfigurationIdentity = CONFIGURATION_ID,
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = EMPTY_CAPABILITY_DIGEST,
        applicableAuthorityDigest = null,
        staleReason = null,
        distinctEpisodeSupport = 1L,
        positiveEpisodeCount = 1L,
        negativeEpisodeCount = 0L,
        usageCount = 0L,
        confidence = 1.0,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        producerModelIdentity = MODEL_ID,
        producerProviderIdentity = PROVIDER_ID,
        producerProviderKind = "local_litert",
        producerConfigurationIdentity = CONFIGURATION_ID,
        producerConfigGeneration = 1L,
        producerPromptIdentity = "policy-distillation-v1",
        producerTemplateIdentity = "policy-distillation-v1",
        producerSchemaIdentity = "policy-candidate-v1",
        createdAtMs = 5L,
        updatedAtMs = 5L,
        lastUsedAtMs = null,
    )

    fun initialPolicyRevision() = PolicyRevisionEntity(
        policyId = POLICY_ID,
        revision = 1L,
        beforeSnapshot = null,
        afterSnapshot = "固定非用户候选快照",
        beforeArtifactSha256 = null,
        afterArtifactSha256 = POLICY_ARTIFACT,
        reasonCode = LearningPolicyRevisionReason.CREATE.name,
        actor = LearningPolicyRevisionActor.SYSTEM.name,
        createdAtMs = 5L,
    )

    fun policyEvidence() = PolicyEvidenceEntity(
        policyId = POLICY_ID,
        episodeId = EVIDENCE_EPISODE_ID,
        evidenceKind = "LESSON",
        polarity = LearningPolicyEvidencePolarity.POSITIVE.name,
        quality = 1.0,
        lessonVersion = 1,
        sourceType = LearningSourceKind.CONVERSATION_MESSAGE.name,
        sourceId = EVIDENCE_MESSAGE_ID,
        sourceRevision = 1L,
        sourceIntegritySha256 = MESSAGE_INTEGRITY,
        createdAtMs = 5L,
    )

    fun policyRewardEvidence() = PolicyRewardEvidenceEntity(
        policyId = POLICY_ID,
        episodeId = EVIDENCE_EPISODE_ID,
        rewardSignalId = REWARD_SIGNAL_ID,
        sourceType = LearningSourceKind.USER_FEEDBACK.name,
        sourceId = REWARD_SOURCE_ID,
        sourceRevision = 1L,
        sourceIntegritySha256 = REWARD_INTEGRITY,
        createdAtMs = 5L,
    )

    fun exposureMetadata() = PolicyExposureMetadata(
        replayGeneration = 0L,
        scope = SCOPE,
        taskSignature = TASK_SIGNATURE.value,
        treatmentArm = "P5_FIXED_EXPOSED",
        modelIdentity = MODEL_ID,
        providerIdentity = PROVIDER_ID,
        providerGeneration = 1L,
        toolsetFingerprint = "8".repeat(64),
        contextCompilerAbi = RECALL_PROMPT_COMPILER_REVISION,
    )
}

private fun requireBoundary(
    condition: Boolean,
    reason: ProductionRoomIntegrationReason,
) {
    if (!condition) throw P5RoomSemanticFailure(reason)
}

private class P5RoomSemanticFailure(
    val reason: ProductionRoomIntegrationReason,
) : RuntimeException(null, null, false, false)
