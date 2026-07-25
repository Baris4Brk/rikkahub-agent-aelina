package me.rerere.rikkahub.context

import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.service.RikkaNotificationListenerService

internal const val BATTERY_REMINDER_THRESHOLD_PERCENT = 20

/**
 * Battery data is useful only when it calls for action. Do not put a normal charge level into
 * automatic model context: otherwise the model tends to turn an observation such as "93% and
 * charging" into an unsolicited reminder on every turn.
 */
internal fun shouldExposeBatteryReminder(
    percent: Int?,
    charging: Boolean,
): Boolean = percent != null && percent <= BATTERY_REMINDER_THRESHOLD_PERCENT && !charging

class AndroidAccessibilityContextReader(
    context: Context,
) : ContextSourceReader {
    private val appContext = context.applicationContext

    override suspend fun read(
        request: ContextRequest,
        source: ContextSource,
    ): ContextReadResult {
        if (request.targetDisplaySessionId != null) {
            // Display Session ownership is resolved by DisplayAutomationRuntime. Until a
            // session supplies an owned display root, never fall back to the primary screen.
            return ContextReadResult.Unavailable("display_session_not_bound")
        }
        val service = RikkaAccessibilityService.instance
            ?: return ContextReadResult.Unavailable("accessibility_service_unavailable")
        val root = selectUserWindowRoot(service)
            ?: return ContextReadResult.Unavailable("no_non_self_window")
        return when (source) {
            ContextSource.FOREGROUND_WINDOW -> foregroundWindow(root)
            ContextSource.UI_TREE -> uiTree(root)
            else -> ContextReadResult.Unavailable("unsupported_accessibility_source")
        }
    }

    private fun selectUserWindowRoot(
        service: RikkaAccessibilityService,
    ): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() != appContext.packageName) {
            return active
        }
        return service.windows
            .asSequence()
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .firstOrNull { root -> root.packageName?.toString() != appContext.packageName }
    }

    private fun foregroundWindow(root: AccessibilityNodeInfo): ContextReadResult {
        val packageName = root.packageName?.toString().orEmpty()
        val title = root.window?.title?.toString().orEmpty()
        val className = root.className?.toString().orEmpty()
        val text = buildString {
            append("package=").append(packageName.ifBlank { "unknown" })
            if (title.isNotBlank()) append("; window=").append(title.take(160))
            if (className.isNotBlank()) append("; class=").append(className.takeLast(120))
        }
        return ContextReadResult.Available(
            ContextFragment(ContextSource.FOREGROUND_WINDOW, text)
        )
    }

    private fun uiTree(root: AccessibilityNodeInfo): ContextReadResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val lines = mutableListOf<String>()
        var visited = 0
        var validNodes = 0
        var nonSensitiveChars = 0
        while (queue.isNotEmpty() && visited++ < MAX_VISITED_NODES && lines.size < MAX_LINES) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val sensitive = isSensitiveNode(node)
                val rawText = node.text?.toString().orEmpty()
                val rawDescription = node.contentDescription?.toString().orEmpty()
                val text = if (sensitive && rawText.isNotBlank()) "[REDACTED]" else rawText
                val description = if (sensitive && rawDescription.isNotBlank()) {
                    "[REDACTED]"
                } else {
                    rawDescription
                }
                val meaningful = node.isClickable || node.isScrollable || node.isEditable ||
                    text.isNotBlank() || description.isNotBlank()
                if (meaningful) {
                    validNodes++
                    if (!sensitive) nonSensitiveChars += rawText.length + rawDescription.length
                    lines += buildString {
                        append(node.className?.toString()?.substringAfterLast('.').orEmpty())
                        if (text.isNotBlank()) append(" text=").append(text.take(180))
                        if (description.isNotBlank()) {
                            append(" description=").append(description.take(180))
                        }
                        if (node.isClickable) append(" clickable")
                        if (node.isScrollable) append(" scrollable")
                        if (node.isEditable) append(" editable")
                        if (!node.isEnabled) append(" disabled")
                    }.take(320)
                }
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(queue::addLast)
            }
        }
        val body = buildString {
            appendLine("package=${root.packageName?.toString().orEmpty()}")
            lines.forEach(::appendLine)
        }.trim()
        return ContextReadResult.Available(
            ContextFragment(
                source = ContextSource.UI_TREE,
                text = body,
                validNodeCount = validNodes,
                nonSensitiveCharacterCount = nonSensitiveChars,
            )
        )
    }

    private fun isSensitiveNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val identity = listOfNotNull(
            node.viewIdResourceName,
            node.hintText?.toString(),
            node.contentDescription?.toString(),
        ).joinToString(" ").lowercase()
        return SENSITIVE_NODE_TERMS.any(identity::contains)
    }

    private companion object {
        const val MAX_VISITED_NODES = 320
        const val MAX_LINES = 120
        val SENSITIVE_NODE_TERMS = listOf(
            "password", "passwd", "passcode", "pin", "otp", "verification", "验证码",
            "银行卡", "card_number", "security_code", "cvv", "api_key", "token", "secret",
        )
    }
}

