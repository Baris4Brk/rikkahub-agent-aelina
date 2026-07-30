package me.rerere.rikkahub.assistant

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.security.SecondUserAuthorityUserAuthorization
import kotlin.uuid.Uuid

/** One global, fail-closed authority record for the local second user. */
@Serializable
data class SecondUserAuthorityConfig(
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
    val authorityEpoch: Long = 0L,
    val state: SecondUserAuthorityState = SecondUserAuthorityState.UNCONFIGURED,
    val updatedAtMs: Long = 0L,
    /** Opaque audit correlation only; never a title, prompt, or secret. */
    val auditId: String? = null,
) {
    fun normalized(): SecondUserAuthorityConfig = when (state) {
        SecondUserAuthorityState.UNCONFIGURED -> copy(
            assistantId = null,
            conversationId = null,
            auditId = null,
        )
        else -> if (state == SecondUserAuthorityState.PENDING_CONFIRMATION &&
            assistantId == null && conversationId == null
        ) {
            // A multiple-legacy-candidate upgrade has no safe automatic target. Keep a
            // durable repair marker so startup routes to the local recovery screen.
            this
        } else if (assistantId == null || conversationId == null || authorityEpoch <= 0L) {
            copy(
                assistantId = null,
                conversationId = null,
                state = SecondUserAuthorityState.UNCONFIGURED,
                authorityEpoch = authorityEpoch.coerceAtLeast(0L),
                auditId = null,
            )
        } else {
            this
        }
    }
}

@Serializable
enum class SecondUserAuthorityState {
    UNCONFIGURED,
    PENDING_CONFIRMATION,
    ACTIVE,
    REVOKING,
}

data class SecondUserAdmissionSnapshot(
    val assistantId: Uuid,
    val conversationId: Uuid,
    val authorityEpoch: Long,
    val subjectId: String,
    val origin: ToolCallOrigin,
) {
    companion object {
        fun create(
            assistantId: Uuid,
            conversationId: Uuid,
            authorityEpoch: Long,
            origin: ToolCallOrigin,
        ): SecondUserAdmissionSnapshot = SecondUserAdmissionSnapshot(
            assistantId = assistantId,
            conversationId = conversationId,
            authorityEpoch = authorityEpoch,
            subjectId = subjectId(assistantId, conversationId, authorityEpoch),
            origin = origin,
        )

        fun subjectId(assistantId: Uuid, conversationId: Uuid, authorityEpoch: Long): String =
            "local-second-user:v1:$assistantId:$conversationId:$authorityEpoch"
    }
}

sealed interface SecondUserAuthorityResolution {
    data class Active(val snapshot: SecondUserAdmissionSnapshot) : SecondUserAuthorityResolution
    data class Pending(val config: SecondUserAuthorityConfig) : SecondUserAuthorityResolution
    data class Invalid(val config: SecondUserAuthorityConfig, val reason: String) : SecondUserAuthorityResolution
    data object Unconfigured : SecondUserAuthorityResolution
}

/**
 * Synchronous fail-closed cache used by low-level gates. The service updates it from the
 * authoritative DataStore stream; a cold process therefore never accidentally grants elevation.
 */
object SecondUserAuthorityRegistry {
    private val snapshot = AtomicReference<SecondUserAdmissionSnapshot?>(null)

    fun current(): SecondUserAdmissionSnapshot? = snapshot.get()

    internal fun install(value: SecondUserAdmissionSnapshot?) {
        snapshot.set(value)
    }

    fun matches(
        subjectId: String,
        conversationId: Uuid?,
        origin: ToolCallOrigin,
    ): Boolean = current()?.let { active ->
        active.subjectId == subjectId &&
            active.conversationId == conversationId &&
            origin in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER
    } == true
}

fun interface SecondUserAuthorityConversationReader {
    suspend fun findAssistantId(conversationId: Uuid): Uuid?
}

/**
 * The only authority source. Legacy assistant fields are read exactly once to build a pending
 * migration record; no active decision trusts them afterwards.
 */
