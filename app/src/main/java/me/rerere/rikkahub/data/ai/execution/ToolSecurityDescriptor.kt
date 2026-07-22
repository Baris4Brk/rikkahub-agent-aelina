package me.rerere.rikkahub.data.ai.execution

import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.ApprovalPolicy
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
import me.rerere.rikkahub.plugin.isPluginModelToolName

enum class ToolDescriptorSource {
    STATIC_CAPABILITY,
    INTERNAL,
    MCP,
    PLUGIN,
}

enum class ToolDescriptorApproval {
    DEFAULT,
    ASK_ON_REMOTE,
    EVERY_CALL,
    CALL_DEFINED,
}

data class ToolSecurityDescriptor(
    val toolName: String,
    val source: ToolDescriptorSource,
    val approval: ToolDescriptorApproval,
    val allowsPermanentApproval: Boolean,
)

fun interface ToolSecurityDescriptorResolver {
    fun resolve(toolName: String, context: ToolExecutionContext): ToolSecurityDescriptor?
}

/** Static, internal, MCP, and syntactically valid plugin descriptors. */
class DefaultToolSecurityDescriptorResolver : ToolSecurityDescriptorResolver {
    constructor() : this(pluginToolKnown = { false })

    constructor(pluginToolKnown: (String) -> Boolean) {
        this.pluginToolKnown = pluginToolKnown
    }

    private val pluginToolKnown: (String) -> Boolean

    override fun resolve(
        toolName: String,
        context: ToolExecutionContext,
    ): ToolSecurityDescriptor? {
        CapabilityCatalog.byToolName(toolName)?.let { capability ->
            val approval = when (capability.approvalPolicy) {
                ApprovalPolicy.AlwaysAsk -> ToolDescriptorApproval.EVERY_CALL
                ApprovalPolicy.AskOnRemote -> ToolDescriptorApproval.ASK_ON_REMOTE
                ApprovalPolicy.Default -> ToolDescriptorApproval.DEFAULT
            }
            return ToolSecurityDescriptor(
                toolName = toolName,
                source = ToolDescriptorSource.STATIC_CAPABILITY,
                approval = approval,
                allowsPermanentApproval = approval != ToolDescriptorApproval.EVERY_CALL,
            )
        }
        if (toolName in InternalToolSecurityCatalog.ALL) {
            return ToolSecurityDescriptor(
                toolName = toolName,
                source = ToolDescriptorSource.INTERNAL,
                approval = ToolDescriptorApproval.CALL_DEFINED,
                allowsPermanentApproval = false,
            )
        }
        if (toolName == PRIVILEGED_SHELL_TOOL_NAME ||
            toolName == "privileged_run_command" ||
            toolName == "external_bridge_run_command" ||
            toolName in STRUCTURED_PRIVILEGED_TOOL_NAMES ||
            toolName in STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
        ) {
            return ToolSecurityDescriptor(
                toolName = toolName,
                source = ToolDescriptorSource.INTERNAL,
                approval = ToolDescriptorApproval.EVERY_CALL,
                allowsPermanentApproval = false,
            )
        }
        if (toolName.startsWith("mcp__")) {
            return ToolSecurityDescriptor(
                toolName = toolName,
                source = ToolDescriptorSource.MCP,
                approval = ToolDescriptorApproval.EVERY_CALL,
                allowsPermanentApproval = false,
            )
        }
        if (isPluginModelToolName(toolName) && pluginToolKnown(toolName)) {
            return ToolSecurityDescriptor(
                toolName = toolName,
                source = ToolDescriptorSource.PLUGIN,
                approval = ToolDescriptorApproval.EVERY_CALL,
                allowsPermanentApproval = false,
            )
        }
        return null
    }

}
