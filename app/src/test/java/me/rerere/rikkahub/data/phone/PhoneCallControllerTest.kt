package me.rerere.rikkahub.data.phone

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallControllerTest {
    @Test
    fun `single SIM is selected automatically when placing a call`() = runBlocking {
        val account = PhoneAccountOption(
            key = PhoneAccountKey("com.android.phone/.TelephonyConnectionService", "sim-1"),
            label = "SIM 1",
        )
        val platform = FakePhoneCallPlatform(accounts = listOf(account))
        val controller = DefaultPhoneCallController(
            platform = platform,
            preferences = InMemoryPhoneCallPreferences(),
        )

        val result = controller.placeCall("13800138000")

        assertTrue(result is PhoneCallResult.Success)
        assertEquals(listOf("13800138000" to account.key), platform.placedCalls)
    }

    @Test
    fun `multiple SIMs require an explicit user selection`() = runBlocking {
        val accounts = listOf(
            phoneAccount("sim-1", "SIM 1"),
            phoneAccount("sim-2", "SIM 2"),
        )
        val platform = FakePhoneCallPlatform(accounts = accounts)
        val controller = DefaultPhoneCallController(platform, InMemoryPhoneCallPreferences())

        val result = controller.placeCall("13800138000")

        assertEquals(PhoneCallResult.AccountSelectionRequired, result)
        assertTrue(platform.placedCalls.isEmpty())
    }

    @Test
    fun `multiple SIMs use the account selected by the user`() = runBlocking {
        val first = phoneAccount("sim-1", "SIM 1")
        val second = phoneAccount("sim-2", "SIM 2")
        val preferences = InMemoryPhoneCallPreferences(initial = second.key)
        val platform = FakePhoneCallPlatform(accounts = listOf(first, second))
        val controller = DefaultPhoneCallController(platform, preferences)

        val result = controller.placeCall("13800138000")

        assertTrue(result is PhoneCallResult.Success)
        assertEquals(listOf("13800138000" to second.key), platform.placedCalls)
    }

    @Test
    fun `missing selected SIM is rejected instead of falling back`() = runBlocking {
        val missing = phoneAccount("sim-missing", "Old SIM")
        val available = phoneAccount("sim-2", "SIM 2")
        val preferences = InMemoryPhoneCallPreferences(initial = missing.key)
        val platform = FakePhoneCallPlatform(accounts = listOf(available))
        val controller = DefaultPhoneCallController(platform, preferences)

        val result = controller.placeCall("13800138000")

        assertEquals(PhoneCallResult.AccountUnavailable(missing.key), result)
        assertTrue(platform.placedCalls.isEmpty())
    }

    @Test
    fun `missing phone-state permission prevents account discovery`() = runBlocking {
        val platform = FakePhoneCallPlatform(
            hasPhoneStatePermission = false,
            accounts = listOf(phoneAccount("sim-1", "SIM 1")),
        )
        val controller = DefaultPhoneCallController(platform, InMemoryPhoneCallPreferences())

        val result = controller.placeCall("13800138000")

        assertEquals(
            PhoneCallResult.MissingPermission("android.permission.READ_PHONE_STATE"),
            result,
        )
        assertTrue(platform.placedCalls.isEmpty())
    }

    @Test
    fun `invalid phone number never reaches the platform`() = runBlocking {
        val platform = FakePhoneCallPlatform(accounts = listOf(phoneAccount("sim-1", "SIM 1")))
        val controller = DefaultPhoneCallController(platform, InMemoryPhoneCallPreferences())

        val result = controller.placeCall("123;rm -rf /")

        assertEquals(PhoneCallResult.InvalidPhoneNumber, result)
        assertTrue(platform.placedCalls.isEmpty())
    }
}

private fun phoneAccount(id: String, label: String) = PhoneAccountOption(
    key = PhoneAccountKey("com.android.phone/.TelephonyConnectionService", id),
    label = label,
)

private class InMemoryPhoneCallPreferences(
    initial: PhoneAccountKey? = null,
) : PhoneCallPreferences {
    private val selected = MutableStateFlow(initial)

    override val selectedAccount: Flow<PhoneAccountKey?> = selected

    override suspend fun currentAccount(): PhoneAccountKey? = selected.value

    override suspend fun selectAccount(key: PhoneAccountKey?) {
        selected.value = key
    }
}

private class FakePhoneCallPlatform(
    var hasCallPermission: Boolean = true,
    var hasPhoneStatePermission: Boolean = true,
    var accounts: List<PhoneAccountOption> = emptyList(),
) : PhoneCallPlatform {
    val placedCalls = mutableListOf<Pair<String, PhoneAccountKey>>()

    override fun hasCallPermission(): Boolean = hasCallPermission

    override fun hasPhoneStatePermission(): Boolean = hasPhoneStatePermission

    override fun listCallCapableAccounts(): List<PhoneAccountOption> = accounts

    override fun placeCall(phoneNumber: String, account: PhoneAccountKey) {
        placedCalls += phoneNumber to account
    }
}
