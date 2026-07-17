package me.rerere.rikkahub.data.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

class AndroidPhoneCallPlatform(
    context: Context,
) : PhoneCallPlatform {
    private val appContext = context.applicationContext
    private val telecomManager = appContext.getSystemService(TelecomManager::class.java)

    override fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    override fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    override fun listCallCapableAccounts(): List<PhoneAccountOption> {
        if (!hasPhoneStatePermission()) return emptyList()
        val manager = telecomManager ?: return emptyList()
        return manager.callCapablePhoneAccounts
            .mapNotNull { handle ->
                val account = runCatching { manager.getPhoneAccount(handle) }.getOrNull()
                    ?: return@mapNotNull null
                val label = account.label?.toString()?.trim().orEmpty()
                    .ifBlank { account.shortDescription?.toString()?.trim().orEmpty() }
                    .ifBlank { handle.id }
                PhoneAccountOption(
                    key = handle.toKey(),
                    label = label,
                )
            }
            .distinctBy { it.key }
    }

    override fun placeCall(phoneNumber: String, account: PhoneAccountKey) {
        val manager = telecomManager ?: error("Telecom service is unavailable.")
        val handle = account.toHandle()
        val currentAccounts = listCallCapableAccounts().map { it.key }.toSet()
        check(account in currentAccounts) { "The selected phone account is no longer available." }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        manager.placeCall(Uri.fromParts("tel", phoneNumber, null), extras)
    }
}

private fun PhoneAccountHandle.toKey(): PhoneAccountKey = PhoneAccountKey(
    componentName = componentName.flattenToString(),
    accountId = id,
)

private fun PhoneAccountKey.toHandle(): PhoneAccountHandle {
    val component = ComponentName.unflattenFromString(componentName)
        ?: error("Invalid phone account component.")
    return PhoneAccountHandle(component, accountId)
}
