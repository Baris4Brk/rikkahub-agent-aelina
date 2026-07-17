package me.rerere.rikkahub.assistant

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json

internal val HONOR_AI_KEY_SETTING_KEYS = listOf(
    "ai_key_short_service_info",
    "ai_key_double_click_service_info",
    "ai_key_long_service_info",
)

internal const val HONOR_AI_KEY_DESIRED_BINDING =
    """{"commonIntent":"intent:#Intent;action=me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_HARDWARE;package=me.rerere.rikkahub;component=me.rerere.rikkahub/.assistant.SystemAssistantOverlayEntryActivity;end","isSubService":false,"isSupportScreenLockStart":"0","launchAnim":"0","lockScreenIntent":"","packageName":"me.rerere.rikkahub","serviceId":"rikka_second_user_overlay","startType":0}"""

private val honorAiKeyDesiredJson = Json.parseToJsonElement(HONOR_AI_KEY_DESIRED_BINDING)

internal data class HonorAiKeyBindingEnvironment(
    val isSupportedDevice: Boolean,
    val isSystemUser: Boolean,
    val mayWriteGlobalSettings: Boolean,
)

internal fun planHonorAiKeyBindingRepairs(
    environment: HonorAiKeyBindingEnvironment,
    currentBindings: Map<String, String?>,
): Map<String, String> {
    if (!environment.isSupportedDevice ||
        !environment.isSystemUser ||
        !environment.mayWriteGlobalSettings
    ) {
        return emptyMap()
    }
    return HONOR_AI_KEY_SETTING_KEYS
        .filter { key ->
            runCatching {
                Json.parseToJsonElement(currentBindings[key].orEmpty()) != honorAiKeyDesiredJson
            }.getOrDefault(true)
        }
        .associateWith { HONOR_AI_KEY_DESIRED_BINDING }
}

internal interface HonorAiKeyBindingStore {
    fun readBindings(): Map<String, String?>
    fun writeBinding(key: String, value: String): Boolean
    fun startObserving(onChanged: () -> Unit)
    fun stopObserving()
}

internal class HonorAiKeyBindingGuard(
    private val store: HonorAiKeyBindingStore,
    private val environment: () -> HonorAiKeyBindingEnvironment,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val reconciling = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        store.startObserving(::reconcile)
        reconcile()
    }

    override fun close() {
        if (!started.compareAndSet(true, false)) return
        store.stopObserving()
    }

    private fun reconcile() {
        if (!started.get() || !reconciling.compareAndSet(false, true)) return
        try {
            planHonorAiKeyBindingRepairs(
                environment = environment(),
                currentBindings = store.readBindings(),
            ).forEach { (key, value) ->
                store.writeBinding(key, value)
            }
        } finally {
            reconciling.set(false)
        }
    }
}

internal class AndroidHonorAiKeyBindingStore(context: Context) : HonorAiKeyBindingStore {
    private val resolver = context.applicationContext.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var pendingNotification: Runnable? = null

    override fun readBindings(): Map<String, String?> = HONOR_AI_KEY_SETTING_KEYS.associateWith { key ->
        runCatching { Settings.Global.getString(resolver, key) }
            .onFailure { error -> Log.w(TAG, "Unable to read $key", error) }
            .getOrNull()
    }

    override fun writeBinding(key: String, value: String): Boolean {
        if (key !in HONOR_AI_KEY_SETTING_KEYS) return false
        return runCatching { Settings.Global.putString(resolver, key, value) }
            .onFailure { error -> Log.w(TAG, "Unable to restore $key", error) }
            .getOrDefault(false)
    }

    override fun startObserving(onChanged: () -> Unit) {
        stopObserving()
        val notification = Runnable(onChanged)
        pendingNotification = notification
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(notification)
                handler.postDelayed(notification, OBSERVER_DEBOUNCE_MS)
            }
        }.also { registered ->
            HONOR_AI_KEY_SETTING_KEYS.forEach { key ->
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, registered)
            }
        }
    }

    override fun stopObserving() {
        pendingNotification?.let(handler::removeCallbacks)
        pendingNotification = null
        observer?.let { registered -> runCatching { resolver.unregisterContentObserver(registered) } }
        observer = null
    }

    private companion object {
        const val TAG = "RikkaAiKeyGuard"
        const val OBSERVER_DEBOUNCE_MS = 300L
    }
}

internal fun createHonorAiKeyBindingGuard(context: Context): HonorAiKeyBindingGuard {
    val appContext = context.applicationContext
    return HonorAiKeyBindingGuard(
        store = AndroidHonorAiKeyBindingStore(appContext),
        environment = {
            val userManager = appContext.getSystemService(UserManager::class.java)
            HonorAiKeyBindingEnvironment(
                isSupportedDevice = Build.MANUFACTURER.equals("HONOR", ignoreCase = true) &&
                    Build.MODEL.equals("AAK-AN00", ignoreCase = true),
                isSystemUser = userManager?.isSystemUser == true,
                mayWriteGlobalSettings = appContext.checkSelfPermission(WRITE_SECURE_SETTINGS_PERMISSION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        },
    )
}

private const val WRITE_SECURE_SETTINGS_PERMISSION =
    "android.permission.WRITE_SECURE_SETTINGS"
