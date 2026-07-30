package me.rerere.rikkahub.privilege

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HostCapabilityRegistryTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()
    private val active = SecondUserAdmissionSnapshot.create(
        assistantId = assistantId,
        conversationId = conversationId,
        authorityEpoch = 8L,
        origin = ToolCallOrigin.LocalChat,
    )

    @After
    fun clearRegistry() {
        SecondUserAuthorityRegistry.install(null)
    }

    @Test
    fun `stale authority cannot reach host backend`() = runBlocking {
        SecondUserAuthorityRegistry.install(active)
        var calls = 0
        val registry = HostCapabilityRegistry(
            backend = PrivilegedManagementBackend { _, _ ->
                calls++
                PrivilegedManagementResult.success("UNEXPECTED", "unexpected")
            },
        )

        val result = registry.execute(
            PrivilegedManagementRequest.StateGet(section = null),
            context(
                subjectId = SecondUserAdmissionSnapshot.subjectId(
                    assistantId,
                    conversationId,
                    7L,
                ),
            ),
        )

        assertFalse(result.ok)
        assertEquals(0, calls)
        assertEquals("SECOND_USER_AUTHORITY_STALE", result.code)
        assertFalse(registry.auditSnapshot().single().succeeded)
    }

    @Test
    fun `current trusted authority reaches typed host backend`() = runBlocking {
        SecondUserAuthorityRegistry.install(active)
        var calls = 0
        val registry = HostCapabilityRegistry(
            backend = PrivilegedManagementBackend { _, _ ->
                calls++
                PrivilegedManagementResult.success("OK", "typed")
            },
        )

        val result = registry.execute(
            PrivilegedManagementRequest.StateGet(section = null),
            context(active.subjectId),
        )

        assertTrue(result.ok)
        assertEquals(1, calls)
        assertEquals(HostCapability.DIAGNOSTICS, registry.auditSnapshot().single().capability)
    }

    private fun context(subjectId: String) = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = conversationId,
        identityName = "Second user",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = false,
        authoritySubjectId = subjectId,
        authorityEpoch = active.authorityEpoch,
    )
}
