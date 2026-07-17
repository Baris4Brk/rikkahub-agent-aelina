package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SelfPreservationPolicyTest {
    private val policy = SelfPreservationPolicy(
        applicationId = "me.rerere.rikkahub.test",
        appDataRoots = setOf("/data/user/0/me.rerere.rikkahub.test"),
    )

    @Test
    fun `package manager mutations cannot target the protected app`() {
        listOf(
            "pm uninstall me.rerere.rikkahub.test",
            "pm disable-user --user 0 me.rerere.rikkahub.test",
            "pm suspend me.rerere.rikkahub.test",
            "am force-stop me.rerere.rikkahub.test",
            "pm clear me.rerere.rikkahub.test",
            "pm revoke me.rerere.rikkahub.test android.permission.CAMERA",
            "pm hide me.rerere.rikkahub.test",
            "appops set me.rerere.rikkahub.test CAMERA deny",
        ).forEach { command ->
            assertNotNull(policy.checkShellCommand(command))
        }

        assertNull(policy.checkShellCommand("pm clear com.example.other"))
        assertNull(policy.checkShellCommand("pm clear me.rerere.rikkahub.test.helper"))
        assertNull(policy.checkShellCommand("pm clear com.example.me.rerere.rikkahub.test"))
        assertNull(policy.checkShellCommand("pm enable me.rerere.rikkahub.test"))
        assertNull(policy.checkShellCommand("appops get me.rerere.rikkahub.test"))
    }

    @Test
    fun `structured mutation seams protect only the app and its core data`() {
        assertNotNull(policy.checkPackageMutation("ME.RERERE.RIKKAHUB.TEST"))
        assertNull(policy.checkPackageMutation("com.example.other"))

        assertNotNull(
            policy.checkAppPrivateMutation(
                "/data/user/0/me.rerere.rikkahub.test/databases/rikkahub.db",
            ),
        )
        assertNotNull(
            policy.checkAppPrivateMutation(
                "/data/user/0/me.rerere.rikkahub.test/datastore/settings.preferences_pb",
            ),
        )
        assertNull(
            policy.checkAppPrivateMutation(
                "/data/user/0/me.rerere.rikkahub.test/files/exports/report.md",
            ),
        )
    }

    @Test
    fun `cmd package aliases cannot bypass self preservation`() {
        assertNotNull(policy.checkShellCommand("cmd package uninstall me.rerere.rikkahub.test"))
        assertNotNull(policy.checkShellCommand("cmd package clear me.rerere.rikkahub.test"))
        assertNotNull(
            policy.checkShellCommand(
                "cmd package set-component-enabled-setting " +
                    "me.rerere.rikkahub.test/.assistant.SystemAssistantOverlayEntryActivity disabled",
            ),
        )
        assertNotNull(policy.checkShellCommand("cmd activity force-stop me.rerere.rikkahub.test"))
    }

    @Test
    fun `core app data cannot be mutated through shell commands`() {
        listOf(
            "rm -rf /data/user/0/me.rerere.rikkahub.test/databases",
            "truncate -s 0 /data/user/0/me.rerere.rikkahub.test/datastore/settings.preferences_pb",
            "mv /data/user/0/me.rerere.rikkahub.test/shared_prefs/settings.xml /tmp/settings.xml",
            "sqlite3 /data/user/0/me.rerere.rikkahub.test/databases/rikkahub.db 'drop table messages'",
            "echo broken > /data/user/0/me.rerere.rikkahub.test/files/datastore/settings.pb",
            "rm /data/user/0/me.rerere.rikkahub.test/files/../databases/rikkahub.db",
            "/system/bin/rm -rf /data/user/0/me.rerere.rikkahub.test/databases",
        ).forEach { command ->
            assertNotNull(policy.checkShellCommand(command))
        }

        assertNull(
            policy.checkShellCommand(
                "sqlite3 /data/user/0/me.rerere.rikkahub.test/databases/rikkahub.db 'select count(*) from messages'",
            ),
        )
        assertNull(policy.checkShellCommand("rm -rf /workspace/build/tmp"))
        assertNull(
            policy.checkShellCommand(
                "cat /data/user/0/me.rerere.rikkahub.test/databases/rikkahub.db; echo ok > /data/local/tmp/result",
            ),
        )
    }
}
