package me.rerere.rikkahub.security

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

@Serializable
enum class SecondUserSecretAccessMode {
    USE_ONLY,
    PLAINTEXT_REMOTE_SESSION,
}

/** Strong-biometric proof dedicated to opening one process-local plaintext session. */
class SecretPlaintextSessionAuthorization internal constructor(internal val issuedAtMs: Long)

data class SecretPlaintextSessionBinding(
    val authoritySubjectId: String,
    val authorityEpoch: Long,
    val assistantId: String,
    val conversationId: String,
    val modelId: String,
    val providerId: String,
)

sealed interface SecretPlaintextSessionState {
    data object Closed : SecretPlaintextSessionState
    data class Open(
        val binding: SecretPlaintextSessionBinding,
        val openedAtMs: Long,
        val expiresAtMs: Long,
    ) : SecretPlaintextSessionState
}

enum class SecretPlaintextSessionCloseReason {
    MANUAL,
    TTL_EXPIRED,
    SCREEN_LOCKED,
    PROCESS_STATE_CHANGED,
    AUTHORITY_CHANGED,
    CONVERSATION_CHANGED,
    MODEL_OR_PROVIDER_CHANGED,
    MODE_DISABLED,
    EMERGENCY_STOP,
}

/**
 * A process-only 30 minute capability. Nothing here is serialized, and every access rechecks
 * the exact authority/model/provider binding plus lock and Emergency Stop state.
 */
class SecretPlaintextSessionManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val vault: SecondUserSecretVault,
    private val safetySettings: AgentSafetySettings,
    private val redactor: RuntimeSecretRedactor,
    private val appScope: AppScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val current = AtomicReference<SecretPlaintextSessionState.Open?>(null)
    private val _state = MutableStateFlow<SecretPlaintextSessionState>(SecretPlaintextSessionState.Closed)
    val state: StateFlow<SecretPlaintextSessionState> = _state.asStateFlow()
    private var expiryJob: Job? = null
    private val closeListeners = mutableListOf<() -> Unit>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                close(SecretPlaintextSessionCloseReason.SCREEN_LOCKED)
            }
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(screenReceiver, filter)
        }
        appScope.launch {
            settingsStore.settingsFlow.collectLatest { settings ->
                if (!settings.init) validateSettings(settings)
            }
        }
        appScope.launch {
            safetySettings.emergencyStopFlow.collectLatest { stopped ->
                if (stopped) close(SecretPlaintextSessionCloseReason.EMERGENCY_STOP)
            }
        }
        appScope.launch {
            SecondUserAuthorityRegistry.flow.collectLatest { authority ->
                val open = current.get() ?: return@collectLatest
                if (authority == null ||
                    authority.subjectId != open.binding.authoritySubjectId ||
                    authority.authorityEpoch != open.binding.authorityEpoch
                ) {
                    close(SecretPlaintextSessionCloseReason.AUTHORITY_CHANGED)
                }
            }
        }
    }

    suspend fun currentBinding(): SecretPlaintextSessionBinding? = bindingFromSettings(
        settingsStore.settingsFlow.value,
    )

    suspend fun openForCurrent(
        authorization: SecretPlaintextSessionAuthorization,
    ): Boolean {
        if (!secretPlaintextAuthorizationIsFresh(nowMs(), authorization.issuedAtMs)) return false
        val settings = settingsStore.settingsFlow.value
        if (settings.secondUserSecretAccessMode != SecondUserSecretAccessMode.PLAINTEXT_REMOTE_SESSION) return false
        if (isDeviceLocked() || safetySettings.isEmergencyStop()) return false
        val binding = bindingFromSettings(settings) ?: return false
        // Re-authentication is a new capability instance, even when the binding is identical.
        // Clear one-use payloads before resetting the redactor; otherwise an older pending
        // payload could be materialized after its plaintext is no longer in the redaction set.
        if (current.get() != null) {
            close(SecretPlaintextSessionCloseReason.PROCESS_STATE_CHANGED)
        }
        val now = nowMs()
        val open = SecretPlaintextSessionState.Open(binding, now, now + SESSION_TTL_MS)
        current.set(open)
        _state.value = open
        redactor.clear()
        expiryJob?.cancel()
        expiryJob = appScope.launch {
            delay(SESSION_TTL_MS)
            close(SecretPlaintextSessionCloseReason.TTL_EXPIRED)
        }
        return true
    }

    fun close(reason: SecretPlaintextSessionCloseReason = SecretPlaintextSessionCloseReason.MANUAL) {
        current.getAndSet(null) ?: return
        expiryJob?.cancel()
        expiryJob = null
        redactor.clear()
        synchronized(closeListeners) { closeListeners.toList() }.forEach { listener ->
            runCatching(listener)
        }
        _state.value = SecretPlaintextSessionState.Closed
        lastCloseReason.set(reason)
    }

    fun isOpenFor(binding: SecretPlaintextSessionBinding): Boolean {
        val open = current.get() ?: return false
        if (secretPlaintextSessionIsExpired(nowMs(), open.expiresAtMs)) {
            close(SecretPlaintextSessionCloseReason.TTL_EXPIRED)
            return false
        }
        if (isDeviceLocked() || safetySettings.isEmergencyStop()) {
            close(
                if (isDeviceLocked()) SecretPlaintextSessionCloseReason.SCREEN_LOCKED
                else SecretPlaintextSessionCloseReason.EMERGENCY_STOP,
            )
            return false
        }
        val active = SecondUserAuthorityRegistry.current()
        if (active?.subjectId != binding.authoritySubjectId || active.authorityEpoch != binding.authorityEpoch) {
            close(SecretPlaintextSessionCloseReason.AUTHORITY_CHANGED)
            return false
        }
        if (open.binding != binding) {
            close(SecretPlaintextSessionCloseReason.MODEL_OR_PROVIDER_CHANGED)
            return false
        }
        return true
    }

    internal suspend fun <T> withPlaintext(
        slotId: String,
        binding: SecretPlaintextSessionBinding,
        block: (CharArray) -> T,
    ): SecretLeaseResult<T> {
        if (!isOpenFor(binding)) return SecretLeaseResult.AuthorityDenied
        return vault.withRemoteSessionSecret(slotId, binding.authoritySubjectId) { chars ->
            redactor.remember(chars)
            block(chars)
        }
    }

    internal suspend fun transformSecret(
        slotId: String,
        binding: SecretPlaintextSessionBinding,
        transform: (CharArray) -> CharArray,
    ): SecretLeaseResult<Unit> {
        if (!isOpenFor(binding)) return SecretLeaseResult.AuthorityDenied
        return vault.transformForRemoteSession(slotId, binding.authoritySubjectId) { chars ->
            redactor.remember(chars)
            val transformed = transform(chars)
            redactor.remember(transformed)
            transformed
        }
    }

    fun lastCloseReason(): SecretPlaintextSessionCloseReason? = lastCloseReason.get()

    fun addCloseListener(listener: () -> Unit) {
        synchronized(closeListeners) { closeListeners += listener }
    }

    private fun validateSettings(settings: Settings) {
        val open = current.get() ?: return
        if (settings.secondUserSecretAccessMode != SecondUserSecretAccessMode.PLAINTEXT_REMOTE_SESSION) {
            close(SecretPlaintextSessionCloseReason.MODE_DISABLED)
            return
        }
        val live = bindingFromSettings(settings)
        if (live == null || live != open.binding) {
            close(SecretPlaintextSessionCloseReason.MODEL_OR_PROVIDER_CHANGED)
        }
    }

    private fun bindingFromSettings(settings: Settings): SecretPlaintextSessionBinding? {
        if (settings.init) return null
        val active = SecondUserAuthorityRegistry.current() ?: return null
        val assistant = settings.assistants.firstOrNull { it.id == active.assistantId } ?: return null
        val modelId = assistant.chatModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return SecretPlaintextSessionBinding(
            authoritySubjectId = active.subjectId,
            authorityEpoch = active.authorityEpoch,
            assistantId = active.assistantId.toString(),
            conversationId = active.conversationId.toString(),
            modelId = model.id.toString(),
            providerId = provider.id.toString(),
        )
    }

    private fun isDeviceLocked(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.let { it.isDeviceLocked || it.isKeyguardLocked } == true

    private companion object {
        const val SESSION_TTL_MS = 30 * 60 * 1000L
        val lastCloseReason = AtomicReference<SecretPlaintextSessionCloseReason?>(null)
    }
}

internal fun secretPlaintextAuthorizationIsFresh(nowMs: Long, issuedAtMs: Long): Boolean =
    nowMs - issuedAtMs in 0..(2 * 60 * 1000L)

internal fun secretPlaintextSessionIsExpired(nowMs: Long, expiresAtMs: Long): Boolean =
    nowMs >= expiresAtMs
