package me.rerere.rikkahub.learning.verification

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static dependency fence: production verifier source stays pure Kotlin and fake-only. */
class WorkflowVerifierArchitectureTest {
    @Test
    fun `production verifier has no Tool Android IO network or runtime dependency`() {
        val root = locateVerificationSources()
        val files = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .sorted()
                .collect(Collectors.toList())
        }
        assertTrue(files.isNotEmpty())
        val source = files.joinToString("\n") { path ->
            Files.readString(path, StandardCharsets.UTF_8)
        }
        val compact = source.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        FORBIDDEN_SOURCE_PATTERNS.forEach { pattern ->
            assertFalse("Verifier production source contains forbidden dependency: $pattern", pattern in compact)
        }
        val bytecode = verifierClassBytes()
        FORBIDDEN_CLASS_NAMES.forEach { className ->
            assertFalse(
                "Verifier bytecode references forbidden production type: $className",
                bytecode.any { bytes -> className in String(bytes, Charsets.ISO_8859_1) },
            )
        }
    }

    @Test
    fun `verifier public constructors cannot receive production Tool or executable callback`() {
        val verifierTypes = listOf(
            WorkflowCandidateVerifier::class.java,
            WorkflowVerificationSubject::class.java,
            WorkflowReplayFixture::class.java,
            FakeWorkflowToolRegistry::class.java,
            FakeWorkflowToolRegistration::class.java,
            FakeWorkflowToolAdapter::class.java,
            FakeWorkflowToolCase::class.java,
        )

        verifierTypes.forEach { type ->
            type.declaredConstructors.flatMap { it.parameterTypes.asIterable() }.forEach { parameter ->
                assertFalse("${type.name} accepts production Tool", Tool::class.java.isAssignableFrom(parameter))
                assertFalse("${type.name} accepts Function callback", kotlin.Function::class.java.isAssignableFrom(parameter))
            }
        }
        assertEquals(
            setOf(
                FakeWorkflowToolOutcome.Success::class.java,
                FakeWorkflowToolOutcome.Failure::class.java,
                FakeWorkflowToolOutcome.Cancelled::class.java,
            ),
            FakeWorkflowToolOutcome::class.sealedSubclasses.map { it.java }.toSet(),
        )
    }

    private fun verifierClassBytes(): List<ByteArray> = listOf(
        WorkflowCandidateVerifier::class.java,
        WorkflowVerificationSubject::class.java,
        WorkflowReplayFixture::class.java,
        FakeWorkflowToolRegistry::class.java,
        FakeWorkflowToolRegistration::class.java,
        FakeWorkflowToolAdapter::class.java,
        WorkflowVerificationReport::class.java,
    ).map { type ->
        val resource = "/${type.name.replace('.', '/')}.class"
        requireNotNull(type.getResourceAsStream(resource)) { "Missing class resource $resource" }
            .use { it.readBytes() }
    }

    private fun locateVerificationSources(): Path {
        var cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            val direct = cursor.resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/verification",
            )
            if (Files.isDirectory(direct)) return direct
            val module = cursor.resolve("src/main/java/me/rerere/rikkahub/learning/verification")
            if (Files.isDirectory(module)) return module
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate Workflow verifier production sources")
    }

    companion object {
        private val FORBIDDEN_SOURCE_PATTERNS = listOf(
            "import android.",
            "import java.io.",
            "import java.net.",
            "import okhttp3.",
            "import me.rerere.ai.core.Tool",
            "LocalTools",
            "ToolRuntime",
            "ChatService",
            "SkillTestRunner",
            "JsSkillRunner",
            "WebView",
            ".execute(",
        )
        private val FORBIDDEN_CLASS_NAMES = listOf(
            "me/rerere/ai/core/Tool",
            "android/",
            "java/io/",
            "java/net/",
            "okhttp3/",
            "LocalTools",
            "ToolRuntime",
            "ChatService",
            "SkillTestRunner",
            "JsSkillRunner",
            "WebView",
        )
    }
}
