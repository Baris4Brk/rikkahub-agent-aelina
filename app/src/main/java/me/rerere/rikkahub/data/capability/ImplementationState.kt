package me.rerere.rikkahub.data.capability

/**
 * Implementation state of a capability.
 *
 * Used in [CapabilityDescriptor] so the UI and diagnostic page can show accurate
 * status for every capability, not just those that are already implemented.
 */
enum class ImplementationState {
    /** The capability has a working tool implementation with permission checks, approval, and tests. */
    Implemented,

    /** Manifest permission has been declared but the tool has not been implemented yet.
     *  UI shows "[Reserved] Not yet implemented" and the switch is disabled. */
    Reserved,

    /** Permission is declared but the system restricts it on this device or API level.
     *  (e.g. some OEMs restrict READ_SMS even after grant). */
    SystemRestricted,

    /** The capability requires an external privilege bridge (Shizuku / ADB / Device Owner / root). */
    ExternalBridgeRequired,

    /** The capability is for manual UI use only — not exposed as an LLM tool. */
    ManualOnly,
}
