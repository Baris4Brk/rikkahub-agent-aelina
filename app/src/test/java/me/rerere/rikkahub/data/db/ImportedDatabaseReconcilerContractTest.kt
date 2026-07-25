package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedDatabaseReconcilerContractTest {
    @Test
    fun `reconciler current version and identity match Room schema 35`() {
        assertEquals(35, ImportedDatabaseReconciler.EXPECTED_VERSION)
        assertEquals(
            "53663bb60a5992f648e8aa97364d1b07",
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
    fun `current v35 skips raw framework SQLite reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.SKIP,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 35,
                identityHash = "53663bb60a5992f648e8aa97364d1b07",
            ),
        )
    }

    @Test
    fun `unknown current schema still uses compatibility reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(version = 35, identityHash = "upstream"),
        )
    }
}