class AndroidDeviceStatusContextReader(
    context: Context,
) : ContextSourceReader {
    private val appContext = context.applicationContext

    override suspend fun read(
        request: ContextRequest,
        source: ContextSource,
    ): ContextReadResult {
        if (source != ContextSource.DEVICE_STATUS) {
            return ContextReadResult.Unavailable("unsupported_device_source")
        }
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val network = when {
            caps == null -> "offline_or_unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val interactive = appContext.getSystemService(PowerManager::class.java)?.isInteractive
        val locked = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked
        val text = buildString {
            if (shouldExposeBatteryReminder(percent, charging)) {
                append("battery_warning=low; battery=").append(percent).append("%; charging=false; ")
            }
            append("network=").append(network)
            interactive?.let { append("; screen_interactive=").append(it) }
            locked?.let { append("; device_locked=").append(it) }
        }
        return ContextReadResult.Available(ContextFragment(source, text))
    }
}

class AndroidUsageStatsContextReader(
    context: Context,
) : ContextSourceReader {
    private val appContext = context.applicationContext

    override suspend fun read(
        request: ContextRequest,
        source: ContextSource,
    ): ContextReadResult {
        if (source != ContextSource.USAGE_STATS) {
            return ContextReadResult.Unavailable("unsupported_usage_source")
        }
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return ContextReadResult.Unavailable("usage_service_unavailable")
        val now = System.currentTimeMillis()
        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 15 * 60_000L,
            now,
        ).orEmpty().filter { it.totalTimeInForeground > 0L }
            .sortedByDescending { it.lastTimeUsed }
            .take(5)
        if (stats.isEmpty()) return ContextReadResult.Unavailable("usage_access_unavailable")
        val text = stats.joinToString(prefix = "recent_apps=", separator = ", ") { stat ->
            val label = runCatching {
                val info = appContext.packageManager.getApplicationInfo(stat.packageName, 0)
                appContext.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(stat.packageName)
            label.take(80)
        }
        return ContextReadResult.Available(ContextFragment(source, text))
    }
}

class AndroidNotificationContextReader : ContextSourceReader {
    override suspend fun read(
        request: ContextRequest,
        source: ContextSource,
    ): ContextReadResult {
        if (source != ContextSource.NOTIFICATIONS) {
            return ContextReadResult.Unavailable("unsupported_notification_source")
        }
        val service = RikkaNotificationListenerService.instance
            ?: return ContextReadResult.Unavailable("notification_listener_unavailable")
        val entries = service.listActive().sortedByDescending { it.postTimeMs }.take(5)
        if (entries.isEmpty()) return ContextReadResult.Unavailable("no_active_notifications")
        val text = entries.joinToString("\n") { entry ->
            buildString {
                append(entry.label.ifBlank { entry.packageName }.take(80))
                if (entry.title.isNotBlank()) append(": ").append(entry.title.take(160))
                if (entry.text.isNotBlank()) append(" — ").append(entry.text.take(240))
            }
        }
        return ContextReadResult.Available(ContextFragment(source, text))
    }
}

class AndroidOcrContextReader(
    context: Context,
    private val visionClient: VisionDescriptionClient,
) : ContextSourceReader {
    private val appContext = context.applicationContext

    override suspend fun read(
        request: ContextRequest,
        source: ContextSource,
    ): ContextReadResult {
        if (source != ContextSource.OCR_FALLBACK) {
            return ContextReadResult.Unavailable("unsupported_ocr_source")
        }
        if (request.targetDisplaySessionId != null) {
            return ContextReadResult.Unavailable("display_session_not_bound")
        }
        val service = RikkaAccessibilityService.instance
            ?: return ContextReadResult.Unavailable("accessibility_service_unavailable")
        return when (val capture = service.captureScreenshot(0)) {
            is RikkaAccessibilityService.ScreenshotOutcome.Failure ->
                ContextReadResult.Unavailable("screenshot_${capture.reason}")
            is RikkaAccessibilityService.ScreenshotOutcome.Success -> describeTemporary(
                request,
                capture.bitmap,
            )
        }
    }

    private suspend fun describeTemporary(
        request: ContextRequest,
        bitmap: Bitmap,
    ): ContextReadResult {
        val directory = File(appContext.cacheDir, "auto-context").apply { mkdirs() }
        val file = File(directory, "screen-${opaqueRunId(request.runId)}.png")
        return try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 92, output)) {
                    "temporary_screenshot_encode_failed"
                }
            }
            visionClient.describe(file).fold(
                onSuccess = { description ->
                    ContextReadResult.Available(
                        ContextFragment(
                            source = ContextSource.OCR_FALLBACK,
                            text = description.text,
                            provider = description.providerLabel,
                        )
                    )
                },
                onFailure = { failure ->
                    ContextReadResult.Unavailable(
                        failure.message?.take(80) ?: "vision_description_failed",
                    )
                },
            )
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            ContextReadResult.Unavailable("temporary_screenshot_failed")
        } finally {
            bitmap.recycle()
            runCatching { file.delete() }
            if (directory.listFiles().isNullOrEmpty()) runCatching { directory.delete() }
        }
    }

    private fun opaqueRunId(runId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(runId.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
