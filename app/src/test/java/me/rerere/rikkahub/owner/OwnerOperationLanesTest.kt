package me.rerere.rikkahub.owner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerOperationLanesTest {
    private val lanes = OwnerOperationLanes(stripeCount = 257)

    @Test
    fun `different resources are not blocked by a slow operation`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            lanes.withOperation(request("request-one", "provider-a")) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val secondFinished = CompletableDeferred<Unit>()
        val second = async {
            lanes.withOperation(request("request-two", "provider-b")) { secondFinished.complete(Unit) }
        }

        withTimeout(1_000) { secondFinished.await() }
        assertTrue(second.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
    }

    @Test
    fun `same resource writes remain serial`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            lanes.withOperation(request("request-three", "provider-a")) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        var secondEntered = false
        val second = async {
            lanes.withOperation(request("request-four", "provider-a")) { secondEntered = true }
        }
        kotlinx.coroutines.yield()
        assertFalse(secondEntered)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
    }

    @Test
    fun `read only actions use no resource write lane`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            lanes.withOperation(request("request-five", "provider-a", OwnerOperationRisk.READ_ONLY)) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        var secondEntered = false
        val second = async {
            lanes.withOperation(request("request-six", "provider-a", OwnerOperationRisk.READ_ONLY)) { secondEntered = true }
        }
        second.await()
        assertTrue(secondEntered)
        releaseFirst.complete(Unit)
        first.await()
    }

    @Test
    fun `lane key is stable and scoped by authority family and target`() {
        val first = lanes.resourceLaneKey(request("request-seven", "provider-a"))
        val same = lanes.resourceLaneKey(request("request-eight", "provider-a"))
        val other = lanes.resourceLaneKey(request("request-nine", "provider-b"))

        assertEquals(first, same)
        assertFalse(first == other)
    }

    private fun request(
        requestId: String,
        resourceId: String,
        risk: OwnerOperationRisk = OwnerOperationRisk.REVERSIBLE_WRITE,
    ) = OwnerOperationRequest(
        requestId = requestId,
        family = OwnerToolFamily.PROVIDER,
        actions = listOf(
            OwnerAction(
                type = "provider_update",
                arguments = JsonObject(mapOf("provider_id" to JsonPrimitive(resourceId))),
                risk = risk,
            ),
        ),
        authoritySubjectId = "owner-subject",
        authorityEpoch = 1,
        assistantId = "assistant",
        conversationId = "conversation",
        modelId = null,
        providerId = null,
    )
}