class SecondUserAuthorityService(
    private val settingsStore: SettingsStore,
    private val conversations: SecondUserAuthorityConversationReader,
    appScope: AppScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        appScope.launch {
            settingsStore.settingsFlow
                .filter { !it.init }
                .collect { settings ->
                    SecondUserAuthorityRegistry.install(validatedActiveSnapshotOrNull(settings))
                }
        }
    }

    suspend fun initializeLegacyMigration() {
        val settings = settingsStore.settingsFlow.first { !it.init }
        if (settings.secondUserAuthority.state != SecondUserAuthorityState.UNCONFIGURED) {
            SecondUserAuthorityRegistry.install(validatedActiveSnapshotOrNull(settings))
            return
        }
        val candidates = settings.assistants.filter { assistant ->
            assistant.privilegedConversationId != null && assistant.secondUserPolicyConfirmed
        }
        if (candidates.size != 1) {
            if (candidates.size > 1) {
                settingsStore.update { current ->
                    if (current.secondUserAuthority.state != SecondUserAuthorityState.UNCONFIGURED) current
                    else current.copy(
                        secondUserAuthority = SecondUserAuthorityConfig(
                            state = SecondUserAuthorityState.PENDING_CONFIRMATION,
                            updatedAtMs = nowMs(),
                            auditId = "legacy-multiple-candidates",
                        ),
                    )
                }
            }
            SecondUserAuthorityRegistry.install(null)
            return
        }
        val candidate = candidates.single()
        val conversationId = candidate.privilegedConversationId ?: return
        if (conversations.findAssistantId(conversationId) != candidate.id) {
            SecondUserAuthorityRegistry.install(null)
            return
        }
        settingsStore.update { current ->
            if (current.secondUserAuthority.state != SecondUserAuthorityState.UNCONFIGURED) current
            else current.copy(
                secondUserAuthority = SecondUserAuthorityConfig(
                    assistantId = candidate.id,
                    conversationId = conversationId,
                    authorityEpoch = 1L,
                    state = SecondUserAuthorityState.PENDING_CONFIRMATION,
                    updatedAtMs = nowMs(),
                    auditId = "legacy-migration",
                ),
            )
        }
        refreshRegistry()
    }

    suspend fun resolve(): SecondUserAuthorityResolution {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val config = settings.secondUserAuthority.normalized()
        if (config.state == SecondUserAuthorityState.UNCONFIGURED) {
            SecondUserAuthorityRegistry.install(null)
            return SecondUserAuthorityResolution.Unconfigured
        }
        val assistantId = config.assistantId ?: return invalid(config, "assistant_missing")
        val conversationId = config.conversationId
            ?: return invalid(config, "conversation_missing")
        if (settings.assistants.none { it.id == assistantId }) {
            return invalid(config, "assistant_not_found")
        }
        if (conversations.findAssistantId(conversationId) != assistantId) {
            return invalid(config, "conversation_owner_mismatch")
        }
        val snapshot = SecondUserAdmissionSnapshot.create(
            assistantId = assistantId,
            conversationId = conversationId,
            authorityEpoch = config.authorityEpoch,
            origin = ToolCallOrigin.LocalChat,
        )
        return when (config.state) {
            SecondUserAuthorityState.ACTIVE -> {
                SecondUserAuthorityRegistry.install(snapshot)
                SecondUserAuthorityResolution.Active(snapshot)
            }
            SecondUserAuthorityState.PENDING_CONFIRMATION,
            SecondUserAuthorityState.REVOKING,
            -> {
                SecondUserAuthorityRegistry.install(null)
                SecondUserAuthorityResolution.Pending(config)
            }
            SecondUserAuthorityState.UNCONFIGURED -> {
                SecondUserAuthorityRegistry.install(null)
                SecondUserAuthorityResolution.Unconfigured
            }
        }
    }

    /** Raw normalized state for revocation recovery; never use it for a permission decision. */
    suspend fun currentConfig(): SecondUserAuthorityConfig = settingsStore.settingsFlow
        .first { !it.init }
        .secondUserAuthority
        .normalized()

    suspend fun admit(
        assistantId: Uuid,
        conversationId: Uuid,
        origin: ToolCallOrigin,
        deviceUnlocked: Boolean,
    ): SecondUserAdmissionSnapshot? {
        if (!deviceUnlocked || origin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return null
        return when (val resolved = resolve()) {
            is SecondUserAuthorityResolution.Active -> resolved.snapshot.takeIf {
                it.assistantId == assistantId && it.conversationId == conversationId
            }?.copy(origin = origin)
            else -> null
        }
    }

    suspend fun isDeletionProtected(conversationId: Uuid): Boolean {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val config = settings.secondUserAuthority.normalized()
        return when {
            config.conversationId != null -> config.conversationId == conversationId
            config.state == SecondUserAuthorityState.PENDING_CONFIRMATION &&
                config.auditId == "legacy-multiple-candidates" ->
                settings.assistants.any { it.privilegedConversationId == conversationId }
            else -> false
        }
    }

    suspend fun isAssistantDeletionProtected(assistantId: Uuid): Boolean {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val config = settings.secondUserAuthority.normalized()
        return when {
            config.assistantId != null -> config.assistantId == assistantId
            config.state == SecondUserAuthorityState.PENDING_CONFIRMATION &&
                config.auditId == "legacy-multiple-candidates" ->
                settings.assistants.any {
                    it.id == assistantId && it.privilegedConversationId != null
                }
            else -> false
        }
    }

    suspend fun stageReassignment(
        authorization: SecondUserAuthorityUserAuthorization,
        assistantId: Uuid,
        conversationId: Uuid,
        auditId: String,
    ) {
        requireFreshUserAuthorization(authorization)
        require(conversations.findAssistantId(conversationId) == assistantId) {
            "second_user_conversation_owner_mismatch"
        }
        settingsStore.update { current ->
            val old = current.secondUserAuthority.normalized()
            require(old.state !in setOf(
                SecondUserAuthorityState.ACTIVE,
                SecondUserAuthorityState.REVOKING,
            )) { "second_user_revocation_required" }
            current.copy(
                assistants = current.assistants.map { assistant ->
                    when {
                        assistant.id == assistantId -> assistant.copy(
                            privilegedConversationId = conversationId,
                            secondUserPolicyConfirmed = false,
                            allowConversationHistoryRead = false,
                        )
                        assistant.privilegedConversationId != null -> assistant.copy(
                            privilegedConversationId = null,
                            secondUserPolicyConfirmed = false,
                            allowConversationHistoryRead = false,
                        )
                        else -> assistant
                    }
                },
                secondUserAuthority = old.copy(
                    assistantId = assistantId,
                    conversationId = conversationId,
                    authorityEpoch = old.authorityEpoch + 1L,
                    state = SecondUserAuthorityState.PENDING_CONFIRMATION,
                    updatedAtMs = nowMs(),
                    auditId = auditId.take(96),
                ),
            )
        }
        // Pending configuration never grants runtime elevation, but clear any stale in-memory
        // snapshot immediately rather than waiting for DataStore collection.
        SecondUserAuthorityRegistry.install(null)
    }

    suspend fun confirmActive(authorization: SecondUserAuthorityUserAuthorization) {
        requireFreshUserAuthorization(authorization)
        val candidate = currentConfig()
        val candidateAssistantId = requireNotNull(candidate.assistantId) {
            "second_user_confirmation_target_missing"
        }
        val candidateConversationId = requireNotNull(candidate.conversationId) {
            "second_user_confirmation_target_missing"
        }
        require(conversations.findAssistantId(candidateConversationId) == candidateAssistantId) {
            "second_user_confirmation_owner_mismatch"
        }
        settingsStore.update { current ->
            val old = current.secondUserAuthority.normalized()
            require(old.state == SecondUserAuthorityState.PENDING_CONFIRMATION) {
                "second_user_confirmation_not_pending"
            }
            require(old.assistantId == candidateAssistantId && old.conversationId == candidateConversationId) {
                "second_user_confirmation_target_changed"
            }
            old.copy(state = SecondUserAuthorityState.ACTIVE, updatedAtMs = nowMs())
                .let { confirmed ->
                    current.copy(
                        assistants = current.assistants.map { assistant ->
                            if (assistant.id == confirmed.assistantId) {
                                assistant.copy(
                                    privilegedConversationId = confirmed.conversationId,
                                    secondUserPolicyConfirmed = true,
                                )
                            } else {
                                assistant
                            }
                        },
                        secondUserAuthority = confirmed,
                    )
                }
        }
        refreshRegistry()
    }

    suspend fun beginRevocation(authorization: SecondUserAuthorityUserAuthorization) {
        requireFreshUserAuthorization(authorization)
        settingsStore.update { current ->
            val old = current.secondUserAuthority.normalized()
            if (old.state == SecondUserAuthorityState.UNCONFIGURED) current
            else current.copy(secondUserAuthority = old.copy(
                state = SecondUserAuthorityState.REVOKING,
                updatedAtMs = nowMs(),
            ))
        }
        SecondUserAuthorityRegistry.install(null)
    }

    internal suspend fun completeUnassign() {
        settingsStore.update { current ->
            val old = current.secondUserAuthority.normalized()
            current.copy(
                assistants = current.assistants.map { assistant ->
                    if (assistant.privilegedConversationId != null || assistant.secondUserPolicyConfirmed) {
                        assistant.copy(
                            privilegedConversationId = null,
                            secondUserPolicyConfirmed = false,
                            allowConversationHistoryRead = false,
                        )
                    } else {
                        assistant
                    }
                },
                secondUserAuthority = SecondUserAuthorityConfig(
                    authorityEpoch = old.authorityEpoch + 1L,
                    state = SecondUserAuthorityState.UNCONFIGURED,
                    updatedAtMs = nowMs(),
                ),
            )
        }
        SecondUserAuthorityRegistry.install(null)
    }

    private fun activeSnapshotOrNull(config: SecondUserAuthorityConfig): SecondUserAdmissionSnapshot? =
        config.normalized().takeIf { it.state == SecondUserAuthorityState.ACTIVE }?.let { active ->
            val assistantId = active.assistantId ?: return@let null
            val conversationId = active.conversationId ?: return@let null
            SecondUserAdmissionSnapshot.create(
                assistantId = assistantId,
                conversationId = conversationId,
                authorityEpoch = active.authorityEpoch,
                origin = ToolCallOrigin.LocalChat,
            )
        }

    private suspend fun refreshRegistry() {
        val settings = settingsStore.settingsFlow.first { !it.init }
        SecondUserAuthorityRegistry.install(validatedActiveSnapshotOrNull(settings))
    }

    private suspend fun validatedActiveSnapshotOrNull(
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): SecondUserAdmissionSnapshot? {
        val snapshot = activeSnapshotOrNull(settings.secondUserAuthority) ?: return null
        return snapshot.takeIf {
            settings.assistants.any { assistant -> assistant.id == snapshot.assistantId } &&
                conversations.findAssistantId(snapshot.conversationId) == snapshot.assistantId
        }
    }

    private fun invalid(
        config: SecondUserAuthorityConfig,
        reason: String,
    ): SecondUserAuthorityResolution.Invalid {
        SecondUserAuthorityRegistry.install(null)
        return SecondUserAuthorityResolution.Invalid(config, reason)
    }

    private fun requireFreshUserAuthorization(authorization: SecondUserAuthorityUserAuthorization) {
        check(nowMs() - authorization.issuedAtMs in 0..USER_AUTHORIZATION_WINDOW_MS) {
            "second_user_biometric_authorization_required"
        }
    }

    private companion object {
        const val USER_AUTHORIZATION_WINDOW_MS = 2 * 60 * 1000L
    }
}
