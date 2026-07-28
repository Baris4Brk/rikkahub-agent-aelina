package me.rerere.rikkahub.pet.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DesktopPetBootReceiver : BroadcastReceiver(), KoinComponent {
    private val settingsStore: SettingsStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = settingsStore.settingsFlow.first { !it.init }
                if (settings.assistants.any { it.petEnabled && it.petBootRestoreEnabled }) {
                    DesktopPetService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
