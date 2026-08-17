package me.rerere.rikkahub.ui.pages.setting

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReflectionDisclosureUiContractTest {
    @Test
    fun `enable is target exact and only reachable through complete disclosure dialog`() {
        val root = Path.of(System.getProperty("user.dir")).let { cwd ->
            if (Files.exists(cwd.resolve("src/main"))) cwd else cwd.resolve("app")
        }
        val page = Files.readString(
            root.resolve(
                "src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAgentRuntimePage.kt",
            ),
        )
        val vm = Files.readString(
            root.resolve(
                "src/main/java/me/rerere/rikkahub/ui/pages/setting/AgentRuntimeSettingsViewModel.kt",
            ),
        )

        listOf(
            "agent_learning_remote_reflection_disclosure_provider",
            "agent_learning_remote_reflection_disclosure_model",
            "agent_learning_remote_reflection_disclosure_fields",
            "agent_learning_remote_reflection_disclosure_limits",
            "agent_learning_remote_reflection_disclosure_scope",
        ).forEach { required -> assertTrue(required in page) }
        assertTrue("pendingRemoteLearningStage = stage to candidate" in page)
        assertTrue("vm.setLearningStage(stage, target)" in page)
        assertTrue("exactRemoteProviderIdentityDigest = liveCandidate" in vm)
        assertTrue("authorizeRemoteReflection = liveCandidate?.isRemote == true" in vm)
        assertFalse("onCheckedChange = vm::setRemoteReflectionAllowed" in page)
    }
}
