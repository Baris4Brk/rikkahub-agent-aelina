package me.rerere.rikkahub.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEntryPolicyTest {

    @Test
    fun `extracts nearby four to eight digit verification code with source metadata`() {
        val entry = entry(
            title = "登录验证码",
            text = "您的验证码为 482731，请勿告诉他人。",
            postTimeMs = 10_000L,
        )

        val result = entry.verificationCodeOrNull(nowMs = 11_000L)

        assertEquals("482731", result?.code)
        assertEquals("example.app", result?.sourcePackage)
        assertEquals("Example", result?.sourceLabel)
        assertEquals(10_000L, result?.observedAtMs)
    }

    @Test
    fun `extracts english otp but ignores unrelated numbers`() {
        assertEquals(
            "9021",
            entry(title = "Security", text = "Your OTP is 9021").verificationCodeOrNull(1_000L)?.code,
        )
        assertNull(entry(title = "Order", text = "Order 482731 is ready").verificationCodeOrNull(1_000L))
        assertNull(entry(title = "OTP", text = "123456789").verificationCodeOrNull(1_000L))
    }

    @Test
    fun `verification code expires from derived response`() {
        val entry = entry(title = "验证码", text = "123456", postTimeMs = 1_000L)

        assertNull(entry.verificationCodeOrNull(nowMs = 11 * 60_000L))
    }

    @Test
    fun `blocks confirming action on payment category or financial content`() {
        assertTrue(
            shouldBlockSensitiveNotificationAction(
                category = "payment",
                title = "Checkout",
                text = "Ready",
                subText = "",
                actionTitle = "Confirm",
            ),
        )
        assertTrue(
            shouldBlockSensitiveNotificationAction(
                category = null,
                title = "银行转账确认",
                text = "向收款人付款",
                subText = "",
                actionTitle = "确认转账",
            ),
        )
        assertTrue(
            shouldBlockSensitiveNotificationAction(
                category = "payment",
                title = "Payment approval",
                text = "",
                subText = "",
                actionTitle = "",
            ),
        )
    }

    @Test
    fun `does not block viewing a financial notification or confirming unrelated action`() {
        assertFalse(
            shouldBlockSensitiveNotificationAction(
                category = "payment",
                title = "Payment receipt",
                text = "Completed",
                subText = "",
                actionTitle = "View details",
            ),
        )
        assertFalse(
            shouldBlockSensitiveNotificationAction(
                category = null,
                title = "Calendar",
                text = "Meeting invitation",
                subText = "",
                actionTitle = "Confirm",
            ),
        )
    }

    @Test
    fun `deduplicator suppresses same action until ttl but permits distinct action`() {
        var now = 1_000L
        val deduplicator = NotificationActionDeduplicator(ttlMs = 500L) { now }

        assertTrue(deduplicator.reserve("notification-1|reply"))
        assertFalse(deduplicator.reserve("notification-1|reply"))
        assertTrue(deduplicator.reserve("notification-1|mark-read"))

        now += 500L
        assertTrue(deduplicator.reserve("notification-1|reply"))
    }

    @Test
    fun `effect comparison distinguishes unchanged changed and removed notification`() {
        val before = NotificationEffectSnapshot("key", 100L, 1)

        assertNull(detectNotificationEffect(before, before))
        assertEquals(
            NotificationObservedEffect.NOTIFICATION_CHANGED,
            detectNotificationEffect(before, before.copy(contentSignature = 2)),
        )
        assertEquals(
            NotificationObservedEffect.NOTIFICATION_REMOVED,
            detectNotificationEffect(before, null),
        )
    }

    private fun entry(
        title: String,
        text: String,
        postTimeMs: Long = 0L,
    ) = NotificationEntry(
        key = "key",
        packageName = "example.app",
        label = "Example",
        title = title,
        text = text,
        subText = "",
        postTimeMs = postTimeMs,
        actionTitles = emptyList(),
    )
}
