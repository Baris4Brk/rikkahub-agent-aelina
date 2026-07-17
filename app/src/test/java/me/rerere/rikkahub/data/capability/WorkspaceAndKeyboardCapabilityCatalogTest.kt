package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceAndKeyboardCapabilityCatalogTest {

    @Test
    fun `workspace tools are catalogued with their exact invocation surfaces`() {
        val expectedSurfaces = mapOf(
            "workspace_read_file" to ToolInvocationSurface.Background,
            "workspace_write_file" to ToolInvocationSurface.FileMutation,
            "workspace_edit_file" to ToolInvocationSurface.FileMutation,
            "workspace_shell" to ToolInvocationSurface.UnboundedExecution,
        )

        val capability = CapabilityCatalog.capabilityOf(CapabilityId.WorkspaceTools)

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertEquals(expectedSurfaces.keys, capability?.toolNames)
        assertEquals(InvocationSurfacePolicy.LOCAL_UNLOCKED, capability?.allowedOrigins)
        expectedSurfaces.forEach { (toolName, expectedSurface) ->
            assertEquals(CapabilityId.WorkspaceTools, CapabilityCatalog.byToolName(toolName)?.id)
            assertEquals(expectedSurface, CapabilityCatalog.toolInvocationSurface(toolName))
        }
    }

    @Test
    fun `keyboard input alias keeps keyboard type catalog and approval policy`() {
        val canonical = CapabilityCatalog.byToolName("keyboard_type")
        val alias = CapabilityCatalog.byToolName("keyboard_input")

        assertEquals(CapabilityId.KeyboardControl, alias?.id)
        assertEquals(canonical?.id, alias?.id)
        assertEquals(
            ToolInvocationSurface.UnboundedExecution,
            CapabilityCatalog.toolInvocationSurface("keyboard_input"),
        )
        assertEquals(
            ToolApprovalDefaults.requiresApproval("keyboard_type"),
            ToolApprovalDefaults.requiresApproval("keyboard_input"),
        )
    }
}
