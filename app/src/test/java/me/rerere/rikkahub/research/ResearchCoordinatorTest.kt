package me.rerere.rikkahub.research

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.ToolNameSnapshot
import me.rerere.rikkahub.subagent.SubAgentCallerContext
import me.rerere.rikkahub.subagent.SubAgentRequest
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ResearchCoordinatorTest {
    @Test
    fun `invalid plan and unavailable read tools are rejected before dispatch`() = runBlocking {
        val gateway = FakeResearchChildGateway()
        val coordinator = ResearchCoordinator(gateway, this, ResearchCompletionNotifier {})
        val caller = caller(RESEARCH_READ_ONLY_TOOLS - "web_fetch")

        val badCount = coordinator.start(
            caller,
            ResearchStartRequest("topic", listOf(ResearchSubtask("one", "only one"))),
        ) as ResearchStartResult.Rejected
        assertEquals("invalid_subtask_count", badCount.error)

        val missingTool = coordinator.start(
            caller,
            ResearchStartRequest(
                "topic",
                listOf(ResearchSubtask("one", "first"), ResearchSubtask("two", "second")),
            ),
        ) as ResearchStartResult.Rejected
        assertEquals("research_tools_unavailable", missingTool.error)
        assertTrue(gateway.dispatches.isEmpty())
    }

    @Test
    fun `status and cancellation are owner scoped and cancel every child`() = runBlocking {
        val gateway = FakeResearchChildGateway()
        val notifications = mutableListOf<ResearchRun>()
        val coordinator = ResearchCoordinator(
            gateway,
            this,
            ResearchCompletionNotifier { notifications += it },
        )
        val caller = caller(RESEARCH_READ_ONLY_TOOLS)
        val started = coordinator.start(
            caller,
            ResearchStartRequest(
                "topic",
                listOf(ResearchSubtask("one", "first"), ResearchSubtask("two", "second")),
            ),
        ) as ResearchStartResult.Started

        assertEquals(null, coordinator.status("another-assistant", started.run.id))
        assertEquals(0, coordinator.cancel("another-assistant", started.run.id))
        assertEquals(2, coordinator.cancel(caller.parentAssistantId, started.run.id))
        assertEquals(setOf("child-1", "child-2"), gateway.cancelled.toSet())
        assertEquals(
            ResearchStatus.CANCELLED,
            coordinator.status(caller.parentAssistantId, started.run.id)?.status,
        )
        gateway.terminal.complete(
            mapOf(
                "child-1" to childRun("child-1", SubAgentStatus.CANCELLED),
                "child-2" to childRun("child-2", SubAgentStatus.CANCELLED),
            ),
        )
        yield()
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `emergency cancellation stops every active research graph without completion notifications`() = runBlocking {
        val gateway = FakeResearchChildGateway()
        val notifications = mutableListOf<ResearchRun>()
        val coordinator = ResearchCoordinator(
            gateway,
            this,
            ResearchCompletionNotifier { notifications += it },
        )
        val first = coordinator.start(
            caller(RESEARCH_READ_ONLY_TOOLS),
            ResearchStartRequest(
                "first",
                listOf(ResearchSubtask("one", "first one"), ResearchSubtask("two", "first two")),
            ),
        ) as ResearchStartResult.Started
        val second = coordinator.start(
            caller(RESEARCH_READ_ONLY_TOOLS),
            ResearchStartRequest(
                "second",
                listOf(ResearchSubtask("one", "second one"), ResearchSubtask("two", "second two")),
            ),
        ) as ResearchStartResult.Started

        assertEquals(2, coordinator.cancelAllActive())
        assertEquals(
            setOf("child-1", "child-2", "child-3", "child-4"),
            gateway.cancelled.toSet(),
        )
        assertEquals(ResearchStatus.CANCELLED, coordinator.status(first.run.ownerAssistantId, first.run.id)?.status)
        assertEquals(ResearchStatus.CANCELLED, coordinator.status(second.run.ownerAssistantId, second.run.id)?.status)

        gateway.terminal.complete(
            (1..4).associate { index ->
                "child-$index" to childRun("child-$index", SubAgentStatus.CANCELLED)
            },
        )
        yield()
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `emergency cancellation cannot be overwritten by an in flight child dispatch`() = runBlocking {
        val gateway = FakeResearchChildGateway().apply {
            dispatchGate = CompletableDeferred()
        }
        val coordinator = ResearchCoordinator(gateway, this, ResearchCompletionNotifier {})
        val owner = caller(RESEARCH_READ_ONLY_TOOLS)
        val startDeferred = async {
            coordinator.start(
                owner,
                ResearchStartRequest(
                    "race",
                    listOf(ResearchSubtask("one", "first"), ResearchSubtask("two", "second")),
                ),
            ) as ResearchStartResult.Started
        }

        gateway.dispatchStarted.await()
        assertEquals(1, coordinator.cancelAllActive())
        gateway.dispatchGate?.complete(Unit)
        val returned = startDeferred.await()
        val returnedStatus = returned.run.status
        gateway.terminal.complete(
            mapOf("child-1" to childRun("child-1", SubAgentStatus.CANCELLED)),
        )
        yield()

        assertEquals(ResearchStatus.CANCELLED, returnedStatus)
        assertEquals(1, gateway.dispatches.size)
        assertEquals(setOf("child-1"), gateway.cancelled.toSet())
        assertEquals(ResearchStatus.CANCELLED, coordinator.status(owner.parentAssistantId, returned.run.id)?.status)
    }

    @Test
    fun `cancelling research start compensates children that were already dispatched`() = runBlocking {
        val gateway = FakeResearchChildGateway().apply {
            cancelOnDispatchNumber = 2
        }
        val notifications = mutableListOf<ResearchRun>()
        val coordinator = ResearchCoordinator(
            gateway,
            this,
            ResearchCompletionNotifier { notifications += it },
        )
        val owner = caller(RESEARCH_READ_ONLY_TOOLS)

        try {
            coordinator.start(
                owner,
                ResearchStartRequest(
                    "cancelled start",
                    listOf(ResearchSubtask("one", "first"), ResearchSubtask("two", "second")),
                ),
            )
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Parent /stop still propagates after coordinator compensation.
        }

        val cancelled = coordinator.list(owner.parentAssistantId).single()
        assertEquals(ResearchStatus.CANCELLED, cancelled.status)
        assertEquals(setOf("child-1"), gateway.cancelled.toSet())
        assertEquals(SubAgentStatus.CANCELLED, cancelled.children.single().status)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `two bounded read only children produce one partial coordinator completion`() = runBlocking {
        val gateway = FakeResearchChildGateway()
        val notifications = mutableListOf<ResearchRun>()
        val coordinator = ResearchCoordinator(
            childGateway = gateway,
            scope = this,
            completionNotifier = ResearchCompletionNotifier { notifications += it },
        )
        val caller = SubAgentCallerContext(
            parentAssistantId = Uuid.random().toString(),
            parentConversationId = Uuid.random().toString(),
            parentEffectiveModelId = Uuid.random(),
            toolNames = ToolNameSnapshot(
                available = RESEARCH_READ_ONLY_TOOLS,
                known = RESEARCH_READ_ONLY_TOOLS,
            ),
        )

        val started = coordinator.start(
            caller = caller,
            request = ResearchStartRequest(
                topic = "Compare two sources",
                subtasks = listOf(
                    ResearchSubtask("official", "Find the official specification"),
                    ResearchSubtask("paper", "Find the primary paper"),
                ),
                maxTrips = 4,
            ),
        ) as ResearchStartResult.Started

        assertEquals(2, gateway.dispatches.size)
        gateway.dispatches.forEach { dispatch ->
            assertEquals(RESEARCH_READ_ONLY_TOOLS, dispatch.request.tools?.toSet())
            assertEquals(4, dispatch.request.maxTrips)
            assertTrue(dispatch.request.runInBackground)
            assertEquals(
                me.rerere.rikkahub.subagent.SubAgentParentCompletionPolicy.COORDINATOR_ONLY,
                dispatch.caller.completionPolicy,
            )
        }

        gateway.terminal.complete(
            mapOf(
                "child-1" to childRun("child-1", SubAgentStatus.SUCCEEDED, "official result"),
                "child-2" to childRun("child-2", SubAgentStatus.FAILED, error = "network"),
            ),
        )
        yield()

        val finished = coordinator.status(caller.parentAssistantId, started.run.id)!!
        assertEquals(ResearchStatus.PARTIAL, finished.status)
        assertEquals(1, notifications.size)
        assertEquals(finished.id, notifications.single().id)
    }

    @Test
    fun `completion payload deduplicates sources and bounds every child report`() {
        val repeatedUrl = "https://example.com/primary"
        val run = ResearchRun(
            id = "research-1",
            ownerAssistantId = "assistant",
            parentConversationId = "conversation",
            topic = "bounded payload",
            children = listOf(
                ResearchChild(
                    label = "one",
                    task = "first",
                    subAgentRunId = "child-1",
                    status = SubAgentStatus.SUCCEEDED,
                    result = "$repeatedUrl\n${"x".repeat(5_000)}",
                ),
                ResearchChild(
                    label = "two",
                    task = "second",
                    subAgentRunId = "child-2",
                    status = SubAgentStatus.SUCCEEDED,
                    result = "same source: $repeatedUrl.",
                ),
            ),
            status = ResearchStatus.SUCCEEDED,
            startedAtMs = 1L,
            finishedAtMs = 2L,
        )

        val payload = Json.parseToJsonElement(
            buildResearchCompletionMessage(run).substringAfter('\n'),
        ).jsonObject

        assertEquals(
            listOf(repeatedUrl),
            payload.getValue("source_urls").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            4_000,
            payload.getValue("subtasks").jsonArray.first().jsonObject
                .getValue("report").jsonPrimitive.content.length,
        )
    }

    private fun caller(availableTools: Set<String>) = SubAgentCallerContext(
        parentAssistantId = Uuid.random().toString(),
        parentConversationId = Uuid.random().toString(),
        parentEffectiveModelId = Uuid.random(),
        toolNames = ToolNameSnapshot(availableTools, RESEARCH_READ_ONLY_TOOLS),
    )

    private fun childRun(
        id: String,
        status: SubAgentStatus,
        result: String? = null,
        error: String? = null,
    ) = SubAgentRun(
        id = id,
        parentChatId = null,
        parentAssistantId = "assistant",
        label = id,
        task = id,
        modelId = null,
        tools = RESEARCH_READ_ONLY_TOOLS.toList(),
        runInBackground = true,
        timeoutSeconds = 60,
        maxTrips = 4,
        status = status,
        result = result,
        error = error,
        startedAtMs = 1L,
        finishedAtMs = 2L,
    )

    private class FakeResearchChildGateway : ResearchChildGateway {
        data class Dispatch(
            val caller: SubAgentCallerContext,
            val request: SubAgentRequest,
        )

        val dispatches = mutableListOf<Dispatch>()
        val terminal = CompletableDeferred<Map<String, SubAgentRun>>()
        val cancelled = mutableListOf<String>()
        val dispatchStarted = CompletableDeferred<Unit>()
        var dispatchGate: CompletableDeferred<Unit>? = null
        var cancelOnDispatchNumber: Int? = null

        override suspend fun dispatch(
            caller: SubAgentCallerContext,
            request: SubAgentRequest,
        ): ResearchChildDispatchResult {
            dispatches += Dispatch(caller, request)
            dispatchStarted.complete(Unit)
            dispatchGate?.await()
            if (dispatches.size == cancelOnDispatchNumber) {
                throw CancellationException("parent stopped research_start")
            }
            return ResearchChildDispatchResult.Started("child-${dispatches.size}")
        }

        override suspend fun awaitTerminal(runIds: Set<String>): Map<String, SubAgentRun> =
            terminal.await()

        override fun cancel(ownerAssistantId: String, runId: String): Boolean {
            cancelled += runId
            return true
        }
    }
}
