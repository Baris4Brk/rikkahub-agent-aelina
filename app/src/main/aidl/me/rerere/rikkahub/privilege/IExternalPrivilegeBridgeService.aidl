package me.rerere.rikkahub.privilege;

/**
 * Fixed privileged operations plus a privileged-session-only generic command interface.
 * Generic command access is restricted by the App-side privilege context and checked again
 * inside UserService.
 */
interface IExternalPrivilegeBridgeService {
    String listPackages(int userId);
    String forceStopApp(String packageName, int userId, in String[] protectedPackages);
    String clearAppCache(String packageName, int userId, in String[] protectedPackages);
    String runCommand(String requestJson);
    String cancelCommand(String commandId);
    String cancelAllCommands();
    void destroy();
}
