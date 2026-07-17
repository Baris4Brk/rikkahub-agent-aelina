package me.rerere.rikkahub.data.notifications

import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of a Notification at the moment it was posted. The listener service stores
 * these in a 100-entry ring buffer so the LLM can query history even after the source
 * notification was dismissed by its owning app.
 */
data class NotificationEntry(
    val key: String,
    val packageName: String,
    val label: String,
    val title: String,
    val text: String,
    val subText: String,
    val postTimeMs: Long,
    val actionTitles: List<String>,
    /** True for foreground-service / persistent notifications (FLAG_ONGOING_EVENT). */
    val ongoing: Boolean = false,
    /** Raw Android notification category. Kept in memory with the notification snapshot. */
    val category: String? = null,
)

/** A verification code derived on demand from an in-memory notification snapshot. */
internal data class NotificationVerificationCode(
    val code: String,
    val sourcePackage: String,
    val sourceLabel: String,
    val observedAtMs: Long,
)

private const val VERIFICATION_CODE_TTL_MS = 10 * 60_000L
private const val VERIFICATION_CODE_CONTEXT_DISTANCE = 80

private val verificationCodePattern = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
private val verificationContextPattern = Regex(
    pattern = """验证码|校验码|动态码|一次性密码|短信码|安全码|(?i:verification\s*code|security\s*code|auth(?:entication)?\s*code|one[-\s]*time\s*(?:password|code)|passcode|\botp\b)""",
)

/**
 * Extract an OTP-like value only when a nearby verification-code marker exists. This is
 * deliberately computed at response time rather than stored in a database or a second cache.
 */
internal fun NotificationEntry.verificationCodeOrNull(
    nowMs: Long = System.currentTimeMillis(),
): NotificationVerificationCode? {
    if (nowMs < postTimeMs || nowMs - postTimeMs > VERIFICATION_CODE_TTL_MS) return null
    val content = listOf(title, text, subText).filter { it.isNotBlank() }.joinToString("\n")
    val contextRanges = verificationContextPattern.findAll(content).map { it.range }.toList()
    if (contextRanges.isEmpty()) return null

    val candidate = verificationCodePattern.findAll(content)
        .map { match ->
            val distance = contextRanges.minOf { context -> rangeDistance(match.range, context) }
            match to distance
        }
        .filter { (_, distance) -> distance <= VERIFICATION_CODE_CONTEXT_DISTANCE }
        .minByOrNull { (_, distance) -> distance }
        ?.first
        ?: return null

    return NotificationVerificationCode(
        code = candidate.value,
        sourcePackage = packageName,
        sourceLabel = label,
        observedAtMs = postTimeMs,
    )
}

private fun rangeDistance(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last
    second.last < first.first -> first.first - second.last
    else -> 0
}

private val sensitiveNotificationTerms = listOf(
    "支付", "付款", "转账", "汇款", "收款", "扣款", "银行", "交易",
    "payment", "bank", "transfer", "transaction", "purchase",
)
private val confirmationActionPattern = Regex(
    pattern = """确认|确定|同意|允许|继续|支付|付款|转账|汇款|购买|授权|""" +
        """\bconfirm\b|\bapprove\b|\bauthorize\b|\ballow\b|\bcontinue\b|""" +
        """\bpay\b|\btransfer\b|\bpurchase\b|\bbuy\b|\bsubmit\b|\byes\b""",
    option = RegexOption.IGNORE_CASE,
)
private val passiveActionPattern = Regex(
    pattern = """取消|拒绝|关闭|稍后|查看|详情|打开|""" +
        """\bcancel\b|\bdeny\b|\bdecline\b|\bclose\b|\blater\b|""" +
        """\bview\b|\bdetails\b|\bopen\b""",
    option = RegexOption.IGNORE_CASE,
)

/** Blocks confirming or ambiguous actions on a payment-sensitive notification. */
internal fun shouldBlockSensitiveNotificationAction(
    category: String?,
    title: String,
    text: String,
    subText: String,
    actionTitle: String,
): Boolean {
    val normalizedAction = actionTitle.trim().lowercase()
    val normalizedContent = listOfNotNull(category, title, text, subText)
        .joinToString("\n")
        .lowercase()
    val categoryIsPayment = category?.contains("payment", ignoreCase = true) == true
    val contentIsSensitive = sensitiveNotificationTerms.any(normalizedContent::contains)
    if (!categoryIsPayment && !contentIsSensitive) return false

    // On a financial notification, an unknown or untitled action is not safe to dispatch.
    // Explicit confirming words always win over passive words ("View and pay" stays blocked).
    if (confirmationActionPattern.containsMatchIn(normalizedAction)) return true
    if (passiveActionPattern.containsMatchIn(normalizedAction)) return false
    return true
}

internal data class NotificationEffectSnapshot(
    val key: String,
    val postTimeMs: Long,
    val contentSignature: Int,
)

enum class NotificationObservedEffect {
    NOTIFICATION_REMOVED,
    NOTIFICATION_CHANGED,
}

internal fun detectNotificationEffect(
    before: NotificationEffectSnapshot,
    current: NotificationEffectSnapshot?,
): NotificationObservedEffect? = when {
    current == null -> NotificationObservedEffect.NOTIFICATION_REMOVED
    current != before -> NotificationObservedEffect.NOTIFICATION_CHANGED
    else -> null
}

/**
 * Process-local, short-lived idempotency guard. A reservation is made before dispatch so two
 * concurrent tool calls cannot fire the same PendingIntent twice. Reservations intentionally
 * survive uncertain delivery results: retrying an unknown PendingIntent outcome is unsafe.
 */
internal class NotificationActionDeduplicator(
    private val ttlMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val reservations = ConcurrentHashMap<String, Long>()

    fun reserve(signature: String): Boolean {
        val now = nowMs()
        reservations.forEach { (key, createdAt) ->
            if (now - createdAt >= ttlMs) reservations.remove(key, createdAt)
        }
        while (true) {
            val existing = reservations[signature]
            if (existing != null && now - existing < ttlMs) return false
            if (existing != null) reservations.remove(signature, existing)
            if (reservations.putIfAbsent(signature, now) == null) return true
        }
    }
}
