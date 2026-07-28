package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedDatabaseReconcilerContractTest {
    @Test
    fun `reconciler current version and identity match Room schema 38`() {
        assertEquals(38, ImportedDatabaseReconciler.EXPECTED_VERSION)
        assertEquals(
            "70e847d8db457399fbc4fbc9c21a41b0",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
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
    fun `current v38 skips raw framework SQLite reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.SKIP,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 38,
                identityHash = "70e847d8db457399fbc4fbc9c21a41b0",
            ),
        )
    }

    @Test
    fun `unknown current schema still uses compatibility reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(version = 38, identityHash = "upstream"),
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
