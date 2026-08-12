package me.rerere.rikkahub.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedDatabaseReconcilerContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reconciler remains pinned to unpublished Room version 46`() {
        assertEquals(46, ImportedDatabaseReconciler.EXPECTED_VERSION)
        assertTrue(
            "final P1 identity must be copied from Room's exported v46 schema",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH.matches(Regex("[0-9a-f]{32}")),
        )
        assertNotEquals(
            "102b6a6fc51154abdac792d133d461a3",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
        )
        assertEquals(
            "102b6a6fc51154abdac792d133d461a3",
            ImportedDatabaseReconciler.PRE_P1_V46_IDENTITY_HASH,
        )
        assertEquals(
            "8ef3ddc71d855013202bb11b0493d6e6",
            ImportedDatabaseReconciler.PRE_LEARNING_V46_IDENTITY_HASH,
        )
    }

    @Test
    fun `installed pre-storage v35 now follows normal migrations`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 35,
                identityHash = "2a74d694211f0df9f9094c7571ec71dd",
            ),
        )
    }

    @Test
    fun `exact P0 v46 receives only the P1 delta`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.CURRENT_V46_P1_DELTA,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 46,
                identityHash = "102b6a6fc51154abdac792d133d461a3",
            ),
        )
    }

    @Test
    fun `exact pre-learning v46 receives the contiguous P0 plus P1 delta`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.CURRENT_V46_P0_P1_DELTA,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 46,
                identityHash = "8ef3ddc71d855013202bb11b0493d6e6",
            ),
        )
    }

    @Test
    fun `unknown current schema is refused and never compatibility stamped`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.REFUSE_UNKNOWN_CURRENT,
            ImportedDatabaseReconciler.reconcilePlan(version = 46, identityHash = "upstream"),
        )
    }

    @Test
    fun `staged restore gate throws for unknown current and newer databases`() {
        assertThrows(IllegalStateException::class.java) {
            ImportedDatabaseReconciler.stagedReconcilePlanOrThrow(
                version = ImportedDatabaseReconciler.EXPECTED_VERSION,
                identityHash = "unknown",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            ImportedDatabaseReconciler.stagedReconcilePlanOrThrow(
                version = ImportedDatabaseReconciler.EXPECTED_VERSION + 1,
                identityHash = null,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            ImportedDatabaseReconciler.stagedReconcilePlanOrThrow(
                version = ImportedDatabaseReconciler.EXPECTED_VERSION - 1,
                identityHash = null,
            )
        }
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.SKIP,
            ImportedDatabaseReconciler.stagedReconcilePlanOrThrow(
                version = ImportedDatabaseReconciler.EXPECTED_VERSION,
                identityHash = ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            ImportedDatabaseReconciler.stagedReconcilePlanOrThrow(
                version = ImportedDatabaseReconciler.EXPECTED_VERSION,
                identityHash = ImportedDatabaseReconciler.PRE_LEARNING_V46_IDENTITY_HASH,
            )
        }
    }

    @Test
    fun `staged file API refuses a non-private candidate before opening SQLite`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportedDatabaseReconciler.reconcileStagedFileOrThrow(
                databaseFile = File("rikka_hub"),
                expectedStreamId = "00000000-0000-0000-0000-000000000001",
                expectedHeadSeq = 1L,
            )
        }
    }

    @Test
    fun `installed and staged validators have disjoint exact filename contracts`() {
        val databases = File(temporaryFolder.newFolder("restore-target"), "databases")
            .apply { check(mkdir()) }
        val installed = File(databases, "rikka_hub").apply { writeText("not sqlite") }
        val staged = File(
            databases,
            ".rikka_hub.restore_0123456789abcdef0123456789abcdef.ready",
        ).apply { writeText("not sqlite") }
        val streamId = "00000000-0000-0000-0000-000000000001"

        assertThrows(IllegalStateException::class.java) {
            ImportedDatabaseReconciler.validateInstalledFileOrThrow(
                databaseFile = staged,
                expectedStreamId = streamId,
                expectedHeadSeq = 1L,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            ImportedDatabaseReconciler.validateStagedFileOrThrow(
                databaseFile = installed,
                expectedStreamId = streamId,
                expectedHeadSeq = 1L,
            )
        }
    }

    @Test
    fun `final v46 identity skips raw framework reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.SKIP,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 46,
                identityHash = ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
            ),
        )
    }

    @Test
    fun `previous v45 is reconciled before Room installs synthesis tables`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 45,
                identityHash = "1d710a3e2caa9b79a2f7eb0c94bb3d6a",
            ),
        )
    }

    @Test
    fun `previous v44 is reconciled before Room installs observer tables`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 44,
                identityHash = "1c505f7d10b206eaa420365150f2eb7d",
            ),
        )
    }

    @Test
    fun `previous v43 is reconciled before Room applies source identity migration`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 43,
                identityHash = "993e84f6266c165ffd196d30acb1969d",
            ),
        )
    }

    @Test
    fun `previous v39 is reconciled before Room creates tool experience tables`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 39,
                identityHash = "98db479b6258269f2aef18ce15e0f2f9",
            ),
        )
    }

    @Test
    fun `previous v40 is reconciled before Room creates fast lane shortcuts`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 40,
                identityHash = "bf0cc9fcad994a73ac34982cf526e2ce",
            ),
        )
    }

    @Test
    fun `previous P0 v37 receives requested terminal outcome compatibility column`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 37,
                identityHash = "8cb20e594bfefae355191428fcd7ca9a",
            ),
        )
    }
}
