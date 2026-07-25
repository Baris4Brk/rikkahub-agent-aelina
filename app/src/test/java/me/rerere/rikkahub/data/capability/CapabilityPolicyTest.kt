package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPolicyTest {
    private val capability = CapabilityKey.of("files.write")

    @Test
    fun `confirmed local second user is allowed only while unlocked in selected conversation`() {
        val engine = DefaultCapabilityPolicyEngine()
        val allowed = engine.evaluate(
            request(
                subject = CapabilitySubject("assistant-1", SubjectType.LOCAL_SECOND_USER),
                origin = ToolCallOrigin.LocalChat,
                unlocked = true,
                selected = true,
            ),
        )

        assertEquals(PolicyDecision.Allowed("local_second_user_profile"), allowed)
    }

    @Test
    fun `remote origin cannot inherit second user profile`() {
        val decision = DefaultCapabilityPolicyEngine().evaluate(
            request(
                subject = CapabilitySubject("assistant-1", SubjectType.LOCAL_SECOND_USER),
                origin = ToolCallOrigin.Telegram,
                unlocked = true,
                selected = true,
            ),
        )

        assertEquals(
            PolicyDecision.Denied(
                "second_user_local_unlocked_required",
                "The local second-user profile is available only from an unlocked local surface.",
            ),
            decision,
        )
    }

    @Test
    fun `second user Linux capability requires exact conversation grant`() {
        val subject = CapabilitySubject("assistant-1:conversation-1", SubjectType.LOCAL_SECOND_USER)
        val linux = CapabilityKey.of("linux.execute")
        val request = request(subject, ToolCallOrigin.LocalChat, unlocked = true, selected = true)
            .copy(capabilities = setOf(linux), resource = ResourceScope.Workspace("workspace-1"))
        assertTrue(DefaultCapabilityPolicyEngine().evaluate(request) is PolicyDecision.Denied)

        val granted = DefaultCapabilityPolicyEngine(grants = { listOf(AccessGrant(
            id = "linux-grant",
            subjectId = subject.id,
            subjectType = subject.type,
            capability = linux,
            resourceKind = "workspace",
            resourceIdentifier = "*",
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            scope = GrantScope.CONVERSATION,
        )) }).evaluate(request)
        assertEquals(PolicyDecision.Allowed("second_user_scoped_grant"), granted)
    }

    @Test
    fun `remote subject needs exact scoped grant`() {
        val subject = CapabilitySubject("telegram-chat-42", SubjectType.TELEGRAM)
        val engine = DefaultCapabilityPolicyEngine(
            grants = {
                listOf(
                    AccessGrant(
                        id = "grant-1",
                        subjectId = subject.id,
                        subjectType = subject.type,
                        capability = capability,
                        resourceKind = "file_root",
                        resourceIdentifier = "workspace",
                        allowedOrigins = setOf(ToolCallOrigin.Telegram),
                        scope = GrantScope.PERSISTENT,
                    ),
                )
            },
        )

        val decision = engine.evaluate(
            request(
                subject = subject,
                origin = ToolCallOrigin.Telegram,
                unlocked = false,
                selected = false,
                resource = ResourceScope.FileRoot("workspace"),
            ),
        )

        assertEquals(PolicyDecision.Allowed("scoped_grant"), decision)
    }

    @Test
    fun `ordinary local assistant abstains for existing gate and approval policy`() {
        val decision = DefaultCapabilityPolicyEngine().evaluate(
            request(
                subject = CapabilitySubject("assistant-1", SubjectType.LOCAL_ASSISTANT),
                origin = ToolCallOrigin.LocalChat,
                unlocked = true,
                selected = false,
            ),
        )

        assertEquals(PolicyDecision.Abstain, decision)
    }

    @Test
    fun `workflow cannot expand beyond persisted capability snapshot`() {
        val engine = DefaultCapabilityPolicyEngine()
        val allowed = engine.evaluate(
            request(
                subject = CapabilitySubject("workflow-1", SubjectType.WORKFLOW),
                origin = ToolCallOrigin.TrustedWorkflow,
                unlocked = true,
                selected = false,
            ).copy(frozenCapabilities = setOf(capability)),
        )
        val denied = engine.evaluate(
            request(
                subject = CapabilitySubject("workflow-1", SubjectType.WORKFLOW),
                origin = ToolCallOrigin.TrustedWorkflow,
                unlocked = true,
                selected = false,
            ),
        )

        assertEquals(PolicyDecision.Allowed("workflow_capability_snapshot"), allowed)
        assertTrue(denied is PolicyDecision.Denied)
    }

    private fun request(
        subject: CapabilitySubject,
        origin: ToolCallOrigin,
        unlocked: Boolean,
        selected: Boolean,
        resource: ResourceScope = ResourceScope.FileRoot("workspace"),
    ) = CapabilityRequest(
        subject = subject,
        origin = origin,
        capabilities = setOf(capability),
        resource = resource,
        deviceUnlocked = unlocked,
        selectedPrivilegedConversation = selected,
    )
}
