package me.rerere.rikkahub.privilege;

import me.rerere.rikkahub.privilege.IDisplayAutomationBridge;

/**
 * Fixed privileged operations plus a privileged-session-only generic command interface.
 * Generic command access is restricted by the App-side privilege context and checked again
 * inside UserService.
 */
interface IExternalPrivilegeBridgeService {
    IDisplayAutomationBridge displayAutomationBridge();
    String listPackages(int userId);
    String forceStopApp(String packageName, int userId, in String[] protectedPackages);
    String clearAppCache(String packageName, int userId, in String[] protectedPackages);
    String ensureAccessibilityServiceEnabled(int userId, boolean forceRebind);
    String runCommand(String requestJson);
    String cancelCommand(String commandId);
    String cancelAllCommands();
    void destroy();
}
