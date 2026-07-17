package me.rerere.rikkahub.data.phone

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

private val PHONE_NUMBER_PATTERN = Regex("^[+]?[0-9\\-\\s()]{5,20}$")

data class PhoneAccountKey(
    val componentName: String,
    val accountId: String,
)

data class PhoneAccountOption(
    val key: PhoneAccountKey,
    val label: String,
)

data class PhoneCallState(
    val hasCallPermission: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val accounts: List<PhoneAccountOption> = emptyList(),
    val selectedAccount: PhoneAccountKey? = null,
) {
    val selectedOption: PhoneAccountOption?
        get() = selectedAccount?.let { selected -> accounts.firstOrNull { it.key == selected } }

    val effectiveOption: PhoneAccountOption?
        get() = selectedOption ?: accounts.singleOrNull().takeIf { selectedAccount == null }

    val selectedAccountUnavailable: Boolean
        get() = selectedAccount != null && selectedOption == null
}

sealed interface PhoneCallResult {
    data class Success(
        val phoneNumber: String,
        val account: PhoneAccountOption,
    ) : PhoneCallResult

    data class MissingPermission(val permission: String) : PhoneCallResult
    data object NoAvailableAccount : PhoneCallResult
    data object AccountSelectionRequired : PhoneCallResult
    data class AccountUnavailable(val selectedAccount: PhoneAccountKey) : PhoneCallResult
    data object InvalidPhoneNumber : PhoneCallResult
    data class Failed(val message: String) : PhoneCallResult
}

interface PhoneCallPreferences {
    val selectedAccount: Flow<PhoneAccountKey?>

    suspend fun currentAccount(): PhoneAccountKey?

    suspend fun selectAccount(key: PhoneAccountKey?)
}

interface PhoneCallPlatform {
    fun hasCallPermission(): Boolean

    fun hasPhoneStatePermission(): Boolean

    fun listCallCapableAccounts(): List<PhoneAccountOption>

    fun placeCall(phoneNumber: String, account: PhoneAccountKey)
}

interface PhoneCallController {
    val state: StateFlow<PhoneCallState>

    suspend fun refresh()

    suspend fun selectAccount(key: PhoneAccountKey)

    suspend fun placeCall(phoneNumber: String): PhoneCallResult
}

class DefaultPhoneCallController(
    private val platform: PhoneCallPlatform,
    private val preferences: PhoneCallPreferences,
) : PhoneCallController {
    private val _state = MutableStateFlow(PhoneCallState())
    override val state: StateFlow<PhoneCallState> = _state.asStateFlow()

    override suspend fun refresh() {
        val hasCallPermission = platform.hasCallPermission()
        val hasPhoneStatePermission = platform.hasPhoneStatePermission()
        val accounts = if (hasPhoneStatePermission) {
            try {
                platform.listCallCapableAccounts()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
        _state.value = PhoneCallState(
            hasCallPermission = hasCallPermission,
            hasPhoneStatePermission = hasPhoneStatePermission,
            accounts = accounts,
            selectedAccount = preferences.currentAccount(),
        )
    }

    override suspend fun selectAccount(key: PhoneAccountKey) {
        refresh()
        if (_state.value.accounts.none { it.key == key }) return
        preferences.selectAccount(key)
        refresh()
    }

    override suspend fun placeCall(phoneNumber: String): PhoneCallResult {
        val normalizedNumber = phoneNumber.trim()
        if (!PHONE_NUMBER_PATTERN.matches(normalizedNumber)) {
            return PhoneCallResult.InvalidPhoneNumber
        }
        if (!platform.hasCallPermission()) {
            return PhoneCallResult.MissingPermission("android.permission.CALL_PHONE")
        }
        if (!platform.hasPhoneStatePermission()) {
            return PhoneCallResult.MissingPermission("android.permission.READ_PHONE_STATE")
        }

        val accounts = try {
            platform.listCallCapableAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return PhoneCallResult.Failed(t.message ?: "Unable to read call-capable phone accounts.")
        }
        if (accounts.isEmpty()) return PhoneCallResult.NoAvailableAccount

        val selectedKey = preferences.currentAccount()
        val selected = when {
            selectedKey != null -> accounts.firstOrNull { it.key == selectedKey }
                ?: return PhoneCallResult.AccountUnavailable(selectedKey)
            accounts.size == 1 -> accounts.single()
            else -> return PhoneCallResult.AccountSelectionRequired
        }

        return try {
            platform.placeCall(normalizedNumber, selected.key)
            refresh()
            PhoneCallResult.Success(normalizedNumber, selected)
        } catch (e: CancellationException) {
            throw e
        } catch (t: SecurityException) {
            PhoneCallResult.MissingPermission("android.permission.CALL_PHONE")
        } catch (t: Throwable) {
            PhoneCallResult.Failed(t.message ?: "The system rejected the phone call.")
        }
    }
}
