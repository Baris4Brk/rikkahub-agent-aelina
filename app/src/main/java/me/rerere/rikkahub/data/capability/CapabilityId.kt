package me.rerere.rikkahub.data.capability

/**
 * Unique identifier for every capability (tool group or individual tool) in RikkaHub.
 *
 * Each ID corresponds to a [CapabilityDescriptor] in the [CapabilityCatalog].
 * Naming convention: PascalCase matching the conceptual capability name.
 *
 * When you add a new tool or tool group, add its ID here FIRST, then register
 * the descriptor in CapabilityCatalog. This ensures the ID is the single source
 * of truth referenced everywhere else (UI switch, permission check, approval policy).
 */
enum class CapabilityId {
    // ── Device Info ───────────────────────────────────────────────────────────────
    TimeInfo,
    Battery,
    AudioInfo,
    TelephonyInfo,
    WifiInfo,
    Sensors,
    StorageInfo,
    JavascriptEngine,
    Clipboard,
    TextToSpeech,
    AskUser,

    // ── Device Control ────────────────────────────────────────────────────────────
    Toast,
    Notification,
    Share,
    Torch,
    Vibrate,
    Brightness,
    Volume,
    MediaPlayer,
    MediaScanner,
    Download,
    SetWallpaper,

    // ── Location & Sensors ────────────────────────────────────────────────────────
    Location,
    GnssDiagnostics,
    StepCounter,

    // ── Contacts & Communication ──────────────────────────────────────────────────
    Calendar,
    Contacts,
    CallLog,
    SmsInbox,
    SmsSend,
    CameraPhoto,
    MicRecorder,
    SpeechToText,
    Fingerprint,

    // ── System Automation ─────────────────────────────────────────────────────────
    ScreenTime,
    CronJobs,
    ScreenAutomation,
    AppLauncher,
    SystemIntents,
    KeyboardControl,

    // ── Files & Storage ───────────────────────────────────────────────────────────
    Files,
    ExternalStorage,
    Archive,
    MediaLibrary,
    ExportConversation,

    // ── Privileged Bridges ────────────────────────────────────────────────────────
    Ssh,
    Termux,

    // ── Remote Entry & Control ────────────────────────────────────────────────────
    TelegramBot,
    McpControl,
    ExternalAutomation,
    WebFetch,
    Browser,

    // ── Background & Automation ───────────────────────────────────────────────────
    NotificationListener,
    Workflows,
    CostGuards,
    SubAgents,
    Alarm,
    BluetoothDevices,

    // ── Security & Crypto ─────────────────────────────────────────────────────────
    Keystore,
    Nfc,
    Reliability,

    // ── Extensibility ─────────────────────────────────────────────────────────────
    SkillImport,
    JsSkills,

    // ── V2 Reserved Capabilities ──────────────────────────────────────────────────
    MediaWrite,
    ContactsWrite,
    PhoneActions,
    SmsReceive,
    NearbyDevices,
    HealthSensors,
    PackageManagement,
    DeviceAdmin,
    VpnControl,
    MediaProjection,
    ExternalPrivilegeBridge,
    PrivilegedShell,
    StructuredPrivilegedSystemTools,
    StructuredPrivilegedSystemToolsV2,
    VerifiedAccessibility,
    WorkspaceProcessManagement,
}
