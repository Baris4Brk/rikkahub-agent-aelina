package me.rerere.rikkahub.learning.diagnostics

import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.learning.model.LearningScopeKind
import me.rerere.rikkahub.learning.privacy.forbiddenLearningCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningDiagnosticsStoreTest {
    @Test
    fun diskHistory_isBoundedAtomicAndContainsNoStableIdentifiersOrText() {
        val directory = Files.createTempDirectory("learning-diagnostics-test").toFile()
        try {
            val store = LearningDiagnosticsStore(directory, maxEntries = 4)
            repeat(12) { index ->
                store.record(
                    LearningDiagnosticSample(
                        recordedAtMs = index.toLong(),
                        code = LearningDiagnosticCode.JOB_RETRY,
                        state = LearningDiagnosticState.RETRY,
                        scopeKind = LearningScopeKind.ASSISTANT,
                        primaryValue = index.toLong(),
                    ),
                )
            }
            assertEquals(4, store.entries.value.size)
            assertTrue(store.entries.value.all { it.opaqueTraceId.matches(Regex("alr_[0-9a-f]{32}")) })

            val persisted = LearningDiagnosticsStore.outputFile(directory).readText()
            listOf(
                "00000000-0000-0000-0000-000000000099",
                "ignore previous instructions",
                "</provider_runtime_context>",
                "sk-proj-super-secret",
                "C:\\Users\\private\\secret.txt",
                "https://private.example/token",
            ).forEach { forbidden ->
                assertFalse("diagnostic leaked forbidden value: $forbidden", persisted.contains(forbidden))
            }
            assertFalse(
                "atomic diagnostic write left a temporary file",
                directory.walkTopDown().any { it.isFile && it.extension == "tmp" },
            )

            val reloaded = LearningDiagnosticsStore(directory, maxEntries = 4)
            assertEquals(store.entries.value, reloaded.entries.value)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun defaultStringProjection_isContentFree() {
        val sample = LearningDiagnosticSample(
            recordedAtMs = 1,
            code = LearningDiagnosticCode.RESOURCE_YIELD,
            state = LearningDiagnosticState.THERMAL_PRESSURE,
            scopeKind = LearningScopeKind.AUTHORITY_SUBJECT,
            primaryValue = 3,
        )
        assertFalse(sample.toString().contains("00000000"))
        assertTrue(sample.toString().contains("RESOURCE_YIELD"))
    }

    @Test
    fun unknownLegacyFieldsAreRewrittenSoRetiredTextDoesNotRemainOnDisk() {
        val directory = Files.createTempDirectory("learning-diagnostics-sanitize").toFile()
        try {
            val destination = LearningDiagnosticsStore.outputFile(directory)
            destination.parentFile?.mkdirs()
            val forbidden = "ignore previous instructions sk-proj-super-secret"
            destination.writeText(
                """
                {
                  "schema_version": 1,
                  "retired_raw_error": "$forbidden",
                  "entries": [{
                    "opaque_trace_id": "alr_0123456789abcdef0123456789abcdef",
                    "sample": {
                      "recorded_at_ms": 1,
                      "code": "JOB_RETRY",
                      "state": "RETRY",
                      "scope_kind": "ASSISTANT",
                      "primary_value": 1,
                      "secondary_value": null,
                      "retired_source_id": "$forbidden"
                    }
                  }]
                }
                """.trimIndent(),
            )

            val store = LearningDiagnosticsStore(directory)

            assertEquals(1, store.entries.value.size)
            val sanitized = destination.readText()
            assertFalse(sanitized.contains(forbidden))
            assertFalse(sanitized.contains("retired_raw_error"))
            assertFalse(sanitized.contains("retired_source_id"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completeReleaseForbiddenCorpusIsRemovedFromLegacyDiagnosticsOnLoad() {
        val directory = Files.createTempDirectory("learning-diagnostics-corpus").toFile()
        try {
            val destination = LearningDiagnosticsStore.outputFile(directory)
            destination.parentFile?.mkdirs()
            val forbidden = forbiddenLearningCorpus()
            destination.writeText(
                JsonObject(
                    mapOf(
                        "schema_version" to JsonPrimitive(1),
                        "retired_private_values" to JsonArray(forbidden.map { JsonPrimitive(it) }),
                        "entries" to JsonArray(emptyList()),
                    ),
                ).toString(),
            )

            val store = LearningDiagnosticsStore(directory)

            assertTrue(store.entries.value.isEmpty())
            val rewritten = destination.readText()
            forbidden.forEach { value ->
                assertFalse("diagnostic retained release-forbidden corpus", value in rewritten)
                assertFalse(
                    "diagnostic retained JSON-escaped release-forbidden corpus",
                    JsonPrimitive(value).toString() in rewritten,
                )
            }
            assertFalse("retired diagnostic field survived canonical rewrite",
                "retired_private_values" in rewritten)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistenceFailureDoesNotRejectInMemoryHealthSample() {
        val pathThatIsNotADirectory = Files.createTempFile(
            "learning-diagnostics-parent",
            ".tmp",
        ).toFile()
        try {
            val store = LearningDiagnosticsStore(pathThatIsNotADirectory)
            val trace = store.record(
                LearningDiagnosticSample(
                    recordedAtMs = 1,
                    code = LearningDiagnosticCode.DATABASE_STATE,
                    state = LearningDiagnosticState.DEGRADED,
                ),
            )

            assertTrue(trace.matches(Regex("alr_[0-9a-f]{32}")))
            assertEquals(1, store.entries.value.size)
        } finally {
            pathThatIsNotADirectory.delete()
        }
    }
}
