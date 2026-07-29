package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.capability.AccessGrant
import me.rerere.rikkahub.data.capability.CapabilityKey
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

private val REQUIRED_SHARED_STORAGE_CAPABILITIES = setOf(
    CapabilityKey.of("phone.shared.read"),
    CapabilityKey.of("phone.shared.write"),
)

/**
 * Grants the Proot /sdcard bind only to the exact selected second-user conversation.
 *
 * Merely enabling local tools is insufficient: both shared read and write grants must still be
 * active for this owner and invocation origin. Automatic pet handoff and remote origins therefore
 * remain unable to acquire the mount.
 */
internal fun canMountSecondUserSharedStorage(
    privilege: PrivilegedSessionContext,
    grants: Collection<AccessGrant>,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (!privilege.expandLocalTools) return false
    if (privilege.origin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return false
    if (privilege.privilegedConversationId != privilege.conversationId) return false
    val subjectId = "${privilege.assistantId}:${privilege.conversationId}"
    return REQUIRED_SHARED_STORAGE_CAPABILITIES.all { required ->
        grants.any { grant ->
            !grant.revoked &&
                (grant.expiresAtMs == null || nowMs < grant.expiresAtMs) &&
                grant.subjectType == SubjectType.LOCAL_SECOND_USER &&
                grant.subjectId == subjectId &&
                grant.capability == required &&
                grant.resourceKind == "file_root" &&
                grant.resourceIdentifier == ToolCapabilityResolver.SHARED_STORAGE_ROOT &&
                privilege.origin in grant.allowedOrigins
        }
    }
}
