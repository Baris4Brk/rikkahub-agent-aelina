package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.privilege.PrivilegedCommandResult
import me.rerere.workspace.WorkspaceStopAllResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStopCoordinatorTest {
    @Test
    fun `emergency stop is persisted before privileged commands are cancelled`() = runBlocking {
        val events = mutableListOf<String>()

        val result = activateEmergencyStop(
            persistStop = { events += "persisted" },
            cancelCommands = {
                events += "bridge_cancelled"
                PrivilegedCommandResult(true, "COMMAND_CANCELLED", "Stopped.")
            },
            stopWorkspaceProcesses = {
                events += "workspace_stopped"
                WorkspaceStopAllResult(true, "PROCESS_STOPPED")
            },
        )

        assertEquals("persisted", events.first())
        assertEquals(setOf("bridge_cancelled", "workspace_stopped"), events.drop(1).toSet())
        assertEquals("COMMAND_CANCELLED", result.bridgeResult?.code)
        assertEquals("PROCESS_STOPPED", result.workspaceResult?.code)
        assertTrue(result.ok)
    }

    @Test
    fun `one backend failure does not prevent the other backend from stopping`() = runBlocking {
        val events = mutableListOf<String>()

        val result = activateEmergencyStop(
            persistStop = { events += "persisted" },
            cancelCommands = {
                events += "bridge_failed"
                error("bridge unavailable")
            },
            stopWorkspaceProcesses = {
                events += "workspace_stopped"
                WorkspaceStopAllResult(true, "PROCESS_STOPPED")
            },
        )

        assertEquals("persisted", events.first())
        assertEquals(setOf("bridge_failed", "workspace_stopped"), events.drop(1).toSet())
        assertEquals("bridge unavailable", result.bridgeError)
        assertEquals("PROCESS_STOPPED", result.workspaceResult?.code)
        assertFalse(result.ok)
    }

    @Test
    fun `additional participants run after persisted core stops and failures are isolated`() = runBlocking {
        val events = mutableListOf<String>()

        val result = activateEmergencyStop(
            persistStop = { events += "persisted" },
            cancelCommands = {
                events += "bridge_cancelled"
                PrivilegedCommandResult(true, "COMMAND_CANCELLED", "Stopped.")
            },
            stopWorkspaceProcesses = {
                events += "workspace_stopped"
                WorkspaceStopAllResult(true, "PROCESS_STOPPED")
            },
            additionalParticipants = listOf(
                emergencyStopParticipant("chat") {
                    events += "chat_stopped"
                    EmergencyStopParticipantResult("chat", true, "CHAT_STOPPED", "Stopped.")
                },
                emergencyStopParticipant("termux") {
                    events += "termux_failed"
                    error("termux unavailable")
                },
                emergencyStopParticipant("subagents") {
                    events += "subagents_stopped"
                    EmergencyStopParticipantResult("subagents", true, "SUBAGENTS_STOPPED", "Stopped.")
                },
            ),
        )

        assertEquals("persisted", events.first())
        assertEquals(
            setOf(
                "bridge_cancelled",
                "workspace_stopped",
                "chat_stopped",
                "termux_failed",
                "subagents_stopped",
            ),
            events.drop(1).toSet(),
        )
        assertEquals("CHAT_STOPPED", result.participants.getValue("chat").result?.code)
        assertEquals("termux unavailable", result.participants.getValue("termux").error)
        assertEquals("SUBAGENTS_STOPPED", result.participants.getValue("subagents").result?.code)
        assertFalse(result.ok)
    }
}
