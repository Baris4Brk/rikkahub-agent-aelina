package me.rerere.rikkahub.learning.eval

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionEvalRuntimeEnvironmentTest {
    @Test
    fun `pinned observed runtime and explicit build inputs reproduce reviewed digest`() {
        val observed = capture(properties = REVIEWED_PROPERTIES, environment = REVIEWED_ENVIRONMENT)

        assertEquals(FrozenProductionEvalBaselineV1.reviewedEnvironment, observed)
        assertEquals(
            FrozenProductionEvalBaselineV1.baseline.baseline.environmentDigestSha256,
            observed.digestSha256,
        )
        assertTrue(observed.hasExplicitBuildBinding)
        assertTrue(observed.frozenMatchRequired)
    }

    @Test
    fun `constant CI profile alone does not pass environment binding`() {
        val onlyProfile = capture(
            properties = REVIEWED_PROPERTIES,
            environment = mapOf(
                ProductionEvalRuntimeEnvironment.CI_PROFILE_ENV to
                    FrozenProductionEvalBaselineV1.reviewedEnvironment.ciProfile,
            ),
        )

        assertFalse(onlyProfile.hasExplicitBuildBinding)
        assertFalse(onlyProfile.frozenMatchRequired)
        assertNotEquals(
            FrozenProductionEvalBaselineV1.reviewedEnvironment.digestSha256,
            onlyProfile.digestSha256,
        )
    }

    @Test
    fun `runtime OS architecture vendor major and VM are all digest bound`() {
        val reviewed = capture(REVIEWED_PROPERTIES, REVIEWED_ENVIRONMENT)
        val propertyKeys = listOf(
            "os.name",
            "os.arch",
            "java.vendor",
            "java.specification.version",
            "java.vm.name",
        )

        propertyKeys.forEach { changedKey ->
            val changed = capture(
                REVIEWED_PROPERTIES + (changedKey to "deliberately-unmatched"),
                REVIEWED_ENVIRONMENT,
            )
            assertNotEquals("$changedKey was not digest-bound", reviewed.digestSha256, changed.digestSha256)
        }
    }

    @Test
    fun `every explicit build input is digest bound`() {
        val reviewed = capture(REVIEWED_PROPERTIES, REVIEWED_ENVIRONMENT)
        val buildKeys = listOf(
            ProductionEvalRuntimeEnvironment.CI_PROFILE_ENV,
            ProductionEvalRuntimeEnvironment.GRADLE_VERSION_ENV,
            ProductionEvalRuntimeEnvironment.AGP_VERSION_ENV,
            ProductionEvalRuntimeEnvironment.KOTLIN_VERSION_ENV,
            ProductionEvalRuntimeEnvironment.JVM_TARGET_ENV,
            ProductionEvalRuntimeEnvironment.COMPILE_SDK_ENV,
            ProductionEvalRuntimeEnvironment.GATE_TASK_ENV,
            ProductionEvalRuntimeEnvironment.REQUIRE_FROZEN_ENV,
        )

        buildKeys.forEach { changedKey ->
            val changed = capture(
                REVIEWED_PROPERTIES,
                REVIEWED_ENVIRONMENT + (changedKey to "false"),
            )
            assertNotEquals("$changedKey was not digest-bound", reviewed.digestSha256, changed.digestSha256)
        }
    }

    @Test
    fun `public observed digest cannot be replaced by frozen constant on local process`() {
        val observed = ProductionEvalRuntimeEnvironment.capture()

        assertEquals(observed.digestSha256, FrozenProductionComponentReplayV1.environmentDigestSha256)
        assertEquals(64, observed.digestSha256.length)
    }

    @Test
    fun `adapter identities are bound to reviewed normalized production sources`() {
        val projectRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

        FrozenProductionAdapterSourceBindingsV1.sources.forEach { binding ->
            val source = File(projectRoot, binding.path)
            assertTrue("Missing bound source ${binding.path}", source.isFile)
            val normalized = source.readText(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
            assertEquals("Explicit adapter re-baseline required for ${binding.path}",
                binding.normalizedUtf8Sha256, actual)
        }

        assertEquals(
            ProductionReplayComponent.entries.size,
            FrozenProductionComponentReplayV1.adapters.identities.map { it.component }.distinct().size,
        )
    }

    private fun capture(
        properties: Map<String, String>,
        environment: Map<String, String>,
    ): ProductionEvalRuntimeEnvironment = ProductionEvalRuntimeEnvironment.capture(
        property = properties::get,
        environment = environment::get,
    )

    private companion object {
        val REVIEWED_PROPERTIES = mapOf(
            "os.name" to "Linux",
            "os.arch" to "amd64",
            "java.vendor" to "Eclipse Adoptium",
            "java.specification.version" to "17",
            "java.vm.name" to "OpenJDK 64-Bit Server VM",
        )

        val REVIEWED_ENVIRONMENT = mapOf(
            ProductionEvalRuntimeEnvironment.CI_PROFILE_ENV to
                "gha-ubuntu-24.04-x64-temurin17-v1",
            ProductionEvalRuntimeEnvironment.GRADLE_VERSION_ENV to "9.4.1",
            ProductionEvalRuntimeEnvironment.AGP_VERSION_ENV to "9.2.1",
            ProductionEvalRuntimeEnvironment.KOTLIN_VERSION_ENV to "2.4.0",
            ProductionEvalRuntimeEnvironment.JVM_TARGET_ENV to "17",
            ProductionEvalRuntimeEnvironment.COMPILE_SDK_ENV to "37",
            ProductionEvalRuntimeEnvironment.GATE_TASK_ENV to
                "app-p5-production-evaluation-gate",
            ProductionEvalRuntimeEnvironment.REQUIRE_FROZEN_ENV to "true",
        )
    }
}
