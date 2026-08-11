package me.rerere.rikkahub.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestBreakdownDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val fingerprintKey = ByteArray(32) { index -> index.toByte() }

    @Test
    fun `breakdown attributes messages and schemas without retaining content`() {
        val secretUserText = "private-user-message-7231"
        val secretMemoryText = "private-memory-8842"
        val secretToolDescription = "private-tool-description-9953"
        val messages = listOf(
            UIMessage.system("system instructions"),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(secretUserText))),
        )
        val tools = listOf(
            Tool(
                name = "list_active_notifications",
                description = secretToolDescription,
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("limit", buildJsonObject { put("type", "integer") })
                        },
                    )
                },
                execute = { emptyList() },
            ),
        )

        val breakdown = RequestBreakdownDiagnostic.create(
            generationId = "conversation-id-that-must-be-hashed",
            providerCallIndex = 1,
            modelId = "test-model",
            providerType = "test-provider",
            requestMode = "normal:stream",
            finalMessages = messages,
            tools = tools,
            assistantPrompt = "system instructions",
            userIdentityPrompt = "user identity",
            toolSystemPrompts = emptyList(),
            memoryPrompt = secretMemoryText,
            recentChatsPrompt = "recent-chat-title",
            dynamicSystemAddendum = "surface-state",
            memoryCount = 1,
            enabledSkillNames = listOf("agent-core"),
            toolFastLaneShortcutLibraryCount = 27,
            toolFastLaneInjectedSchemaCount = 5,
            toolFastLaneBundleId = "phone_status_full",
        )
        RequestBreakdownDiagnosticsStore.write(temporaryFolder.root, breakdown)

        val output = RequestBreakdownDiagnosticsStore.outputFile(temporaryFolder.root).readText()
        val historyOutput = RequestBreakdownDiagnosticsStore.historyOutputFile(temporaryFolder.root).readText()
        assertFalse(output.contains(secretUserText))
        assertFalse(output.contains(secretMemoryText))
        assertFalse(output.contains(secretToolDescription))
        assertFalse(output.contains("conversation-id-that-must-be-hashed"))
        assertFalse(historyOutput.contains(secretUserText))
        assertFalse(historyOutput.contains(secretMemoryText))
        assertFalse(historyOutput.contains(secretToolDescription))
        assertFalse(historyOutput.contains("conversation-id-that-must-be-hashed"))
        assertFalse(historyOutput.contains("list_active_notifications"))
        assertFalse(historyOutput.contains("agent-core"))
        assertFalse(historyOutput.contains("phone_status_full"))
        assertTrue(output.contains("list_active_notifications"))
        assertTrue(output.contains("agent-core"))
        assertTrue(output.contains("estimated_request_tokens"))
        assertTrue(output.contains("tool_fast_lane_shortcut_library_count"))
        assertTrue(output.contains("phone_status_full"))
        assertFalse(output.contains("semantic_segment_fingerprints"))
        assertFalse(output.contains("tool_manifest_fingerprint"))
        assertEquals(
            breakdown.estimatedMessageTokens + breakdown.estimatedToolSchemaTokens,
            breakdown.estimatedRequestTokens,
        )
    }

    @Test
    fun `provider usage is added without changing attribution`() {
        val base = RequestBreakdownDiagnostic.create(
            generationId = "generation",
            providerCallIndex = 2,
            modelId = "model",
            providerType = "provider",
            requestMode = "normal:stream",
            finalMessages = listOf(UIMessage.user("hello")),
            tools = emptyList(),
            assistantPrompt = "",
            userIdentityPrompt = "",
            toolSystemPrompts = emptyList(),
            memoryPrompt = "",
            recentChatsPrompt = "",
            dynamicSystemAddendum = null,
            memoryCount = 0,
            enabledSkillNames = emptyList(),
        )

        val updated = base.withProviderUsage(
            promptTokens = 40_123,
            cachedTokens = 32_000,
            completionTokens = 18,
        )

        assertEquals(40_123, updated.providerPromptTokens)
        assertEquals(32_000, updated.providerCachedTokens)
        assertEquals(8_123, updated.providerFreshPromptTokens)
        assertEquals(7_975, updated.providerCachedPromptBasisPoints)
        assertEquals(18, updated.providerCompletionTokens)
        assertEquals(base.wireSections, updated.wireSections)
    }

    @Test
    fun `pre-provider snapshot does not rewrite bounded history`() {
        val diagnostic = breakdown(callIndex = 1)

        RequestBreakdownDiagnosticsStore.write(
            temporaryFolder.root,
            diagnostic,
            includeHistory = false,
        )

        assertTrue(RequestBreakdownDiagnosticsStore.outputFile(temporaryFolder.root).isFile)
        assertFalse(RequestBreakdownDiagnosticsStore.historyOutputFile(temporaryFolder.root).exists())
    }

    @Test
    fun `zero cache usage remains an explicit measured value`() {
        val updated = breakdown(callIndex = 1).withProviderUsage(
            promptTokens = 12_345,
            cachedTokens = 0,
            completionTokens = 9,
        )

        assertEquals(0, updated.providerCachedTokens)
        assertEquals(12_345, updated.providerFreshPromptTokens)
        assertEquals(0, updated.providerCachedPromptBasisPoints)
        assertEquals(0, updated.toJson()["provider_cached_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `semantic segments expose append-only prefix without retaining their content`() {
        val user = UIMessage.user("private-task-prefix")
        val firstAssistant = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Reasoning("reasoning-one"),
            executedTool(1),
        ))
        val first = breakdown(
            callIndex = 1,
            messages = listOf(user, firstAssistant),
        )
        val second = breakdown(
            callIndex = 2,
            messages = listOf(
                user,
                firstAssistant.copy(parts = firstAssistant.parts + listOf(
                    UIMessagePart.Reasoning("reasoning-two"),
                    executedTool(2),
                )),
            ),
        ).withPreviousRequest(first)

        assertEquals(
            "first=${first.semanticSegmentFingerprints}; second=${second.semanticSegmentFingerprints}",
            first.semanticSegmentFingerprints.size,
            second.commonPrefixSegmentCount,
        )
        assertEquals(first.estimatedMessageTokens, second.commonPrefixEstimatedTokens)
        assertEquals(true, second.previousToolManifestMatched)
        val json = second.toJson().toString()
        assertFalse(json.contains("private-task-prefix"))
        assertFalse(json.contains("reasoning-one"))
        assertFalse(json.contains("output-1"))
        assertFalse(json.contains("semantic_segment_fingerprints"))
    }

    @Test
    fun `adding a tool result preserves the preceding tool call prefix`() {
        val user = UIMessage.user("task")
        val toolCall = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "read_state",
            input = "{}",
        )
        val firstAssistant = UIMessage.assistant("").copy(parts = listOf(toolCall))
        val secondAssistant = firstAssistant.copy(parts = listOf(
            toolCall.copy(output = listOf(UIMessagePart.Text("fresh state"))),
        ))
        val first = breakdown(callIndex = 1, messages = listOf(user, firstAssistant))
        val second = breakdown(callIndex = 2, messages = listOf(user, secondAssistant))
            .withPreviousRequest(first)

        assertEquals(first.estimatedRequestTokens, second.commonPrefixEstimatedTokens)
        assertTrue(second.commonPrefixRequestBasisPoints!! < 10_000)
    }

    @Test
    fun `UI identity and lifecycle fields do not break a provider semantic prefix`() {
        val firstMessages = listOf(
            UIMessage.system("stable system"),
            UIMessage.assistant("stable answer"),
        )
        val secondMessages = listOf(
            UIMessage.system("stable system").copy(state = UIMessageState.STREAMING),
            UIMessage.assistant("stable answer").copy(
                translation = "UI-only translation",
                state = UIMessageState.INTERRUPTED,
            ),
        )
        val first = breakdown(callIndex = 1, messages = firstMessages)
        val second = breakdown(callIndex = 2, messages = secondMessages)
            .withPreviousRequest(first)

        assertEquals(first.semanticSegmentFingerprints.size, second.commonPrefixSegmentCount)
        assertEquals(first.estimatedMessageTokens, second.commonPrefixEstimatedTokens)
        assertEquals(10_000, second.commonPrefixRequestBasisPoints)
    }

    @Test
    fun `tool manifest is the first weighted request prefix segment`() {
        fun tool(description: String) = Tool(
            name = "stable_tool",
            description = description,
            parameters = { InputSchema.Obj(buildJsonObject {}) },
            execute = { emptyList() },
        )
        val first = breakdown(callIndex = 1, tools = listOf(tool("version one")))
        val stable = breakdown(callIndex = 2, tools = listOf(tool("version one")))
            .withPreviousRequest(first)
        val changed = breakdown(callIndex = 3, tools = listOf(tool("version two")))
            .withPreviousRequest(stable)

        assertEquals(first.estimatedRequestTokens, stable.commonPrefixEstimatedTokens)
        assertEquals(10_000, stable.commonPrefixRequestBasisPoints)
        assertEquals(0, changed.commonPrefixEstimatedTokens)
        assertEquals(0, changed.commonPrefixRequestBasisPoints)
        assertEquals(false, changed.previousToolManifestMatched)
    }

    @Test
    fun `built-in tools participate in manifest identity and request token weight`() {
        val withoutBuiltIn = breakdown(callIndex = 1)
        val withSearch = breakdown(
            callIndex = 2,
            builtInTools = setOf(BuiltInTools.Search),
        ).withPreviousRequest(withoutBuiltIn)
        val stableSearch = breakdown(
            callIndex = 3,
            builtInTools = setOf(BuiltInTools.Search),
        ).withPreviousRequest(withSearch)

        assertEquals(0, withSearch.commonPrefixEstimatedTokens)
        assertEquals(false, withSearch.previousToolManifestMatched)
        assertEquals(128, withSearch.estimatedToolSchemaTokens)
        assertTrue("built_in:search" in withSearch.toolNames)
        assertEquals(withSearch.estimatedRequestTokens, stableSearch.commonPrefixEstimatedTokens)
        assertEquals(10_000, stableSearch.commonPrefixRequestBasisPoints)
    }

    @Test
    fun `history is bounded and provider usage upserts the same call`() {
        repeat(130) { index ->
            RequestBreakdownDiagnosticsStore.write(
                temporaryFolder.root,
                breakdown(callIndex = index + 1),
            )
        }
        RequestBreakdownDiagnosticsStore.write(
            temporaryFolder.root,
            breakdown(callIndex = 130).withProviderUsage(
                promptTokens = 1_000,
                cachedTokens = 0,
                completionTokens = 10,
            ),
        )

        val root = Json.parseToJsonElement(
            RequestBreakdownDiagnosticsStore.historyOutputFile(temporaryFolder.root).readText(),
        ).jsonObject
        val entries = root.getValue("entries").jsonArray

        assertEquals(128, entries.size)
        assertEquals(3, entries.first().jsonObject.getValue("provider_call_index").jsonPrimitive.content.toInt())
        val last = entries.last().jsonObject
        assertEquals(130, last.getValue("provider_call_index").jsonPrimitive.content.toInt())
        assertEquals(0, last.getValue("provider_cached_tokens").jsonPrimitive.content.toInt())
        assertEquals(1_000, last.getValue("provider_fresh_prompt_tokens").jsonPrimitive.content.toInt())
    }

    @Test
    fun `context budget is shadow only and 126k stays below a 1m high water mark`() {
        val roomy = breakdown(callIndex = 1)
            .copy(estimatedRequestTokens = 126_349)
            .withContextBudget(
                effectiveContextWindowTokens = 1_000_000,
                requestedOutputTokens = null,
            )
        val constrained = roomy.withContextBudget(
            effectiveContextWindowTokens = 128_000,
            requestedOutputTokens = null,
        )

        assertEquals(1_000_000, roomy.contextWindowTokens)
        assertEquals(false, roomy.contextHighWatermarkReached)
        assertEquals(128_000, constrained.contextWindowTokens)
        assertEquals(true, constrained.contextHighWatermarkReached)
        assertEquals(126_349, constrained.estimatedRequestTokens)
    }

    private fun breakdown(
        callIndex: Int,
        messages: List<UIMessage> = listOf(UIMessage.user("hello")),
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet(),
    ): RequestBreakdownDiagnostic = RequestBreakdownDiagnostic.create(
        generationId = "generation",
        providerCallIndex = callIndex,
        modelId = "model",
        providerType = "provider",
        requestMode = "normal:stream",
        finalMessages = messages,
        tools = tools,
        builtInTools = builtInTools,
        assistantPrompt = "",
        userIdentityPrompt = "",
        toolSystemPrompts = emptyList(),
        memoryPrompt = "",
        recentChatsPrompt = "",
        dynamicSystemAddendum = null,
        memoryCount = 0,
        enabledSkillNames = emptyList(),
        fingerprintKey = fingerprintKey,
    )

    private fun executedTool(index: Int): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "call-$index",
        toolName = "tool_$index",
        input = "{\"index\":$index}",
        output = listOf(UIMessagePart.Text("output-$index")),
    )
}
