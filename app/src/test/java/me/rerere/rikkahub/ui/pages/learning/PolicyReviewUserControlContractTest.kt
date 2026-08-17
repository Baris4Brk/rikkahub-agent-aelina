package me.rerere.rikkahub.ui.pages.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyReviewUserControlContractTest {
    @Test
    fun `grant revoke technical suspend and authority scope erase are distinct controls`() {
        val root = Path.of(System.getProperty("user.dir")).let { cwd ->
            if (Files.exists(cwd.resolve("src/main"))) cwd else cwd.resolve("app")
        }
        val page = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/ui/pages/learning/LearningCenterPage.kt"),
        )
        val vm = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/ui/pages/learning/LearningCenterVM.kt"),
        )
        val repository = Files.readString(
            root.resolve(
                "src/main/java/me/rerere/rikkahub/learning/review/" +
                    "ProductionLearningPolicyReviewRepository.kt",
            ),
        )

        assertTrue("learning_policy_revoke_grant" in page)
        assertTrue("learning_policy_suspend_scope" in page)
        assertTrue("ReviewConfirmation.Suspend" in page)
        assertTrue("repository.suspendPolicy(command)" in vm)
        assertTrue("PolicyReviewLifecycleAction.SUSPEND" in repository)
        assertTrue("SecondUserAuthorityRegistry.current()" in vm)
        assertTrue("LearningScope.AuthoritySubject" in vm)
        assertTrue("authorityScopeEraseAvailable" in page)
        assertFalse("authoritySubjectLearningCaptureEnabled" in vm)
        assertFalse("authority.subjectId" in page)
    }
}
