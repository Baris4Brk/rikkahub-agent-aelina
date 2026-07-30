package me.rerere.rikkahub.toolcatalog

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.DefaultToolExecutionPolicyResolver
import me.rerere.rikkahub.data.ai.execution.DefaultToolSecurityDescriptorResolver
import me.rerere.rikkahub.data.ai.execution.InternalToolSecurityCatalog
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.execution.VerificationState
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {
    @Test
    fun `catalogue snapshot covers every runtime definition exactly once`() {
        val expectedNames = CapabilityCatalog.allCapabilities()
            .flatMap { it.toolNames }
            .toSet() + InternalToolSecurityCatalog.ALL
        val surface = ToolSurfaceBuilder.staticCapabilityBaseline()

        assertEquals(expectedNames, surface.snapshot.entries.map { it.toolName }.toSet())
        assertEquals(expectedNames.sorted(), ToolSurfaceBuilder.staticToolNames())
        assertTrue(surface.snapshot.entries.all { it.currentlyInjectable })
    }

    @Test
    fun `discovery exposes bootstrap only until an entry is explicitly opened`() = runBlocking {
        val definitions = listOf(tool("ask_user"), tool("get_battery_status"), tool("set_volume"))
        val editor = ToolExperienceEditor { _, _, _, _, _ -> ToolExperienceEditResult.Updated(1) }
        val session = ToolDiscoverySession(ToolSurfaceBuilder.snapshot(definitions), experienceEditor = editor)

        val initial = session.providerTools(definitions, emptySet())
        assertTrue(initial.map(Tool::name).containsAll(
            listOf(
                ToolDiscoverySession.TOOL_CATALOG_SEARCH,
                ToolDiscoverySession.TOOL_CATALOG_LIST,
                ToolDiscoverySession.TOOL_CATALOG_OPEN,
                ToolDiscoverySession.TOOL_EXPERIENCE_UPDATE,
                "ask_user",
            ),
        ))
        assertFalse(initial.any { it.name == "get_battery_status" })

        val id = session.snapshot().entry("get_battery_status")!!.id
        initial.single { it.name == ToolDiscoverySession.TOOL_CATALOG_OPEN }.execute(
            buildJsonObject { put("ids", JsonArray(listOf(JsonPrimitive(id)))) },
        )

        val afterOpen = session.providerTools(definitions, emptySet())
        assertTrue(afterOpen.any { it.name == "get_battery_status" })
        assertFalse(afterOpen.any { it.name == "set_volume" })
        // `ask_user` is a fixed real schema; the metric reports actual provider schemas,
        // not only entries opened through the directory.
        assertEquals(2, session.metrics().selectedSchemaCount)
    }

    @Test
    fun `ordinary open selection remains capped while pinned calls survive`() = runBlocking {
        val definitions = listOf(tool("ask_user")) + (0..7).map { tool("device_tool_$it") }
        val session = ToolDiscoverySession(ToolSurfaceBuilder.snapshot(definitions))
        val open = session.providerTools(definitions, emptySet())
            .single { it.name == ToolDiscoverySession.TOOL_CATALOG_OPEN }
        val allIds = session.snapshot().entries
            .filterNot { it.toolName == "ask_user" }
            .map { JsonPrimitive(it.id) }
        open.execute(buildJsonObject { put("ids", JsonArray(allIds.take(4))) })
        open.execute(buildJsonObject { put("ids", JsonArray(allIds.drop(4).take(4))) })

        val selected = session.providerTools(definitions, emptySet())
            .filter { it.name == "ask_user" || it.name.startsWith("device_tool_") }
        assertEquals(6, selected.size)
        assertEquals(6, session.metrics().selectedSchemaCount)

        val pinned = session.providerTools(definitions, setOf("device_tool_0", "device_tool_1", "device_tool_2"))
            .filter { it.name.startsWith("device_tool_") }
        assertTrue(pinned.map(Tool::name).containsAll(listOf("device_tool_0", "device_tool_1", "device_tool_2")))
    }

    @Test
    fun `experience prose rejects raw operational data`() {
        assertTrue(ToolExperienceContentPolicy.normalize(
            title = "Safe verification",
            body = "Open the current definition, confirm permission status, then verify the result.",
            tags = listOf("verification"),
        ) != null)
        assertEquals(null, ToolExperienceContentPolicy.normalize(
            title = "Command",
            body = "Run curl https://example.test/private?token=secret",
            tags = emptyList(),
        ))
        assertEquals(null, ToolExperienceContentPolicy.normalize(
            title = "Path",
            body = "Read /data/data/example/private.txt before continuing.",
            tags = emptyList(),
        ))
        assertEquals(null, ToolExperienceContentPolicy.normalize(
            title = "Contact",
            body = "Send the result to user@example.test or 13800138000.",
            tags = emptyList(),
        ))
        assertEquals(null, ToolExperienceContentPolicy.normalize(
            title = "Credential",
            body = "Use sk-abcdefghijklmnopqrstuv when verifying the connection.",
            tags = emptyList(),
        ))
    }

    @Test
    fun `catalogue metadata never copies factory documentation or operational values`() {
        val definition = Tool(
            name = "workspace_shell",
            description = "Run curl https://example.test/private?token=secret from /data/data/example.",
            execute = { emptyList() },
        )

        val entry = ToolCatalogSnapshot.fromDefinitions(listOf(definition)).entry("workspace_shell")!!

        assertFalse(entry.summary.contains("https://"))
        assertFalse(entry.summary.contains("/data/data/"))
        assertFalse(entry.summary.contains("token=secret"))
        assertTrue(entry.summary.contains("workspace_shell"))
    }

    @Test
    fun `experience classifier rejects explicit failures and promotes verified evidence`() {
        assertEquals(
            null,
            ToolExperienceOutcomeClassifier.classify(
                listOf(UIMessagePart.Text("not a JSON envelope"), UIMessagePart.Text("{\"error\":\"failed\"}")),
                VerificationState.RUNTIME_CONFIRMED,
            ),
        )
        assertEquals(
            ToolExperienceOutcomeKind.STANDARD_SUCCESS,
            ToolExperienceOutcomeClassifier.classify(
                listOf(UIMessagePart.Text("{\"ok\":true}")),
                VerificationState.DATABASE_CONFIRMED,
            ),
        )
        assertEquals(
            ToolExperienceOutcomeKind.HOST_COMPLETED,
            ToolExperienceOutcomeClassifier.classify(
                listOf(UIMessagePart.Text("plain completion")),
                VerificationState.DATABASE_CONFIRMED,
            ),
        )
    }

    @Test
    fun `directory helpers have explicit runtime security and execution policy`() {
        val context = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "test",
            callOrigin = ToolCallOrigin.LocalChat,
        )
        val security = DefaultToolSecurityDescriptorResolver()
        val policy = DefaultToolExecutionPolicyResolver()
        setOf(
            ToolDiscoverySession.TOOL_CATALOG_SEARCH,
            ToolDiscoverySession.TOOL_CATALOG_LIST,
            ToolDiscoverySession.TOOL_CATALOG_OPEN,
            ToolDiscoverySession.TOOL_EXPERIENCE_UPDATE,
        ).forEach { toolName ->
            assertTrue(security.resolve(toolName, context) != null)
            assertFalse(policy.resolve(toolName, buildJsonObject {}, context) == ToolExecutionPolicy.UNKNOWN)
        }
    }

    @Test
    fun `every built-in internal host tool has an executable security registration`() {
        val context = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "test",
            callOrigin = ToolCallOrigin.LocalChat,
        )
        val security = DefaultToolSecurityDescriptorResolver()
        val policy = DefaultToolExecutionPolicyResolver()

        InternalToolSecurityCatalog.ALL.forEach { toolName ->
            assertTrue("missing descriptor for $toolName", security.resolve(toolName, context) != null)
            assertFalse(
                "missing policy for $toolName",
                policy.resolve(toolName, buildJsonObject {}, context) == ToolExecutionPolicy.UNKNOWN,
            )
        }
    }

    @Test
    fun `baseline report contains source facts but no runtime values`() {
        val report = ToolInventoryReport.renderCompiledBaseline()
        assertTrue(report.contains("CapabilityCatalog"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("/data/data/"))
    }

    /** Invoked explicitly by the release workflow; ordinary unit tests never write a desktop file. */
    @Test
    fun `explicit request exports desktop baseline through the production renderer`() {
        val directory = System.getProperty("rikkahub.exportDesktop")
            ?: System.getenv("RIKKAHUB_EXPORT_DESKTOP")
            ?: return
        val output = ToolInventoryReport.writeDesktopBaseline(File(directory))
        assertTrue(output.isFile)
        assertTrue(output.readText().contains("RikkaHub 第二用户"))
    }

    private fun tool(name: String) = Tool(
        name = name,
        description = "Safe $name capability.",
        execute = { listOf(UIMessagePart.Text("{}")) },
    )
}
