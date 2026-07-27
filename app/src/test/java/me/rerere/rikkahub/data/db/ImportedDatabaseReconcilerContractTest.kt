package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedDatabaseReconcilerContractTest {
    @Test
    fun `reconciler current version and identity match Room schema 36`() {
        assertEquals(36, ImportedDatabaseReconciler.EXPECTED_VERSION)
        assertEquals(
            "efb4f396f5f1d0fcce7479fbf5ef9238",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
        )
    }

    @Test
    fun `installed pre-storage v35 uses the narrow same-version delta`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.CURRENT_V35_DELTA,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 35,
                identityHash = "2a74d694211f0df9f9094c7571ec71dd",
            ),
        )
    }

    @Test
    fun `current v36 skips raw framework SQLite reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.SKIP,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 36,
                identityHash = "efb4f396f5f1d0fcce7479fbf5ef9238",
            ),
        )
    }

    @Test
    fun `unknown current schema still uses compatibility reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(version = 36, identityHash = "upstream"),
        )
    }
}
