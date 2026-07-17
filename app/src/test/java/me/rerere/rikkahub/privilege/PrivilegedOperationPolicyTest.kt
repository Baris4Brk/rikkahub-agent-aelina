package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgePrivilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedOperationPolicyTest {
    private val policy = PrivilegedOperationPolicy(
        applicationPackageName = "me.rerere.rikkahub",
    )

    @Test
    fun `ordinary system diagnostics and third party app management are allowed`() {
        assertNull(policy.check(argv("/system/bin/dumpsys", "battery"), ExternalPrivilegeBridgePrivilege.Shell))
        assertNull(policy.check(argv("/system/bin/am", "force-stop", "com.example.notes"), ExternalPrivilegeBridgePrivilege.Root))
        assertNull(policy.check(shell("settings get system screen_brightness"), ExternalPrivilegeBridgePrivilege.Root))
    }

    @Test
    fun `direct package attacks against rikkahub shizuku and sui are rejected`() {
        listOf(
            argv("/system/bin/am", "force-stop", "me.rerere.rikkahub"),
            argv("/system/bin/pm", "clear", "moe.shizuku.privileged.api"),
            shell("pm uninstall rikka.sui"),
            shell("cmd package set-enabled-setting me.rerere.rikkahub disabled"),
        ).forEach { input ->
            assertEquals("COMMAND_REJECTED", policy.check(input, ExternalPrivilegeBridgePrivilege.Root)?.code)
        }
    }

    @Test
    fun `run as and root access to rikkahub private data are rejected`() {
        assertEquals(
            "COMMAND_REJECTED",
            policy.check(
                argv("/system/bin/run-as", "me.rerere.rikkahub", "ls"),
                ExternalPrivilegeBridgePrivilege.Shell,
            )?.code,
        )
        assertEquals(
            "COMMAND_REJECTED",
            policy.check(
                shell("cat /data/user/0/me.rerere.rikkahub/files/datastore/agent_safety.preferences_pb"),
                ExternalPrivilegeBridgePrivilege.Root,
            )?.code,
        )
        assertNull(
            policy.check(
                shell("ls /data/user/0/com.example.notes/cache"),
                ExternalPrivilegeBridgePrivilege.Root,
            ),
        )
    }

    private fun argv(executable: String, vararg args: String) = PrivilegedCommandInput(
        mode = PrivilegedCommandMode.ARGV,
        executable = executable,
        arguments = args.toList(),
    )

    private fun shell(command: String) = PrivilegedCommandInput(
        mode = PrivilegedCommandMode.SHELL,
        command = command,
    )
}
