package me.rerere.rikkahub.setup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsSetupTransactionBackendTest {
    @Test
    fun `planning rejects missing models and models whose provider is disabled`() = runBlocking {
        val assistantId = Uuid.random()
        val disabledModelId = Uuid.random()
        val store = InMemorySetupConfigurationStore(
            Settings(
                providers = listOf(
                    ProviderSetting.OpenAI(
                        enabled = false,
                        models = listOf(
                            Model(
                                id = disabledModelId,
                                modelId = "disabled-model",
                                displayName = "Disabled",
                            ),
                        ),
                    ),
                ),
                assistants = listOf(Assistant(id = assistantId, name = "Clone")),
                assistantId = assistantId,
            ),
        )
        val coordinator = SetupTransactionCoordinator(
            SettingsSetupTransactionBackend(store, emptyResources()),
            SetupAuditLedger.NONE,
        )
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())

        val missing = coordinator.plan(
            owner,
            listOf(SetupChange.AssistantChatModel(assistantId, Uuid.random())),
        )
        val disabled = coordinator.plan(
            owner,
            listOf(SetupChange.AssistantChatModel(assistantId, disabledModelId)),
        )

        assertEquals("MODEL_NOT_FOUND", missing.code)
        assertEquals("MODEL_PROVIDER_DISABLED", disabled.code)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun `planning rejects workspace skill and MCP bindings that are not already available`() = runBlocking {
        val assistantId = Uuid.random()
        val store = InMemorySetupConfigurationStore(
            Settings(
                assistants = listOf(Assistant(id = assistantId, name = "Clone")),
                assistantId = assistantId,
            ),
        )
        val coordinator = SetupTransactionCoordinator(
            SettingsSetupTransactionBackend(store, emptyResources()),
            SetupAuditLedger.NONE,
        )
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())

        val workspace = coordinator.plan(
            owner,
            listOf(SetupChange.AssistantWorkspace(assistantId, Uuid.random())),
        )
        val skill = coordinator.plan(
            owner,
            listOf(SetupChange.AssistantSkills(assistantId, setOf("not-installed"))),
        )
        val mcp = coordinator.plan(
            owner,
            listOf(SetupChange.AssistantMcpServers(assistantId, setOf(Uuid.random()))),
        )

        assertEquals("WORKSPACE_NOT_FOUND", workspace.code)
        assertEquals("SKILL_NOT_FOUND", skill.code)
        assertEquals("MCP_SERVER_NOT_FOUND", mcp.code)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun `supported typed fields apply through one-field CAS and verify referenced resources`() = runBlocking {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val workspaceId = Uuid.random()
        val mcpId = Uuid.random()
        val model = Model(id = modelId, modelId = "test-model", displayName = "Test")
        val store = InMemorySetupConfigurationStore(
            Settings(
                providers = listOf(ProviderSetting.OpenAI(enabled = true, models = listOf(model))),
                assistants = listOf(Assistant(id = assistantId, name = "Clone")),
                assistantId = assistantId,
                mcpServers = listOf(McpServerConfig.SseTransportServer(id = mcpId)),
            ),
        )
        val backend = SettingsSetupTransactionBackend(
            configurationStore = store,
            resources = object : SetupResourceCatalog {
                override suspend fun workspaceExists(id: Uuid): Boolean = id == workspaceId
                override suspend fun installedSkillNames(): Set<String> = setOf("deep-research")
            },
        )
        val coordinator = SetupTransactionCoordinator(backend, SetupAuditLedger.NONE)
        val owner = SetupOwner(assistantId.toString(), Uuid.random().toString())
        val planned = coordinator.plan(
            owner,
            listOf(
                SetupChange.AssistantChatModel(assistantId, modelId),
                SetupChange.AssistantWorkspace(assistantId, workspaceId),
                SetupChange.AssistantTool(assistantId, "battery", true),
                SetupChange.AssistantSkills(assistantId, setOf("deep-research")),
                SetupChange.AssistantMcpServers(assistantId, setOf(mcpId)),
                SetupChange.AssistantFlag(assistantId, SetupAssistantFlag.ENABLE_MEMORY, true),
                SetupChange.AppFlag(SetupAppFlag.DYNAMIC_COLOR, false),
                SetupChange.AppModel(SetupAppModel.TITLE_MODEL, modelId),
            ),
        )

        val applied = coordinator.apply(owner, planned.transaction!!.id)
        val verified = coordinator.verify(owner, planned.transaction.id)
        val settings = store.settings
        val assistant = settings.assistants.single()

        assertTrue(applied.ok)
        assertTrue(verified.ok)
        assertEquals(modelId, assistant.chatModelId)
        assertEquals(workspaceId, assistant.workspaceId)
        assertTrue(LocalToolOption.Battery in assistant.localTools)
        assertEquals(setOf("deep-research"), assistant.enabledSkills)
        assertEquals(setOf(mcpId), assistant.mcpServers)
        assertTrue(assistant.enableMemory)
        assertEquals(false, settings.dynamicColor)
        assertEquals(modelId, settings.titleModelId)
    }

    private class InMemorySetupConfigurationStore(
        initial: Settings,
    ) : SetupConfigurationStore {
        private val lock = Mutex()
        var settings: Settings = initial
        var updateCalls: Int = 0

        override suspend fun snapshot(): Settings = lock.withLock { settings }

        override suspend fun updateIf(
            predicate: (Settings) -> Boolean,
            transform: (Settings) -> Settings,
        ): Boolean = lock.withLock {
            updateCalls++
            if (!predicate(settings)) return@withLock false
            settings = transform(settings)
            true
        }
    }

    private fun emptyResources() = object : SetupResourceCatalog {
        override suspend fun workspaceExists(id: Uuid): Boolean = false
        override suspend fun installedSkillNames(): Set<String> = emptySet()
    }
}
