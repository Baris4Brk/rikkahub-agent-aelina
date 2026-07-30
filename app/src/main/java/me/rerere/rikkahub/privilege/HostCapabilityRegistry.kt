package me.rerere.rikkahub.privilege

import java.util.ArrayDeque
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy

/**
 * Typed, intentionally finite host capabilities. New host controls must be added here instead
 * of exposing a raw database, private-file, shell, reflection, or settings mutation primitive.
 */
enum class HostCapability {
    RIKKAHUB_SETTINGS,
    DIAGNOSTICS,
    NAVIGATION,
    PROVIDER,
    TTS_STT,
    MCP,
    SKILL,
    WORKFLOW,
    PET,
    AUDIO,
    CONVERSATION,
    SECRET_VAULT,
}

data class HostCapabilityAuditEntry(
    val capability: HostCapability,
    val operation: String,
    val succeeded: Boolean,
    val createdAtMs: Long,
)

/** Bounded, redacted audit trail: request values and model text never enter this store. */
class HostCapabilityAuditTrail {
    private val entries = ArrayDeque<HostCapabilityAuditEntry>()

    @Synchronized
    fun record(entry: HostCapabilityAuditEntry) {
        while (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<HostCapabilityAuditEntry> = entries.toList()

    private companion object {
        const val MAX_ENTRIES = 64
    }
}

/**
 * Enforces the live second-user epoch immediately before a model-facing host command delegates
 * to the existing typed repository backend. It preserves that backend's input validation and
 * hard-deny floor while making stale session snapshots fail closed after reassignment.
 */
class HostCapabilityRegistry(
    private val backend: PrivilegedManagementBackend,
    private val auditTrail: HostCapabilityAuditTrail = HostCapabilityAuditTrail(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PrivilegedManagementBackend {
    override suspend fun execute(
        request: PrivilegedManagementRequest,
        context: PrivilegedSessionContext,
    ): PrivilegedManagementResult {
        val capability = request.hostCapability()
        val subjectId = context.authoritySubjectId
        if (!context.isPrivileged || subjectId == null ||
            !SecondUserAuthorityRegistry.matches(subjectId, context.conversationId, context.origin) ||
            context.origin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER
        ) {
            return audited(
                capability,
                request,
                PrivilegedManagementResult.failure(
                    "SECOND_USER_AUTHORITY_STALE",
                    "The current second-user authority changed. Open the protected conversation again.",
                ),
            )
        }
        // PrivilegedManagementRequest is deliberately a sealed typed surface. There is no raw
        // database/private-file/security-settings/self-destruction/authority-management branch.
        return audited(capability, request, backend.execute(request, context))
    }

    fun auditSnapshot(): List<HostCapabilityAuditEntry> = auditTrail.snapshot()

    private fun audited(
        capability: HostCapability,
        request: PrivilegedManagementRequest,
        result: PrivilegedManagementResult,
    ): PrivilegedManagementResult {
        auditTrail.record(
            HostCapabilityAuditEntry(
                capability = capability,
                operation = request::class.simpleName.orEmpty().take(80),
                succeeded = result.ok,
                createdAtMs = nowMs(),
            ),
        )
        return result
    }
}

private fun PrivilegedManagementRequest.hostCapability(): HostCapability = when (this) {
    is PrivilegedManagementRequest.StateGet -> HostCapability.DIAGNOSTICS
    is PrivilegedManagementRequest.ConversationCreate,
    is PrivilegedManagementRequest.ConversationUpdate,
    is PrivilegedManagementRequest.ConversationDelete,
    -> HostCapability.CONVERSATION
    is PrivilegedManagementRequest.AssistantUpdate,
    is PrivilegedManagementRequest.AssistantToggleTool,
    is PrivilegedManagementRequest.LorebookCreate,
    is PrivilegedManagementRequest.LorebookUpdate,
    is PrivilegedManagementRequest.LorebookDelete,
    is PrivilegedManagementRequest.ModeInjectionUpdate,
    is PrivilegedManagementRequest.AppSettingsUpdate,
    -> HostCapability.RIKKAHUB_SETTINGS
    is PrivilegedManagementRequest.AssistantUpdateSkills -> HostCapability.SKILL
    is PrivilegedManagementRequest.AssistantUpdateMcpServers -> HostCapability.MCP
    is PrivilegedManagementRequest.SecretVaultList,
    is PrivilegedManagementRequest.SecretVaultCreateSlot,
    is PrivilegedManagementRequest.SecretVaultSetBinding,
    is PrivilegedManagementRequest.SecretVaultTestBinding,
    -> HostCapability.SECRET_VAULT
}
