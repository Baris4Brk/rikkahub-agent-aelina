package me.rerere.rikkahub.learning.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ShadowIsolationTest {
    @Test
    fun stageDShadowNeverBecomesProviderRecallAndStageERequiresReviewOptIn() {
        val root = appRoot()
        val handler = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt"),
        )
        val facade = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt"),
        )
        val chatService = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/service/ChatService.kt"),
        )
        val shadowBlock = handler.substringAfter("// Stage D is content-free observation only")
            .substringBefore("val policyRetrieval = if")
        val forbiddenInShadow = listOf(
            "LearnedPolicySource",
            "compileRecallPrompt",
            "createSystemPromptLayout",
            "PolicyRetriever",
            "PolicyCandidateDraft",
        )
        forbiddenInShadow.forEach { token ->
            assertFalse("Stage-D shadow leaked into provider projection: $token", token in shadowBlock)
        }
        assertTrue("Stage D must explicitly remain content-free", "The result never enters Recall" in shadowBlock)
        assertTrue(
            "Stage E must require the exact assistant's reviewed-policy opt-in",
            "assistantPolicyOptIn = assistant.reviewedPolicyInjectionEnabled" in handler,
        )
        val stageDAuthorityBlock = chatService
            .substringAfter("// Stage D needs the exact command authority")
            .substringBefore("var memoryRetrievalTraceId")
        assertFalse(
            "Stage-D authority identity must not depend on the Stage-E review opt-in",
            "reviewedPolicyInjectionEnabled" in stageDAuthorityBlock,
        )
        assertTrue(
            "Stage D must fence the authoritative branch revision",
            "branchAnchorMessageRevision = branchAnchorRevision" in stageDAuthorityBlock,
        )
        assertTrue(
            "Feature-off construction must fail closed",
            "if (!flagsSource.policyInjectionEnabledFailClosed()) return empty()" in facade,
        )
    }

    @Test
    fun p1BusinessPackagesCannotDependOnGenerationHandlerOrToolRuntime() {
        val root = appRoot()
            .resolve("src/main/java/me/rerere/rikkahub/learning")
        val packages = listOf("episode", "trace", "reward", "reflection", "policy", "curation", "retrieval")
        packages.forEach { name ->
            val directory = root.resolve(name)
            if (!Files.exists(directory)) return@forEach
            Files.walk(directory).use { paths ->
                paths.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    val source = Files.readString(file)
                    assertFalse("$file imports GenerationHandler", "GenerationHandler" in source)
                    assertFalse("$file imports ToolRuntime", "ToolRuntime" in source)
                }
            }
        }
        assertTrue(true)
    }

    private fun appRoot(): Path {
        val working = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(working.resolve("app/src/main"))) working.resolve("app") else working
    }
}
