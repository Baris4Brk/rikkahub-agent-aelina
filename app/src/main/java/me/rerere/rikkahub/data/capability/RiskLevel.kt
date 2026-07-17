package me.rerere.rikkahub.data.capability

/** Risk level of executing a capability. Used by [CapabilityDescriptor] and the execution gate. */
enum class RiskLevel {
    /** Read-only, no side effects (e.g. get_battery_status, get_storage_info). */
    Low,

    /** Read with potential privacy implications (e.g. list_contacts, read_sms, take_screenshot). */
    Medium,

    /** Side-effecting writes to device state (e.g. send_notification, set_volume, delete_file). */
    High,

    /** Irreversible or high-impact operations (e.g. install_apk, factory_reset, format storage). */
    Critical,
}
