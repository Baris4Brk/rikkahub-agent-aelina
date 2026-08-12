package me.rerere.rikkahub.learning.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ShadowIsolationTest {
    @Test
    fun p1PolicyAndRetrievalAreNotImportedIntoProviderRequestCompiler() {
        val root = appRoot()
        val handler = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt"),
        )
        val forbidden = listOf(
            "learning.policy",
            "learning.retrieval",
            "learning.reflection",
            "PolicyRetriever",
            "PolicyCandidateDraft",
        )
        forbidden.forEach { token -> assertFalse("P1 shadow leaked into provider path: $token", token in handler) }
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
