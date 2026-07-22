package me.rerere.rikkahub.data.ai.execution

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ImplementationState
import me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ToolSecurityDescriptorResolverTest {
    private val resolver = DefaultToolSecurityDescriptorResolver()
    private val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant",
        callOrigin = ToolCallOrigin.LocalChat,
    )

    @Test
    fun `all implemented capability tools have security descriptors`() {
        val missing = CapabilityCatalog.allCapabilities()
            .filter { it.implementationState == ImplementationState.Implemented }
            .flatMap { it.toolNames }
            .distinct()
            .filter { resolver.resolve(it, context) == null }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `mcp tools are per-call and cannot be permanently approved`() {
        val descriptor = checkNotNull(resolver.resolve("mcp__calendar__read", context))

        assertEquals(ToolDescriptorSource.MCP, descriptor.source)
        assertEquals(ToolDescriptorApproval.EVERY_CALL, descriptor.approval)
        assertFalse(descriptor.allowsPermanentApproval)
    }

    @Test
    fun `plugin tools are per-call and cannot be permanently approved`() {
        val pluginAware = DefaultToolSecurityDescriptorResolver(
            pluginToolKnown = { it == "plugin__0123456789ab__read_status" },
        )
        val descriptor = checkNotNull(
            pluginAware.resolve("plugin__0123456789ab__read_status", context)
        )

        assertEquals(ToolDescriptorSource.PLUGIN, descriptor.source)
        assertEquals(ToolDescriptorApproval.EVERY_CALL, descriptor.approval)
        assertFalse(descriptor.allowsPermanentApproval)
        assertNull(resolver.resolve("plugin__0123456789ab__read_status", context))
    }

    @Test
    fun `unknown tools fail descriptor resolution`() {
        assertNull(resolver.resolve("unregistered_runtime_tool", context))
    }

    @Test
    fun `every explicitly internal model tool has a descriptor`() {
        val missing = InternalToolSecurityCatalog.ALL.filter {
            resolver.resolve(it, context) == null
        }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `dynamic privileged command is explicit and cannot be permanently approved`() {
        val descriptor = resolver.resolve(PRIVILEGED_SHELL_TOOL_NAME, context)

        assertNotNull(descriptor)
        assertEquals(ToolDescriptorApproval.EVERY_CALL, descriptor!!.approval)
        assertFalse(descriptor.allowsPermanentApproval)
    }
}
