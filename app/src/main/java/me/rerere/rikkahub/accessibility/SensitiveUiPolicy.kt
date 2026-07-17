package me.rerere.rikkahub.accessibility

enum class UiAutomationAction {
    WAIT,
    CLICK,
    SET_TEXT,
    SCROLL,
}

data class UiSafetyDecision(
    val allowed: Boolean,
    val code: String,
    val message: String,
)

/**
 * A deliberately conservative guard for UI surfaces where unattended automation can cause
 * irreversible account, payment, or device-control changes.
 */
object SensitiveUiPolicy {
    private val passwordTerms = listOf(
        "password", "passcode", "screen lock", "unlock code", "密码", "口令", "锁屏密码",
    )
    private val biometricTerms = listOf(
        "biometric", "fingerprint", "face recognition", "face unlock", "生物识别", "指纹", "人脸识别",
    )
    private val paymentTerms = listOf(
        "payment", "pay now", "bank", "transfer", "remit",
        "付款", "支付", "银行", "转账", "汇款",
    )
    private val destructiveTerms = listOf(
        "factory reset", "erase all data", "reset phone", "remove device admin",
        "deactivate device administrator", "remove device owner", "恢复出厂", "清除所有数据",
        "删除设备管理员", "停用设备管理", "移除设备所有者",
    )
    private val otpTerms = listOf(
        "verification code", "one-time code", "one time code", "otp", "验证码", "校验码", "动态码",
    )
    private val confirmationTerms = listOf(
        "confirm", "submit", "continue", "verify", "pay", "transfer",
        "确认", "提交", "继续", "验证", "支付", "转账",
    )

    fun check(
        window: UiWindowSnapshot,
        action: UiAutomationAction,
        targetSelector: UiNodeSelector? = null,
    ): UiSafetyDecision {
        val visible = buildString {
            append(window.title.orEmpty()).append(' ')
            append(window.className.orEmpty()).append(' ')
            window.nodes.forEach { node ->
                append(node.text.orEmpty()).append(' ')
                append(node.contentDescription.orEmpty()).append(' ')
            }
        }.lowercase()

        val otpSurface = visible.containsAny(otpTerms)
        val explicitPasswordSurface = visible.containsAny(passwordTerms)

        val reason = when {
            explicitPasswordSurface || (window.nodes.any(UiNodeSnapshot::password) && !otpSurface) ->
                "PASSWORD_SURFACE"
            visible.containsAny(biometricTerms) -> "BIOMETRIC_SURFACE"
            visible.containsAny(paymentTerms) -> "PAYMENT_OR_TRANSFER_SURFACE"
            visible.containsAny(destructiveTerms) -> "DEVICE_CONTROL_SURFACE"
            else -> null
        }
        if (reason != null) {
            return UiSafetyDecision(
                allowed = false,
                code = "SENSITIVE_UI_BLOCKED",
                message = "Verified accessibility stopped on a protected $reason page.",
            )
        }

        // Filling an OTP is permitted, but the assistant must hand control back to the user
        // before pressing the page's confirmation/submit action.
        if (action == UiAutomationAction.CLICK && otpSurface) {
            val target = listOfNotNull(
                targetSelector?.text,
                targetSelector?.contentDescription,
                targetSelector?.viewId,
            ).joinToString(" ").lowercase()
            if (target.containsAny(confirmationTerms)) {
                return UiSafetyDecision(
                    allowed = false,
                    code = "OTP_CONFIRMATION_REQUIRES_USER",
                    message = "The verification code may be filled, but confirmation must be performed by the user.",
                )
            }
        }

        return UiSafetyDecision(true, "ALLOWED", "UI operation allowed.")
    }

    private fun String.containsAny(terms: List<String>): Boolean = terms.any(::contains)
}
