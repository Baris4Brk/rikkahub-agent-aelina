package me.rerere.rikkahub.data.ai.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ImplementationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolExecutionPolicyResolverTest {
    private val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant-1",
        callOrigin = ToolCallOrigin.LocalChat,
    )
    private val resolver = DefaultToolExecutionPolicyResolver()

    @Test
    fun `unknown tools fail closed and form a global serial barrier`() {
        val policy = resolver.resolve(
            toolName = "tool_added_without_a_descriptor",
            args = buildJsonObject {},
            context = context,
        )

        assertEquals(setOf(ToolEffect.UNKNOWN), policy.effects)
        assertEquals(ToolConcurrency.GLOBAL_SERIAL, policy.concurrency)
        assertEquals(ToolCancellationCapability.UNKNOWN, policy.cancellationCapability)
        assertFalse(policy.allowReadOnlyParallelBatch)
    }

    @Test
    fun `plugin tools retain unknown effects but form an explicit global serial barrier`() {
        val policy = resolver.resolve(
            toolName = "plugin__0123456789ab__read_status",
            args = buildJsonObject { put("secret", "must-not-be-a-resource-key") },
            context = context,
        )

        assertEquals(setOf(ToolEffect.UNKNOWN), policy.effects)
        assertEquals(ToolConcurrency.GLOBAL_SERIAL, policy.concurrency)
        assertEquals(ToolCancellationCapability.REAL, policy.cancellationCapability)
        assertFalse(policy.allowReadOnlyParallelBatch)
        assertTrue(policy.resourceKeys.single().namespace == "plugin")
        assertFalse(policy.resourceKeys.single().toString().contains("secret"))
    }

    @Test
    fun `web fetch classification depends on the request method`() {
        val getPolicy = resolver.resolve(
            toolName = "web_fetch",
            args = buildJsonObject {
                put("url", "https://example.com/search?q=private")
                put("method", "GET")
            },
            context = context,
        )
        val postPolicy = resolver.resolve(
            toolName = "web_fetch",
            args = buildJsonObject {
                put("url", "https://example.com/account")
                put("method", "POST")
            },
            context = context,
        )

        assertEquals(setOf(ToolEffect.NETWORK_READ), getPolicy.effects)
        assertEquals(ToolConcurrency.PARALLEL_SAFE, getPolicy.concurrency)
        assertTrue(getPolicy.allowReadOnlyParallelBatch)
        assertEquals(setOf(ToolEffect.NETWORK_WRITE), postPolicy.effects)
        assertEquals(ToolConcurrency.GLOBAL_SERIAL, postPolicy.concurrency)
        assertFalse(postPolicy.allowReadOnlyParallelBatch)
    }

    @Test
    fun `resource keys are stable opaque hashes and never expose arguments`() {
        val secretPath = "/sdcard/private/api-token-123.txt"
        val first = resolver.resolve(
            toolName = "read_file",
            args = buildJsonObject { put("path", secretPath) },
            context = context,
        )
        val second = resolver.resolve(
            toolName = "read_file",
            args = buildJsonObject { put("path", secretPath) },
            context = context,
        )

        assertEquals(first.resourceKeys, second.resourceKeys)
        assertTrue(first.resourceKeys.isNotEmpty())
        val rendered = first.resourceKeys.joinToString()
        assertFalse(rendered.contains("api-token-123"))
        assertFalse(rendered.contains("/sdcard"))
        assertTrue(first.resourceKeys.all { it.opaqueId.matches(Regex("[0-9a-f]{16}")) })
    }

    @Test
    fun `every implemented capability tool has a runtime policy`() {
        val names = CapabilityCatalog.allCapabilities()
            .filter { it.implementationState == ImplementationState.Implemented }
            .flatMap { it.toolNames }
            .toSortedSet()

        val missing = names.filter { name ->
            ToolEffect.UNKNOWN in resolver.resolve(name, buildJsonObject {}, context).effects
        }

        assertTrue("Missing policies: $missing", missing.isEmpty())
    }

    @Test
    fun `memory tool reads and mutations do not share one static effect`() {
        val query = resolver.resolve(
            "memory_tool",
            buildJsonObject { put("action", "query") },
            context,
        )
        val create = resolver.resolve(
            "memory_tool",
            buildJsonObject { put("action", "create") },
            context,
        )

        assertEquals(setOf(ToolEffect.LOCAL_READ), query.effects)
        assertTrue(query.allowReadOnlyParallelBatch)
        assertEquals(setOf(ToolEffect.PERSISTENT_STATE), create.effects)
        assertFalse(create.allowReadOnlyParallelBatch)
    }
}
