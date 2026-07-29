package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.AccessGrant
import me.rerere.rikkahub.data.capability.CapabilityKey
import me.rerere.rikkahub.data.capability.GrantScope
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SecondUserSharedStorageAccessTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()

    @Test
    fun `exact confirmed owner with read and write grants receives mount`() {
        assertTrue(canMountSecondUserSharedStorage(privilege(), grants()))
    }

    @Test
    fun `missing write grant wrong owner or automatic pet origin fails closed`() {
        assertFalse(canMountSecondUserSharedStorage(privilege(), grants().take(1)))
        assertFalse(
            canMountSecondUserSharedStorage(
                privilege(),
                grants(subjectId = "$assistantId:${Uuid.random()}"),
            ),
        )
        assertFalse(
            canMountSecondUserSharedStorage(
                privilege(origin = ToolCallOrigin.PetHandoffAuto, expanded = false),
                grants(),
            ),
        )
        assertFalse(
            canMountSecondUserSharedStorage(
                privilege = privilege(),
                grants = grants(expiresAtMs = 99L),
                nowMs = 100L,
            ),
        )
    }

    @Test
    fun `confirmed pet handoff needs grants that include its exact origin`() {
        val pet = privilege(origin = ToolCallOrigin.PetHandoffConfirmed)
        assertTrue(canMountSecondUserSharedStorage(pet, grants()))
        assertFalse(
            canMountSecondUserSharedStorage(
                pet,
                grants(origins = setOf(ToolCallOrigin.LocalChat, ToolCallOrigin.SystemAssistant)),
            ),
        )
    }

    private fun privilege(
        origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
        expanded: Boolean = true,
    ) = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = origin,
        privilegedConversationId = conversationId,
        identityName = "second user",
        isPrivileged = true,
        expandLocalTools = expanded,
        autoApproveTools = expanded,
        unrestrictedOverride = false,
    )

    private fun grants(
        subjectId: String = "$assistantId:$conversationId",
        origins: Set<ToolCallOrigin> = InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER,
        expiresAtMs: Long? = null,
    ) = listOf("phone.shared.read", "phone.shared.write").map { capability ->
        AccessGrant(
            id = capability,
            subjectId = subjectId,
            subjectType = SubjectType.LOCAL_SECOND_USER,
            capability = CapabilityKey.of(capability),
            resourceKind = "file_root",
            resourceIdentifier = ToolCapabilityResolver.SHARED_STORAGE_ROOT,
            allowedOrigins = origins,
            scope = GrantScope.CONVERSATION,
            expiresAtMs = expiresAtMs,
        )
    }
}
