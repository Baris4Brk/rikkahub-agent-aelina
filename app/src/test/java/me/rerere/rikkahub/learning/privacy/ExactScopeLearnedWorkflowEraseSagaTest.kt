package me.rerere.rikkahub.learning.privacy

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactScopeLearnedWorkflowEraseSagaTest {
    @Test
    fun pagesAreBoundedAndAppDatabaseIsCompletelyFencedBeforeSuccess() = runBlocking {
        val ids = candidateIds(3)
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        val cursors = mutableListOf<String>()
        val source = ExactScopeLearnedWorkflowCandidatePageSource { actualScope, cursor, limit ->
            assertEquals(scope, actualScope)
            assertEquals(2, limit)
            cursors += cursor
            ids.filter { it > cursor }.take(limit)
        }
        val pages = mutableListOf<List<String>>()
        val port = ExactScopeLearnedWorkflowErasePort { page, nowMs ->
            assertEquals(42L, nowMs)
            pages += page
            ExactScopeLearnedWorkflowEraseBatchReceipt(
                fencedCandidateIds = page.size,
                redactedWorkflowDefinitions = page.count { it != ids[1] },
                insertedFenceClaims = page.count { it == ids[1] },
            )
        }

        val receipt = ExactScopeLearnedWorkflowEraseSaga(source, port, batchSize = 2)
            .fenceBeforeLearningDelete(scope, frozenNowMs = 42L)

        assertEquals(listOf("", ids[1]), cursors)
        assertEquals(listOf(ids.take(2), ids.drop(2)), pages)
        assertEquals(3, receipt.fencedCandidateIds)
        assertEquals(2, receipt.redactedWorkflowDefinitions)
        assertEquals(1, receipt.insertedFenceClaims)
    }

    @Test
    fun crashAfterOnePageReplaysFromTheBeginningWithStableCounts() = runBlocking {
        val ids = candidateIds(3)
        val scope = LearningScope.AuthoritySubject("exact-authority-subject")
        var failSecondPageOnce = true
        val source = ExactScopeLearnedWorkflowCandidatePageSource { _, cursor, limit ->
            if (cursor.isNotEmpty() && failSecondPageOnce) {
                failSecondPageOnce = false
                throw IOException("injected crash")
            }
            ids.filter { it > cursor }.take(limit)
        }
        val originalDefinitions = setOf(ids[0], ids[2])
        val durableMarkers = linkedMapOf<String, Boolean>() // true = definition tombstone
        val visitedPages = mutableListOf<List<String>>()
        val port = ExactScopeLearnedWorkflowErasePort { page, _ ->
            visitedPages += page
            page.forEach { id -> durableMarkers.putIfAbsent(id, id in originalDefinitions) }
            ExactScopeLearnedWorkflowEraseBatchReceipt(
                fencedCandidateIds = page.size,
                redactedWorkflowDefinitions = page.count { durableMarkers.getValue(it) },
                insertedFenceClaims = page.count { !durableMarkers.getValue(it) },
            )
        }
        val saga = ExactScopeLearnedWorkflowEraseSaga(source, port, batchSize = 2)

        val failure = runCatching {
            saga.fenceBeforeLearningDelete(scope, 9L)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        val replay = saga.fenceBeforeLearningDelete(scope, 9L)

        assertEquals(listOf(ids.take(2), ids.take(2), ids.drop(2)), visitedPages)
        assertEquals(3, replay.fencedCandidateIds)
        assertEquals(2, replay.redactedWorkflowDefinitions)
        assertEquals(1, replay.insertedFenceClaims)
    }

    @Test
    fun malformedOrNonMonotonicPageFailsBeforeAnyAppDatabaseMutation() = runBlocking {
        val id = candidateIds(1).single()
        var portCalls = 0
        val saga = ExactScopeLearnedWorkflowEraseSaga(
            candidates = ExactScopeLearnedWorkflowCandidatePageSource { _, _, _ -> listOf(id, id) },
            workflows = ExactScopeLearnedWorkflowErasePort { _, _ ->
                portCalls += 1
                ExactScopeLearnedWorkflowEraseBatchReceipt(0, 0, 0)
            },
            batchSize = 2,
        )

        val failure = runCatching {
            saga.fenceBeforeLearningDelete(
                LearningScope.AuthoritySubject("exact-authority-subject"),
                1L,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, portCalls)
    }

    private fun candidateIds(count: Int): List<String> = (1..count).map { index ->
        "workflow-candidate-v1:${index.toString(16).padStart(64, '0')}"
    }
}
