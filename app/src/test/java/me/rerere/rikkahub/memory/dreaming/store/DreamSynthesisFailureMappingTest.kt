package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import org.junit.Assert.assertEquals
import org.junit.Test

class DreamSynthesisFailureMappingTest {
    @Test
    fun `every synthesis failure maps to one stable allowlisted run code`() {
        val expected = mapOf(
            DreamSynthesisFailure.INPUT_REJECTED to DreamRunFailureCode.INPUT_REJECTED,
            DreamSynthesisFailure.MODEL_PERMANENT_FAILURE to DreamRunFailureCode.MODEL_PERMANENT_FAILURE,
            DreamSynthesisFailure.MODEL_AUDIT_MISMATCH to DreamRunFailureCode.MODEL_AUDIT_MISMATCH,
            DreamSynthesisFailure.MODEL_OUTPUT_PARSE_REJECTED to
                DreamRunFailureCode.MODEL_OUTPUT_PARSE_REJECTED,
            DreamSynthesisFailure.MODEL_OUTPUT_VALIDATION_REJECTED to
                DreamRunFailureCode.MODEL_OUTPUT_VALIDATION_REJECTED,
            DreamSynthesisFailure.SNAPSHOT_COMPILATION_FAILED to
                DreamRunFailureCode.SNAPSHOT_COMPILATION_FAILED,
            DreamSynthesisFailure.STORE_FAILURE to DreamRunFailureCode.STORE_FAILURE,
        )

        assertEquals(DreamSynthesisFailure.entries.toSet(), expected.keys)
        DreamSynthesisFailure.entries.forEach { failure ->
            assertEquals(expected.getValue(failure), failure.toRunFailureCode())
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
