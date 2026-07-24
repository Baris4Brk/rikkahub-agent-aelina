package me.rerere.rikkahub.quickcapture

import android.app.KeyguardManager
import android.content.Context
import android.os.Process

/** Platform seam so owner-user and lock-screen admission remain JVM-testable. */
interface QuickCaptureAccessState {
    fun isOwnerUser(): Boolean

    fun isDeviceLocked(): Boolean
}

class AndroidQuickCaptureAccessState(
    context: Context,
) : QuickCaptureAccessState {
    private val appContext = context.applicationContext

    // Android's public process UID layout reserves 100,000 app IDs per Android user. Avoid a
    // hidden UserHandle API here: user 100 must never be treated as the owner surface.
    override fun isOwnerUser(): Boolean = Process.myUid() / ANDROID_USER_UID_RANGE == 0

    override fun isDeviceLocked(): Boolean = appContext
        .getSystemService(KeyguardManager::class.java)
        ?.isDeviceLocked
        ?: true

    private companion object {
        const val ANDROID_USER_UID_RANGE = 100_000
    }
}
