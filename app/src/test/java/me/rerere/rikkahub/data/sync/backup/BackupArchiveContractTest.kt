package me.rerere.rikkahub.data.sync.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveContractTest {
    @Test
    fun validManifestRoundTripsWithStrictContentFreeContract() {
        val manifest = validManifest()

        val encoded = BackupArchiveManifestCodec.encode(manifest)
        val decoded = BackupArchiveManifestCodec.decode(encoded)

        assertTrue(decoded is BackupArchiveManifestDecodeResult.Verified)
        decoded as BackupArchiveManifestDecodeResult.Verified
        assertEquals(manifest, decoded.manifest)
        assertTrue(encoded.size <= 1_048_576)
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"learningDbExcluded\":true"))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"mainStream\""))
    }

    @Test
    fun securityCriticalFieldsHaveNoPermissiveSerializationDefaults() {
        val missingExclusion = """
            {
              "formatVersion": 1,
              "components": ["DATABASE"],
              "mainStream": {"streamId":"00000000-0000-0000-0000-000000000001","headSeq":1},
              "entries": {"rikka_hub.db":{"size":512,"sha256":"${"a".repeat(64)}"}}
            }
        """.trimIndent().toByteArray()

        assertEquals(
            BackupArchiveManifestDecodeResult.Rejected(
                BackupArchiveManifestFailure.MALFORMED_JSON,
            ),
            BackupArchiveManifestCodec.decode(missingExclusion),
        )
    }

    @Test
    fun unknownJsonFieldFailsClosed() {
        val raw = BackupArchiveManifestCodec.encode(validManifest())
            .toString(Charsets.UTF_8)
            .replaceFirst("{", "{\"unexpected\":true,")

        assertEquals(
            BackupArchiveManifestDecodeResult.Rejected(
                BackupArchiveManifestFailure.MALFORMED_JSON,
            ),
            BackupArchiveManifestCodec.decode(raw.toByteArray()),
        )
    }

    @Test
    fun databaseSelectionRequiresCanonicalAuthorityStream() {
        val result = BackupArchiveManifestCodec.validate(validManifest().copy(mainStream = null))

        assertEquals(BackupArchiveManifestFailure.MAIN_STREAM_MISSING, result)
    }

    @Test
    fun traversalAndLearningDatabaseEntriesAreRejected() {
        val traversal = validManifest().copy(
            entries = validManifest().entries +
                ("skills/demo/../../learning_runtime.db" to entry("b")),
        )
        val learningDatabase = validManifest().copy(
            entries = validManifest().entries + ("learning_runtime.db" to entry("c")),
        )

        assertEquals(
            BackupArchiveManifestFailure.UNSAFE_ENTRY_NAME,
            BackupArchiveManifestCodec.validate(traversal),
        )
        assertEquals(
            BackupArchiveManifestFailure.UNSUPPORTED_ENTRY,
            BackupArchiveManifestCodec.validate(learningDatabase),
        )
    }

    private fun validManifest() = BackupArchiveManifestV1(
        formatVersion = BACKUP_ARCHIVE_FORMAT_VERSION,
        learningDbExcluded = true,
        components = listOf(
            BackupArchiveComponent.DATABASE,
            BackupArchiveComponent.SETTINGS,
        ),
        mainStream = BackupAuthorityStreamV1(
            streamId = "00000000-0000-0000-0000-000000000001",
            headSeq = 7L,
        ),
        entries = linkedMapOf(
            BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY to BackupArchiveEntryV1(
                size = 512L,
                sha256 = "a".repeat(64),
            ),
            BACKUP_ARCHIVE_SETTINGS_ENTRY to BackupArchiveEntryV1(
                size = 32L,
                sha256 = "b".repeat(64),
            ),
        ),
    )

    private fun entry(hexCharacter: String) = BackupArchiveEntryV1(
        size = 1L,
        sha256 = hexCharacter.repeat(64),
    )
}
