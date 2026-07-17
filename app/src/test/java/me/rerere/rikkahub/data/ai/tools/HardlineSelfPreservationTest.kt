package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardlineSelfPreservationTest {
    private val policy = SelfPreservationPolicy(
        applicationId = "me.rerere.rikkahub.test",
        appDataRoots = setOf("/data/user/0/me.rerere.rikkahub.test"),
    )

    @Test
    fun `default hardline policy protects the installed application id`() {
        assertNotNull(
            HardlineCommandGuard.checkTool(
                "workspace_shell",
                """{"command":"pm uninstall me.rerere.rikkahub"}""",
            ),
        )
    }

    @Test
    fun `workspace and termux reject self destructive commands without echoing them`() {
        val workspaceCommand = "pm clear me.rerere.rikkahub.test"
        val workspaceReason = HardlineCommandGuard.checkTool(
            toolName = "workspace_shell",
            inputJson = """{"command":"$workspaceCommand"}""",
            selfPreservationPolicy = policy,
        )
        assertNotNull(workspaceReason)
        assertFalse(workspaceReason!!.contains(workspaceCommand))

        val termuxCommand = "rm -rf /data/user/0/me.rerere.rikkahub.test/databases"
        val termuxReason = HardlineCommandGuard.checkTool(
            toolName = "termux_run_command",
            inputJson = """{"command":"$termuxCommand"}""",
            selfPreservationPolicy = policy,
        )
        assertNotNull(termuxReason)
        assertFalse(termuxReason!!.contains(termuxCommand))
    }

    @Test
    fun `ordinary workspace and other package commands remain allowed`() {
        assertNull(
            HardlineCommandGuard.checkTool(
                toolName = "workspace_shell",
                inputJson = """{"command":"./gradlew test"}""",
                selfPreservationPolicy = policy,
            ),
        )
        assertNull(
            HardlineCommandGuard.checkTool(
                toolName = "termux_run_command",
                inputJson = """{"command":"pm clear com.example.other"}""",
                selfPreservationPolicy = policy,
            ),
        )
    }
}
