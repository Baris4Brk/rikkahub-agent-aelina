package me.rerere.rikkahub.learning.storage

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePrivacySqlContractTest {
    @Test
    fun staleLessonQueriesEraseEveryDerivedTextFieldAndReplaceArtifactIdentity() {
        val source = File(
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningEpisodeDao.kt",
        ).readText()
        val sections = listOf(
            queryImmediatelyBefore(source, "suspend fun markLessonsStaleForSource"),
            queryImmediatelyBefore(source, "suspend fun markLessonsStaleWithInvalidSource"),
        )
        sections.forEach { sql ->
            listOf(
                "trigger_summary = '[SOURCE_ERASED]'",
                "observation_summary = '[SOURCE_ERASED]'",
                "lesson_summary = '[SOURCE_ERASED]'",
                "boundary_summary = '[SOURCE_ERASED]'",
                "state = 'STALE_SOURCE'",
                ERASED_ARTIFACT,
            ).forEach { required -> assertTrue("missing $required", required in sql) }
        }
    }

    @Test
    fun erasedArtifactIsTheFrozenMarkerDigest() {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest("learning-lesson-source-erased-v1".toByteArray())
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        assertEquals(ERASED_ARTIFACT, actual)
    }

    private companion object {
        const val ERASED_ARTIFACT =
            "4337a7cc59142919cbbb5af77323269e33cdc79e68e85aa289571c1af2136143"
    }

    private fun queryImmediatelyBefore(source: String, signature: String): String =
        source.substringBefore(signature).substringAfterLast("@Query(")
}
