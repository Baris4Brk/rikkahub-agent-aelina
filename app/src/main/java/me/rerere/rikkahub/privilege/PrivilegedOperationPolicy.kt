package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeActionResult
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgePrivilege

/**
 * Minimum self-preservation policy for the personal privileged shell. This is deliberately a
 * mistake-prevention floor, not an adversarial shell sandbox: generic root shell syntax can use
 * indirection that no finite string policy can fully understand.
 */
class PrivilegedOperationPolicy(
    applicationPackageName: String,
    bridgePackages: Set<String> = DEFAULT_BRIDGE_PACKAGES,
) {
    private val appPackage = applicationPackageName.trim().lowercase()
    private val protectedPackages = (bridgePackages + applicationPackageName)
        .mapTo(linkedSetOf()) { it.trim().lowercase() }
    private val privateRoots = listOf(
        "/data/data/$appPackage",
        "/data/user/0/$appPackage",
    )

    fun check(
        input: PrivilegedCommandInput,
        privilege: ExternalPrivilegeBridgePrivilege,
    ): ExternalPrivilegeActionResult? {
        val canonical = when (input.mode) {
            PrivilegedCommandMode.ARGV ->
                (listOf(input.executable) + input.arguments).joinToString(" ")
            PrivilegedCommandMode.SHELL -> input.command
        }.lowercase()

        if (isProtectedRunAs(canonical)) {
            return rejected("run-as access to RikkaHub private state is protected.")
        }
        if (privilege == ExternalPrivilegeBridgePrivilege.Root && privateRoots.any(canonical::contains)) {
            return rejected("Direct root access to RikkaHub private data is protected.")
        }
        if (targetsProtectedPackageDestructively(canonical)) {
            return rejected("RikkaHub, Shizuku, and Sui cannot be stopped, cleared, disabled, or removed.")
        }
        return null
    }

    private fun isProtectedRunAs(command: String): Boolean =
        Regex("(?:^|[;&|\\s])(?:/system/bin/)?run-as\\s+${Regex.escape(appPackage)}(?:\\s|$)")
            .containsMatchIn(command)

    private fun targetsProtectedPackageDestructively(command: String): Boolean {
        if (protectedPackages.none(command::contains)) return false
        return DESTRUCTIVE_PACKAGE_PATTERNS.any { it.containsMatchIn(command) }
    }

    private fun rejected(message: String) = ExternalPrivilegeActionResult(
        ok = false,
        code = "COMMAND_REJECTED",
        message = message,
    )

    companion object {
        val DEFAULT_BRIDGE_PACKAGES: Set<String> = setOf(
            "moe.shizuku.privileged.api",
            "rikka.sui",
        )

        private val DESTRUCTIVE_PACKAGE_PATTERNS = listOf(
            Regex("(?:^|[;&|\\s])(?:/system/bin/)?am\\s+force-stop\\b"),
            Regex("(?:^|[;&|\\s])(?:/system/bin/)?pm\\s+(?:clear|uninstall|disable|disable-user)\\b"),
            Regex("(?:^|[;&|\\s])(?:/system/bin/)?cmd\\s+package\\s+(?:clear|uninstall)\\b"),
            Regex("(?:^|[;&|\\s])(?:/system/bin/)?cmd\\s+package\\s+set-enabled-setting\\b.*\\bdisabled"),
            Regex("(?:^|[;&|\\s])(?:kill|pkill|killall)\\b"),
        )

    }
}
