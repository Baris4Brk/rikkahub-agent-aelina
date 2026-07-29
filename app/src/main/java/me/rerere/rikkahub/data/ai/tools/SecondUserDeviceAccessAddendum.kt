package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.workspace.WorkspaceStorageMode

/**
 * Stable routing guidance for the selected, locally confirmed second-user conversation.
 *
 * This is deliberately not installed for ordinary assistants, remote surfaces, automatic pet
 * handoffs, or an unconfirmed second-user policy. It contains only fixed path conventions and no
 * user content, command text, credentials, or file names.
 */
internal fun secondUserDeviceAccessAddendum(
    privilege: PrivilegedSessionContext,
    workspaceId: String?,
    workspaceStorageMode: String?,
    workspaceShellSharedStorage: Boolean,
): String? {
    if (!privilege.expandLocalTools) return null
    val mode = workspaceStorageMode?.let { raw ->
        WorkspaceStorageMode.entries.firstOrNull { it.name == raw }
    }
    val workspaceVisibility = when (mode) {
        WorkspaceStorageMode.SHARED -> {
            val sharedPath = workspaceId
                ?.takeIf(String::isNotBlank)
                ?.let { "/storage/emulated/0/RikkaHubExchange/workspaces/$it" }
                ?: "/storage/emulated/0/RikkaHubExchange/workspaces/<workspace-id>"
            "This workspace uses SHARED storage: /workspace is also visible to Android file " +
                "managers under $sharedPath."
        }
        WorkspaceStorageMode.PRIVATE ->
            "This workspace uses PRIVATE storage: /workspace persists for the assistant but is " +
                "not directly visible in Android file managers. Use the phone paths or the " +
                "RikkaHubExchange directory when the user asks for a phone-visible file."
        null ->
            "Treat /workspace as the assistant workspace. Do not assume it is visible in the " +
                "Android file manager unless the workspace reports SHARED storage."
    }
    val shellStorage = if (workspaceShellSharedStorage) {
        "Inside workspace_shell, Android primary shared storage is mounted at /sdcard; for " +
            "example, /sdcard/Download and /sdcard/Music."
    } else {
        "workspace_shell does not currently have the shared-storage bind mount. Use direct " +
            "Android file/media tools for phone files, and call linux_grant_request when the " +
            "user asks to enable or refresh Linux/shared-storage access."
    }
    return """
        <second_user_device_access>
        You are the locally confirmed second-user assistant and may use the injected Android file,
        media, Workspace, Termux, and related local tools, subject to their normal execution gate.
        - Android primary shared storage is /storage/emulated/0 for list_files, read_file,
          open_file, play_media, and other direct phone tools. Use file:///storage/emulated/0/...
          when a tool requires a URI.
        - $shellStorage /workspace is the assistant's bound workspace, not the phone storage root.
        - RikkaHubExchange maps to /storage/emulated/0/RikkaHubExchange in Android,
          /sdcard/RikkaHubExchange in workspace_shell, and ~/storage/shared/RikkaHubExchange in
          Termux.
        - When the user asks to find, open, play, copy, or edit a phone file, inspect it with
          list_storage_volumes and list_files before claiming it is unavailable. Prefer direct
          phone file/media tools for user-visible files; use workspace tools for /workspace work.
        - SAF content:// access is for separately granted SD-card, USB, Downloads-provider, or cloud
          trees. Lack of a SAF grant does not mean primary shared storage is unavailable.
        - Before claiming that a Linux/shared-storage grant is active, call linux_grant_list. When
          asked to enable or refresh it, call linux_grant_request; do not infer grant state.
        - $workspaceVisibility
        </second_user_device_access>
    """.trimIndent()
}
