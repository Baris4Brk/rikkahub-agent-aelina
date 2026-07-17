package me.rerere.rikkahub.data.capability

import android.content.ComponentName

/**
 * A requirement that must be satisfied for a capability to be available.
 *
 * Each capability in the [CapabilityCatalog] lists all its requirements.
 * The evaluation logic ([CapabilityStatus]) checks each requirement against
 * the device state and the app's configuration.
 */
sealed interface CapabilityRequirement {

    /** A `<uses-permission>` declared in AndroidManifest.xml (install-time grant). */
    data class ManifestPermission(val permission: String) : CapabilityRequirement

    /** A runtime (dangerous) permission that must be granted via dialog on its SDK range. */
    data class RuntimePermission(
        val permission: String,
        val minSdk: Int = 1,
        val maxSdk: Int = Int.MAX_VALUE,
    ) : CapabilityRequirement {
        init {
            require(minSdk <= maxSdk) { "minSdk must not exceed maxSdk" }
        }

        fun appliesToSdk(sdk: Int): Boolean = sdk in minSdk..maxSdk
    }

    /** A special system access that requires a Settings intent (e.g. overlay, DND). */
    data class SpecialAccess(val type: SpecialAccessType) : CapabilityRequirement

    /** A system service component that must be enabled (e.g. AccessibilityService). */
    data class EnabledService(val component: ComponentName) : CapabilityRequirement

    /** A system role (e.g. default SMS app, default phone app). */
    data class Role(val roleName: String) : CapabilityRequirement

    /** An external privilege bridge (Shizuku, ADB, Device Owner, root). */
    data class ExternalBridge(val type: BridgeType) : CapabilityRequirement

    /** MediaProjection consent — must be obtained before each use. */
    data object MediaProjectionConsent : CapabilityRequirement

    /** VPN consent — user must confirm via system dialog. */
    data object VpnConsent : CapabilityRequirement
}

/** Types of special system access that require a Settings intent. */
enum class SpecialAccessType {
    Overlay,
    WriteSettings,
    Dnd,
    AllFilesAccess,
    UsageStats,
    ExactAlarm,
    BatteryOptimization,
    InstallUnknownApps,
    NotificationListener,
    Accessibility,
    MediaProjection,
    Vpn,
    DeviceAdmin,
}

/** Types of external privilege bridges. */
enum class BridgeType {
    Shizuku,
    Adb,
    DeviceOwner,
    Root,
}
