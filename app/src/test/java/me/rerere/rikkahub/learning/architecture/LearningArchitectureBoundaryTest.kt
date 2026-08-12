package me.rerere.rikkahub.learning.architecture

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class LearningArchitectureBoundaryTest {
    @Test
    fun learningCoreDoesNotImportCompetingAuthoritiesOrUnsafeVerifiers() {
        val root = locateLearningSourceRoot()
        val forbidden = listOf(
            "import me.rerere.rikkahub.memory.MemoryMutationCoordinator",
            "import me.rerere.rikkahub.memory.RoomMemoryProcessingStore",
            "import me.rerere.rikkahub.skills.SkillManager",
            "import me.rerere.rikkahub.skills.SkillTestRunner",
            "import me.rerere.rikkahub.skills.JsSkillRunner",
            "import me.rerere.rikkahub.data.ai.GenerationHandler",
            "import me.rerere.rikkahub.service.ChatService",
        )
        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText(Charsets.UTF_8)
                forbidden.asSequence()
                    .filter(text::contains)
                    .map { token -> "${file.relativeTo(root).path}: $token" }
            }
            .toList()
        assertTrue("Learning authority boundary violations: $violations", violations.isEmpty())
    }

    @Test
    fun compositionRootDoesNotPublishTheDerivedRoomDatabaseOrDaos() {
        val dataSource = locateProjectFile(
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText(Charsets.UTF_8)
        val forbidden = listOf(
            "single<LearningDatabase>",
            "single { LearningDatabase",
            "get<LearningDatabase>",
            "single<LearningInboxDao>",
            "single<LearningJobDao>",
            "single<LearningHandoffConsumer>",
            "single<LearningBootstrapCoordinator>",
            "single<LearningJobCoordinator>",
        )
        forbidden.forEach { token ->
            assertFalse("Derived Room handle escaped through DI: $token", token in dataSource)
        }
    }

    @Test
    fun productionRolloutSourceIsExplicitSettingsBackedAndDefaultClosed() {
        val dataSource = locateProjectFile(
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(
            "Production must bind the explicit settings-backed Learning flag source",
            "SettingsLearningFeatureFlagSource" in dataSource,
        )
        assertFalse(
            "Production must not infer Learning consent from memory or Dreaming settings",
            Regex("LearningFeatureFlagSource[\\s\\S]{0,300}(useGlobalMemory|dream|memoryEnabled)",
                RegexOption.IGNORE_CASE).containsMatchIn(dataSource),
        )
    }

    @Test
    fun p0ProviderRequestPathHasNoLearningContributor() {
        val requestPathFiles = listOf(
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt",
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationProviderContextPreparer.kt",
            "app/src/main/java/me/rerere/rikkahub/data/ai/ProviderCacheIdentityFactory.kt",
            "app/src/main/java/me/rerere/rikkahub/data/ai/ProviderTurnRunner.kt",
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
        ).map { relative -> locateProjectFile(relative, relative.removePrefix("app/")) }

        val violations = requestPathFiles.filter { file ->
            file.readText(Charsets.UTF_8).lineSequence().any { line ->
                line.trimStart().startsWith("import me.rerere.rikkahub.learning.")
            }
        }.map(File::getName)

        assertTrue(
            "P0 feature-off path must have no Learning request contributor: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun backgroundGenerationProductionBindingRequiresExactSettingsConsent() {
        val dataSource = locateProjectFile(
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText(Charsets.UTF_8)
        val host = locateProjectFile(
            "app/src/main/java/me/rerere/rikkahub/data/ai/background/SettingsBackedBackgroundGenerationHost.kt",
            "src/main/java/me/rerere/rikkahub/data/ai/background/SettingsBackedBackgroundGenerationHost.kt",
        ).readText(Charsets.UTF_8)
        assertTrue("BackgroundGenerationClient must have one production binding",
            "BackgroundGenerationClient(" in dataSource)
        assertTrue("Background authorization must use the model-scoped settings adapter",
            "SettingsLearningBackgroundGenerationUserPolicySource" in dataSource)
        assertTrue("Persisted Learning preferences must remain default-off",
            "backgroundWorkAuthorized: Boolean = false" in locateProjectFile(
                "app/src/main/java/me/rerere/rikkahub/learning/model/LearningPreferencesV1.kt",
                "src/main/java/me/rerere/rikkahub/learning/model/LearningPreferencesV1.kt",
            ).readText(Charsets.UTF_8))
        assertTrue("AICore must remain excluded", "ProviderSetting.AICore" in host)
        assertTrue("Bindings must require a versioned cancellation fence",
            "cancellationFenceAbi" in host)
    }

    private fun locateLearningSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/learning"),
            File("app/src/main/java/me/rerere/rikkahub/learning"),
        )
        return requireNotNull(candidates.firstOrNull(File::isDirectory)) {
            "Cannot locate the Learning source root from ${File(".").absolutePath}"
        }
    }

    private fun locateProjectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }
}
