package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedDatabaseReconcilerContractTest {
    @Test
    fun `reconciler current version and identity match Room schema 34`() {
        assertEquals(34, ImportedDatabaseReconciler.EXPECTED_VERSION)
        assertEquals(
            "48ec748cc533a47fb0dbcd3431c17ebe",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
        )
    }
}
