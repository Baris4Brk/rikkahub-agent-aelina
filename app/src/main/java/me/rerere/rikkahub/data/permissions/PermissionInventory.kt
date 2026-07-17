package me.rerere.rikkahub.data.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityRequirement
import me.rerere.rikkahub.data.capability.BridgeType
import me.rerere.rikkahub.data.capability.ImplementationState
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.service.RikkaNotificationListenerService

/**
 * Auto-discovered inventory of every permission this app requires, grouped by how the
 * user grants it. Reads <uses-permission> entries at runtime via PackageManager so
 * future-added perms appear automatically — only the friendly-label lookup is hand-curated;
 * any unmapped perm falls back to humanizing the constant name.
 *
 * On top of <uses-permission>, two virtual rows surface service bindings the user must enable
 * via dedicated Android UIs (AccessibilityService, NotificationListenerService) — these are
 * not real permissions but behave the same from the user's standpoint.
 */
object PermissionInventory {

    enum class Group { ServicesAndIntegrations, SpecialAccess, Runtime, AutoGranted }

    enum class Status { GRANTED, DENIED, AUTO_GRANTED }

    sealed class GrantAction {
        /** No action required — install-time / signature-level / always granted. */
        object None : GrantAction()
        /** Request via ActivityResultContracts.RequestPermission. */
        data class Runtime(val permission: String) : GrantAction()
        /** Open this Intent, user toggles in system Settings. */
        data class SystemSettings(val intent: Intent) : GrantAction()
    }

    data class Row(
        val id: String,
        val label: String,
        val description: String,
        val status: Status,
        val group: Group,
        val grant: GrantAction,
        /** Optional display override for states outside the legacy granted/denied model. */
        val statusLabel: String? = null,
    )

    fun build(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        rows += accessibilityServiceRow(context)
        rows += notificationListenerRow(context)
        rows += deviceAdminRow()
        rows += vpnServiceRow()
        rows += mediaProjectionConsentRow(context)
        rows += shizukuBridgeRow(context)
        rows += adbBridgeRow()

        val declared = readDeclaredPermissions(context)
        for (perm in declared) {
            rows += classify(context, perm) ?: continue
        }
        return rows.sortedWith(
            compareBy({ it.group.ordinal }, { if (it.status == Status.DENIED) 0 else 1 }, { it.label })
        )
    }

    /**
     * Build a list of [Row] entries from [CapabilityCatalog] showing the status of each
     * registered capability. This lets the user see what capabilities exist, their
     * implementation state, and whether their requirements are satisfied.
     *
     * Use this alongside [build] for a complete picture: [build] shows individual
     * permissions, while [capabilityStatusRows] groups them by capability.
     */
    fun capabilityStatusRows(context: Context): List<Row> {
        return CapabilityCatalog.allCapabilities().mapNotNull { cap ->
            val ok = cap.requirements.all { req -> checkRequirement(context, req) }
            val missingCount = cap.requirements.count { req -> !checkRequirement(context, req) }
            val desc = when (cap.implementationState) {
                ImplementationState.Reserved -> "[Reserved] Not yet implemented."
                ImplementationState.SystemRestricted -> "System-restricted — may not be available on this device."
                ImplementationState.ExternalBridgeRequired -> "Requires external privilege bridge."
                ImplementationState.ManualOnly -> "Manual UI operation only."
                ImplementationState.Implemented -> {
                    if (missingCount > 0) {
                        "Missing $missingCount requirement(s)."
                    } else {
                        "All requirements satisfied."
                    }
                }
            }
            Row(
                id = "capability:${cap.id.name}",
                label = cap.id.name.humanizeCapabilityId(),
                description = desc,
                status = when {
                    cap.implementationState == ImplementationState.Reserved -> Status.AUTO_GRANTED
                    ok -> Status.GRANTED
                    else -> Status.DENIED
                },
                group = Group.Runtime,
                grant = GrantAction.None,
                statusLabel = when (cap.implementationState) {
                    ImplementationState.Reserved -> "RESERVED"
                    ImplementationState.SystemRestricted -> "SYSTEM RESTRICTED"
                    ImplementationState.ExternalBridgeRequired -> "EXTERNAL BRIDGE"
                    ImplementationState.ManualOnly -> "MANUAL ONLY"
                    ImplementationState.Implemented -> null
                },
            )
        }
    }

