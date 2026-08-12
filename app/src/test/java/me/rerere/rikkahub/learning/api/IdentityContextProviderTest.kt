package me.rerere.rikkahub.learning.api

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.adapters.DreamingIdentityAdapter
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IdentityContextProviderTest {
    @Test
    fun defaultNoOpIsExplicitlyUnavailable() = runBlocking {
        assertEquals(
            IdentityContextResult.Unavailable(IdentityContextUnavailableReason.DISABLED),
            NoOpIdentityContextProvider.queryRelevantIdentity(request()),
        )
    }

    @Test
    fun dreamingAdapterDoesNotGuessFromInternalTables() = runBlocking {
        assertEquals(
            IdentityContextResult.Unavailable(
                IdentityContextUnavailableReason.PUBLIC_READ_API_UNAVAILABLE,
            ),
            DreamingIdentityAdapter.queryRelevantIdentity(request()),
        )
    }

    @Test
    fun requestAndProvidersDoNotLeakScopeOrTaskIdentifiersInToString() {
        val request = request()
        assertFalse(request.toString().contains(ASSISTANT_ID.toString()))
        assertFalse(request.toString().contains(TASK_SIGNATURE.value))
        assertFalse(NoOpIdentityContextProvider.toString().contains(ASSISTANT_ID.toString()))
        assertFalse(DreamingIdentityAdapter.toString().contains(ASSISTANT_ID.toString()))
    }

    private fun request() = IdentityContextRequest(
        expectedScope = LearningScope.Assistant(ASSISTANT_ID),
        taskSignature = TASK_SIGNATURE,
        budget = IdentityContextBudget(maxItems = 4, maxChars = 1_024),
    )

    private companion object {
        val ASSISTANT_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000042")
        val TASK_SIGNATURE: TaskSignatureV1 = checkNotNull(
            TaskSignatureV1.parseOrNull("task-signature-v1:${"a".repeat(64)}"),
        )
    }
}
