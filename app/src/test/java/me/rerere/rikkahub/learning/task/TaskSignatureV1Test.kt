package me.rerere.rikkahub.learning.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSignatureV1Test {
    @Test
    fun canonicalFeatureOrderIsStableAndPromptTextIsNotAnInput() {
        val a = LearningToolSignature("memory.query", "a".repeat(64))
        val b = LearningToolSignature("file.read", "b".repeat(64))
        val first = TaskSignatureV1.create(
            LearningTaskClass.INFORMATION,
            LearningLanguageClass.CHINESE,
            LearningModalityClass.TEXT_ONLY,
            linkedSetOf(a, b),
        )
        val second = TaskSignatureV1.create(
            LearningTaskClass.INFORMATION,
            LearningLanguageClass.CHINESE,
            LearningModalityClass.TEXT_ONLY,
            linkedSetOf(b, a),
        )
        assertEquals(first, second)
        assertTrue(TaskSignatureV1.parseOrNull(first.value) == first)
        assertFalse(first.toString().contains(first.value))
    }

    @Test
    fun schemaAndVersionedFeaturesChangeTheSignature() {
        val baseline = TaskSignatureV1.create(
            LearningTaskClass.CODE_CHANGE,
            LearningLanguageClass.CHINESE,
            LearningModalityClass.TEXT_ONLY,
            setOf(LearningToolSignature("file.patch", "a".repeat(64))),
        )
        val changedSchema = TaskSignatureV1.create(
            LearningTaskClass.CODE_CHANGE,
            LearningLanguageClass.CHINESE,
            LearningModalityClass.TEXT_ONLY,
            setOf(LearningToolSignature("file.patch", "b".repeat(64))),
        )
        assertNotEquals(baseline, changedSchema)
        assertNull(TaskSignatureV1.parseOrNull("task-signature-v2:${"a".repeat(64)}"))
    }
}