    private fun checkRequirement(context: Context, req: CapabilityRequirement): Boolean {
        return when (req) {
            is CapabilityRequirement.ManifestPermission -> {
                ContextCompat.checkSelfPermission(context, req.permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
            is CapabilityRequirement.RuntimePermission -> {
                !req.appliesToSdk(Build.VERSION.SDK_INT) ||
                    ContextCompat.checkSelfPermission(context, req.permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
            is CapabilityRequirement.SpecialAccess -> false // too complex, skip in quick check
            is CapabilityRequirement.EnabledService -> {
                val flatName = req.component.flattenToString()
                when {
                    req.component.className.contains("RikkaAccessibilityService") -> {
                        (Settings.Secure.getString(
                            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: "").split(":").any { it.equals(flatName, ignoreCase = true) }
                    }
                    req.component.className.contains("RikkaNotificationListenerService") -> {
                        (Settings.Secure.getString(
                            context.contentResolver, "enabled_notification_listeners"
                        ) ?: "").split(":").any { it.equals(flatName, ignoreCase = true) }
                    }
                    else -> false
                }
            }
            is CapabilityRequirement.ExternalBridge -> when (req.type) {
                BridgeType.Shizuku -> runCatching {
                    rikka.shizuku.Shizuku.pingBinder() &&
                        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                else -> false
            }
            is CapabilityRequirement.Role -> false
            is CapabilityRequirement.MediaProjectionConsent -> false
            is CapabilityRequirement.VpnConsent -> false
        }
    }

    private fun String.humanizeCapabilityId(): String {
        // Convert "ExportConversation" → "Export Conversation"
        return this.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .replace(Regex("([A-Z])([A-Z][a-z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }

    private fun readDeclaredPermissions(context: Context): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    private fun classify(context: Context, perm: String): Row? {
        val pm = context.packageManager
        val pkgUri: Uri = ("package:" + context.packageName).toUri()

        API_RANGES[perm]?.let { range ->
            if (Build.VERSION.SDK_INT < range.first || Build.VERSION.SDK_INT > range.last) {
                val requirement = when {
                    range.last < Int.MAX_VALUE -> "Android ${range.first}–${range.last} only."
                    else -> "Requires Android API ${range.first} or newer."
                }
                return Row(
                    id = perm,
                    label = labelOrHumanize(perm),
                    description = "$requirement ${descriptionOrDefault(perm)}",
                    status = Status.AUTO_GRANTED,
                    group = Group.AutoGranted,
                    grant = GrantAction.None,
                    statusLabel = "NOT APPLICABLE",
                )
            }
        }

        // Special-access permissions — each has its own canWrite / canDrawOverlays / etc check
        // and a deep-link Intent.
        when (perm) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                val granted = Settings.canDrawOverlays(context)
                return Row(
                    id = perm,
                    label = "Display over other apps",
                    description = "Lets RikkaHub draw the \"agent is working\" overlay while automation is in progress.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                    ),
                )
            }
            Manifest.permission.WRITE_SETTINGS -> {
                val granted = Settings.System.canWrite(context)
                return Row(
                    id = perm,
                    label = "Modify system settings",
                    description = "Lets the agent change brightness via set_brightness.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
                    ),
                )
            }
            Manifest.permission.ACCESS_NOTIFICATION_POLICY -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                val granted = nm?.isNotificationPolicyAccessGranted == true
                return Row(
                    id = perm,
                    label = "Do Not Disturb access",
                    description = "Lets the agent change ringer mode and per-stream volume.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    ),
                )
            }
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> {
                val pwm = context.getSystemService(PowerManager::class.java)
                val granted = pwm?.isIgnoringBatteryOptimizations(context.packageName) == true
                return Row(
                    id = perm,
                    label = "Ignore battery optimizations",
                    description = "Keeps the Telegram bot foreground service responsive when the screen is off.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS pops a system dialog asking
                    // for the exemption directly — better UX than the long settings list.
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                    ),
                )
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    Row(
                        id = perm,
                        label = "Post notifications",
                        description = "Required so the bot foreground service and TTS / progress notifications can show.",
                        status = if (granted) Status.GRANTED else Status.DENIED,
                        group = Group.Runtime,
                        grant = GrantAction.Runtime(perm),
                    )
                } else {
                    autoRow(perm, "Post notifications")
                }
            }
            Manifest.permission.PACKAGE_USAGE_STATS -> {
                val granted = PermissionHelper.hasUsageStatsAccess(context)
                return Row(
                    id = perm,
                    label = "Usage access",
                    description = "Lets the agent query app usage for screen time analysis.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.usageAccessIntent()),
                )
            }
            Manifest.permission.MANAGE_EXTERNAL_STORAGE -> {
                val granted = PermissionHelper.hasAllFilesAccess(context)
                return Row(
                    id = perm,
                    label = "All files access",
                    description = "Lets enabled file tools manage shared storage outside app-owned folders.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.allFilesAccessIntent(context)),
                )
            }
            Manifest.permission.SCHEDULE_EXACT_ALARM -> {
                val granted = PermissionHelper.hasExactAlarmAccess(context)
                return Row(
                    id = perm,
                    label = "Exact alarms",
                    description = "Lets the agent schedule alarms at precise times.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.exactAlarmIntent(context)),
                )
            }
            Manifest.permission.REQUEST_INSTALL_PACKAGES -> {
                val granted = PermissionHelper.canRequestPackageInstalls(context)
                return Row(
                    id = perm,
                    label = "Install unknown apps",
                    description = "Allows RikkaHub to start the system-confirmed APK installation flow.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.unknownAppSourcesIntent(context)),
                )
            }
            Manifest.permission.USE_FULL_SCREEN_INTENT -> {
                val granted = PermissionHelper.canUseFullScreenIntent(context)
                return Row(
                    id = perm,
                    label = "Full-screen intent access",
                    description = "Lets eligible urgent notifications request full-screen presentation.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        GrantAction.SystemSettings(PermissionHelper.fullScreenIntentSettingsIntent(context))
                    } else {
                        GrantAction.None
                    },
                )
            }
        }

        // Generic classification: ask PackageManager about the protection level. Dangerous =>
        // runtime grant. Anything else (normal, signature, signatureOrSystem) is auto-granted
        // at install time and only listed for transparency.
        val info: PermissionInfo? = try {
            pm.getPermissionInfo(perm, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        if (info == null) {
            // Unknown to this device — typically a custom perm declared by an app that isn't
            // installed (e.g. com.termux.permission.RUN_COMMAND when Termux isn't installed).
            // Best we can do is check checkSelfPermission and offer no grant flow.
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            return Row(
                id = perm,
                label = humanize(perm),
                description = "Custom permission. Owner app may not be installed yet.",
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        }

        val protectionBase = info.protection
        val isDangerous = protectionBase == PermissionInfo.PROTECTION_DANGEROUS
        val granted = ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED

        return if (isDangerous) {
            Row(
                id = perm,
                label = labelOrHumanize(perm),
                description = describeRuntime(perm),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        } else {
            autoRow(perm, labelOrHumanize(perm), descriptionOrDefault(perm))
        }
    }

    private fun autoRow(
        perm: String,
        label: String,
        description: String = "Auto-granted at install (no user action needed).",
    ) = Row(
        id = perm,
        label = label,
        description = description,
        status = Status.AUTO_GRANTED,
        group = Group.AutoGranted,
        grant = GrantAction.None,
    )

    private fun accessibilityServiceRow(context: Context): Row {
        val component = ComponentName(context, RikkaAccessibilityService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_ACCESSIBILITY",
            label = "Screen automation (Accessibility)",
            description = "Required for tap, swipe, click_node, screenshot, read_window_tree, set_text and other UI-driving tools.",
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private fun notificationListenerRow(context: Context): Row {
        val component = ComponentName(context, RikkaNotificationListenerService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_NOTIFICATION_LISTENER",
            label = "Notification access",
            description = "Lets the agent read incoming notifications and auto-forward whitelisted apps to Telegram.",
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private fun deviceAdminRow() = reservedIntegrationRow(
        id = "rikkahub.SERVICE_DEVICE_ADMIN",
        label = "Device administrator",
        description = "Reserved. A DeviceAdminReceiver has not been implemented; no device policy is exposed to the assistant.",
    )

    private fun vpnServiceRow() = reservedIntegrationRow(
        id = "rikkahub.SERVICE_VPN",
        label = "VPN service",
        description = "Reserved. Opens Android VPN settings, but RikkaHub does not register a VPN service yet.",
        grant = GrantAction.SystemSettings(PermissionHelper.vpnSettingsIntent()),
    )

    private fun mediaProjectionConsentRow(context: Context): Row {
        val supported = PermissionHelper.hasMediaProjectionCapability(context)
        return Row(
            id = "rikkahub.CONSENT_MEDIA_PROJECTION",
            label = "Screen capture consent",
            description = if (supported) {
                "Supported, but Android requires a fresh system confirmation for every capture session. No persistent grant exists."
            } else {
                "MediaProjection is not available on this device."
            },
            status = Status.AUTO_GRANTED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.None,
            statusLabel = if (supported) "PER-USE CONSENT" else "NOT APPLICABLE",
        )
    }

    private fun shizukuBridgeRow(context: Context): Row {
        val packageManager = context.packageManager
        val installed = listOf("moe.shizuku.privileged.api", "rikka.sui").any { packageName ->
            runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        }
        val binderAvailable = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        val authorized = binderAvailable && runCatching {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val (status, statusLabel, description) = when {
            !installed -> Triple(Status.DENIED, "NOT INSTALLED", "Install Shizuku or Sui to expose the structured privilege bridge.")
            !binderAvailable -> Triple(Status.DENIED, "NOT RUNNING", "Shizuku or Sui is installed, but its Binder service is not running.")
            !authorized -> Triple(Status.DENIED, "AUTHORIZATION REQUIRED", "The bridge is running; authorize RikkaHub from the settings switch before use.")
            else -> Triple(Status.GRANTED, "AUTHORIZED", "The structured Shizuku bridge is authorized and ready for approved local tools.")
        }
        return Row(
            id = "rikkahub.EXTERNAL_BRIDGE_SHIZUKU",
            label = "Shizuku bridge",
            description = description,
            status = status,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.None,
            statusLabel = statusLabel,
        )
    }

    private fun adbBridgeRow() = reservedIntegrationRow(
        id = "rikkahub.EXTERNAL_BRIDGE_ADB",
        label = "ADB bridge",
        description = "Reserved for an experimental external bridge; it is not exposed to the assistant.",
    )

    private fun reservedIntegrationRow(
        id: String,
        label: String,
        description: String,
        grant: GrantAction = GrantAction.None,
    ) = Row(
        id = id,
        label = label,
        description = description,
        status = Status.AUTO_GRANTED,
        group = Group.ServicesAndIntegrations,
        grant = grant,
        statusLabel = "RESERVED",
    )

    // -- Friendly labels for every dangerous permission we currently request ------------------

    private val LABELS = mapOf(
        Manifest.permission.CAMERA to "Camera",
        Manifest.permission.RECORD_AUDIO to "Microphone",
        Manifest.permission.READ_PHONE_STATE to "Phone state",
        Manifest.permission.ACCESS_FINE_LOCATION to "Precise location",
        Manifest.permission.ACCESS_COARSE_LOCATION to "Approximate location",
        Manifest.permission.READ_CONTACTS to "Contacts",
        Manifest.permission.READ_CALL_LOG to "Call log",
        Manifest.permission.READ_SMS to "SMS",
        Manifest.permission.SEND_SMS to "Send SMS",
        Manifest.permission.POST_NOTIFICATIONS to "Post notifications",
        Manifest.permission.READ_MEDIA_IMAGES to "Media — images",
        Manifest.permission.READ_MEDIA_VIDEO to "Media — video",
        Manifest.permission.READ_MEDIA_AUDIO to "Media — audio",
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to "Media — selected photos and videos",
        Manifest.permission.READ_EXTERNAL_STORAGE to "Shared storage — read (legacy)",
        Manifest.permission.WRITE_EXTERNAL_STORAGE to "Shared storage — write (legacy)",
        Manifest.permission.WRITE_CONTACTS to "Write contacts",
        Manifest.permission.GET_ACCOUNTS to "Device accounts",
        Manifest.permission.CALL_PHONE to "Call phone",
        Manifest.permission.ANSWER_PHONE_CALLS to "Answer phone calls",
        Manifest.permission.READ_PHONE_NUMBERS to "Phone numbers",
        Manifest.permission.RECEIVE_SMS to "Receive SMS",
        Manifest.permission.RECEIVE_MMS to "Receive MMS",
        Manifest.permission.RECEIVE_WAP_PUSH to "Receive WAP push",
        "com.android.voicemail.permission.ADD_VOICEMAIL" to "Add voicemail",
        Manifest.permission.BLUETOOTH_SCAN to "Bluetooth scan",
        Manifest.permission.BLUETOOTH_ADVERTISE to "Bluetooth advertise",
        Manifest.permission.NEARBY_WIFI_DEVICES to "Nearby WiFi devices",
        Manifest.permission.CHANGE_WIFI_STATE to "Change WiFi state",
        Manifest.permission.BLUETOOTH to "Bluetooth (legacy)",
        Manifest.permission.BLUETOOTH_ADMIN to "Bluetooth administration (legacy)",
        Manifest.permission.ACTIVITY_RECOGNITION to "Activity recognition",
        Manifest.permission.BODY_SENSORS to "Body sensors",
        Manifest.permission.BODY_SENSORS_BACKGROUND to "Body sensors in background",
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS to "High sampling rate sensors",
        Manifest.permission.REQUEST_INSTALL_PACKAGES to "Install unknown apps",
        Manifest.permission.REQUEST_DELETE_PACKAGES to "Request app uninstall",
        Manifest.permission.EXPAND_STATUS_BAR to "Expand status bar",
        Manifest.permission.DISABLE_KEYGUARD to "Dismiss insecure keyguard",
        Manifest.permission.SET_ALARM to "Set alarms",
        Manifest.permission.USE_FULL_SCREEN_INTENT to "Full screen intent",
        "com.termux.permission.RUN_COMMAND" to "Termux RUN_COMMAND",
    )

    private val DESCRIPTIONS = mapOf(
        Manifest.permission.CAMERA to "Used by take_photo to capture a still image.",
        Manifest.permission.RECORD_AUDIO to "Used by record_audio and speech_to_text.",
        Manifest.permission.READ_PHONE_STATE to "Used by get_telephony_info (SIM operator, signal).",
        Manifest.permission.ACCESS_FINE_LOCATION to "Used by get_location and get_wifi_info.",
        Manifest.permission.ACCESS_COARSE_LOCATION to "Approximate location fallback for get_location.",
        Manifest.permission.READ_CONTACTS to "Used by search_contacts and list_contacts.",
        Manifest.permission.READ_CALL_LOG to "Used by list_call_log.",
        Manifest.permission.READ_SMS to "Used by list_sms_inbox and search_sms.",
        Manifest.permission.SEND_SMS to "Used by send_sms to send text messages programmatically.",
        Manifest.permission.READ_MEDIA_IMAGES to "Allows enabled media tools to read images selected by Android's media permission model.",
        Manifest.permission.READ_MEDIA_VIDEO to "Reserved for reading videos from shared media storage.",
        Manifest.permission.READ_MEDIA_AUDIO to "Allows enabled media tools to read audio from shared media storage.",
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to "Allows access only to photos and videos explicitly selected by the user on Android 14+.",
        Manifest.permission.READ_EXTERNAL_STORAGE to "Legacy shared-storage read access on Android 12 and earlier.",
        Manifest.permission.WRITE_EXTERNAL_STORAGE to "Legacy shared-storage write access through Android 9.",
        Manifest.permission.WRITE_CONTACTS to "Reserved for creating or updating contacts after explicit approval.",
        Manifest.permission.GET_ACCOUNTS to "Reserved for showing which account owns a contact; account data remains system-restricted.",
        Manifest.permission.CALL_PHONE to "Reserved for direct calls; each call must require local approval.",
        Manifest.permission.ANSWER_PHONE_CALLS to "Reserved for answering calls where the device and phone role allow it.",
        Manifest.permission.READ_PHONE_NUMBERS to "Reserved for reading device phone numbers where the carrier exposes them.",
        Manifest.permission.RECEIVE_SMS to "Reserved for receiving SMS broadcasts and local workflow triggers.",
        Manifest.permission.RECEIVE_MMS to "Reserved for receiving MMS broadcasts; behavior depends on the default SMS role and OEM.",
        Manifest.permission.RECEIVE_WAP_PUSH to "Reserved for WAP push messages; behavior depends on the default SMS role and OEM.",
        "com.android.voicemail.permission.ADD_VOICEMAIL" to "System-restricted voicemail integration; availability depends on the phone or voicemail role.",
        Manifest.permission.BLUETOOTH_SCAN to "Allows enabled nearby-device tools to discover Bluetooth devices.",
        Manifest.permission.BLUETOOTH_ADVERTISE to "Reserved for Bluetooth advertising initiated by a local, approved action.",
        Manifest.permission.NEARBY_WIFI_DEVICES to "Reserved for nearby WiFi discovery on supported Android versions.",
        Manifest.permission.CHANGE_WIFI_STATE to "Allows system-confirmed WiFi configuration changes where Android permits them.",
        Manifest.permission.BLUETOOTH to "Legacy Bluetooth access through Android 11.",
        Manifest.permission.BLUETOOTH_ADMIN to "Legacy Bluetooth discovery and pairing administration through Android 11.",
        Manifest.permission.ACTIVITY_RECOGNITION to "Used by step-count and activity-recognition capabilities.",
        Manifest.permission.BODY_SENSORS to "Reserved for heart-rate and other body sensors exposed by the device.",
        Manifest.permission.BODY_SENSORS_BACKGROUND to "System-restricted background access to body sensors on supported Android versions.",
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS to "Allows enabled sensor tools to request higher sampling rates.",
        Manifest.permission.REQUEST_INSTALL_PACKAGES to "Allows starting Android's user-confirmed unknown-app installation flow.",
        Manifest.permission.REQUEST_DELETE_PACKAGES to "Allows starting Android's user-confirmed app uninstall flow.",
        Manifest.permission.EXPAND_STATUS_BAR to "Allows local UI automation to expand or collapse the status bar where supported.",
        Manifest.permission.DISABLE_KEYGUARD to "Only dismisses a non-secure keyguard; it cannot bypass device credentials.",
        Manifest.permission.SET_ALARM to "Allows opening or integrating with the system alarm application.",
        Manifest.permission.USE_FULL_SCREEN_INTENT to "Allows eligible urgent notifications to request full-screen presentation.",
        "com.termux.permission.RUN_COMMAND" to "Lets RikkaHub start commands inside Termux for the termux_run_command tool.",
    )

    /** API interval where each versioned permission exists and can be meaningfully granted. */
    private val API_RANGES = mapOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to (Build.VERSION_CODES.Q..Int.MAX_VALUE),
        Manifest.permission.ACCESS_LOCAL_NETWORK to (37..Int.MAX_VALUE),
        Manifest.permission.POST_PROMOTED_NOTIFICATIONS to (36..Int.MAX_VALUE),
        Manifest.permission.POST_NOTIFICATIONS to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_IMAGES to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_VIDEO to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_AUDIO to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.READ_EXTERNAL_STORAGE to (1..Build.VERSION_CODES.S_V2),
        Manifest.permission.WRITE_EXTERNAL_STORAGE to (1..Build.VERSION_CODES.P),
        Manifest.permission.READ_PHONE_NUMBERS to (Build.VERSION_CODES.O..Int.MAX_VALUE),
        Manifest.permission.ANSWER_PHONE_CALLS to (Build.VERSION_CODES.O..Int.MAX_VALUE),
        Manifest.permission.BLUETOOTH_SCAN to (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.BLUETOOTH_ADVERTISE to (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.BLUETOOTH to (1..Build.VERSION_CODES.R),
        Manifest.permission.BLUETOOTH_ADMIN to (1..Build.VERSION_CODES.R),
        Manifest.permission.NEARBY_WIFI_DEVICES to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.ACTIVITY_RECOGNITION to (Build.VERSION_CODES.Q..Int.MAX_VALUE),
        Manifest.permission.BODY_SENSORS_BACKGROUND to
            (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS to
            (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.REQUEST_INSTALL_PACKAGES to (Build.VERSION_CODES.O..Int.MAX_VALUE),
        Manifest.permission.REQUEST_DELETE_PACKAGES to (Build.VERSION_CODES.O..Int.MAX_VALUE),
        Manifest.permission.SCHEDULE_EXACT_ALARM to (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.MANAGE_EXTERNAL_STORAGE to (Build.VERSION_CODES.R..Int.MAX_VALUE),
        Manifest.permission.USE_FULL_SCREEN_INTENT to (Build.VERSION_CODES.Q..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_CAMERA to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
    )

    private fun labelOrHumanize(perm: String) = LABELS[perm] ?: humanize(perm)
    private fun describeRuntime(perm: String) =
        DESCRIPTIONS[perm] ?: "Runtime permission required by one or more enabled tools."
    private fun descriptionOrDefault(perm: String) =
        DESCRIPTIONS[perm] ?: "Auto-granted at install (no user action needed)."

    private fun humanize(perm: String): String {
        val tail = perm.substringAfterLast('.')
        return tail.lowercase().split('_').joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }
}

private fun String.toUri(): Uri = Uri.parse(this)
