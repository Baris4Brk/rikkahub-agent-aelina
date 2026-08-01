package me.rerere.rikkahub.data.telegram

import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecretLeaseResult
import me.rerere.rikkahub.security.SecondUserSecretVault

/**
 * Resolves the Telegram bot token only at the HTTP boundary. Vault plaintext is never copied back
 * into DataStore, tool output, chat history or the Owner operation ledger.
 */
class TelegramCredentialResolver(
    private val preferences: TelegramBotPreferences,
    private val vault: SecondUserSecretVault,
) {
    suspend fun currentToken(): String {
        val config = preferences.current()
        val slotId = config.vaultSlotId?.takeIf { it.isNotBlank() } ?: return config.token
        val authority = SecondUserAuthorityRegistry.current() ?: return ""
        val binding = SecretBinding(SecretBindingKind.CHANNEL, TELEGRAM_BINDING_TARGET)
        return when (val result = vault.withLease(slotId, authority.subjectId, binding) { lease ->
            lease.use { it.concatToString() }
        }) {
            is SecretLeaseResult.Success -> result.value
            else -> ""
        }
    }

    suspend fun credentialAvailable(): Boolean = currentToken().isNotBlank()

    companion object {
        const val TELEGRAM_BINDING_TARGET = "telegram_bot"
    }
}
