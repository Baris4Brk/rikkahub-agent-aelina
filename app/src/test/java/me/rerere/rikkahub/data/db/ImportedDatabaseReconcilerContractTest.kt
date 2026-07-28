package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedDatabaseReconcilerContractTest {
    @Test
    fun `reconciler current version and identity match Room schema 37`() {
        assertEquals(37, ImportedDatabaseReconciler.EXPECTED_VERSION)
        assertEquals(
            "8bf48c5fd55eef1331c5b5cf043eac5b",
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
    fun `current v37 skips raw framework SQLite reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.SKIP,
            ImportedDatabaseReconciler.reconcilePlan(
                version = 37,
                identityHash = "8bf48c5fd55eef1331c5b5cf043eac5b",
            ),
        )
    }

    @Test
    fun `unknown current schema still uses compatibility reconciliation`() {
        assertEquals(
            ImportedDatabaseReconciler.ReconcilePlan.FULL_COMPATIBILITY,
            ImportedDatabaseReconciler.reconcilePlan(version = 37, identityHash = "upstream"),
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
