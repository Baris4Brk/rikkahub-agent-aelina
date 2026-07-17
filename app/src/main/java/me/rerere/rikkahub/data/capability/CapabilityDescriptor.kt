package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/**
 * Complete description of a single capability.
 *
 * This is the single source of truth for everything related to a capability —
 * which tool option toggle it, what permissions it needs, how risky it is, who
 * can call it, and whether it's implemented at all.
 *
 * @property id Unique capability identifier.
 * @property localToolOption The [LocalToolOption] toggle that enables/disables this capability,
 *   or null if the capability is not toggle-able (e.g. ManualOnly).
 * @property toolNames Exact LLM tool names owned by this capability. Empty keeps the legacy
 *   capability-id heuristic for descriptors that have not migrated yet.
 * @property requirements List of requirements (permissions, services, bridges) that must be
 *   satisfied before the capability can be used. Empty means no requirements.
 * @property implementationState Whether the capability is actually implemented.
 * @property riskLevel How risky it is to execute this capability.
 * @property approvalPolicy Default approval policy for this capability.
 * @property allowedOrigins Which call origins are permitted. Empty set = no restrictions.
 * @property requiresUnlockedDevice If true, the device must be unlocked to execute this capability.
 * @property requiresForegroundApp If true, the app must be in the foreground to execute.
 */
data class CapabilityDescriptor(
    val id: CapabilityId,
    val localToolOption: LocalToolOption?,
    val toolNames: Set<String> = emptySet(),
    val requirements: List<CapabilityRequirement>,
    val implementationState: ImplementationState,
    val riskLevel: RiskLevel,
    val approvalPolicy: ApprovalPolicy,
    val allowedOrigins: Set<ToolCallOrigin>,
    val requiresUnlockedDevice: Boolean = false,
    val requiresForegroundApp: Boolean = false,
)

/**
 * Default approval policy for a capability.
 * Individual assistants can override this per-capability.
 */
enum class ApprovalPolicy {
    /** Tool always requires user approval (added to ALWAYS_ASK). */
    AlwaysAsk,

    /** Tool requires approval only when called from remote origins. */
    AskOnRemote,

    /** Tool does not require approval by default (use at user's discretion). */
    Default,
}
