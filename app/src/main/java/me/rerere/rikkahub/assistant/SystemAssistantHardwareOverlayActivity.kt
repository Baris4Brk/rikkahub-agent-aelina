package me.rerere.rikkahub.assistant

import android.app.KeyguardManager
import android.graphics.Color
import android.os.Bundle
import android.os.UserManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.koin.android.ext.android.inject

/**
 * Activity-hosted fallback for Honor's AI key when YOYO owns the platform assistant role.
 *
 * The exported lightweight entry validates the hardware action first. This internal activity
 * then hosts the same controller-backed surface as VoiceInteractionSession, so changing the
 * default navigation-bar assistant does not break the independently configured AI key.
 */
class SystemAssistantHardwareOverlayActivity : ComponentActivity() {
    private val adapter: AndroidSystemAssistantSessionAdapter by inject()
    private var surfaceCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSystemAssistantHardwareInvocationAction(intent?.action) || !mayShow()) {
            finish()
            return
        }

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.28f }
        val density = resources.displayMetrics.density
        val horizontalPadding = (12 * density).toInt()
        val bottomPadding = (18 * density).toInt()
        val content = adapter.createActivityContentView(this)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(horizontalPadding, 0, horizontalPadding, bottomPadding)
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM,
                    ),
                )
            },
        )
        surfaceCreated = true
    }

    override fun onStart() {
        super.onStart()
        if (surfaceCreated) adapter.onActivityShow(this)
    }

    override fun onStop() {
        if (surfaceCreated) adapter.onActivityHide(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (surfaceCreated) adapter.onActivityDestroy(this)
        super.onDestroy()
    }

    private fun mayShow(): Boolean {
        val userManager = getSystemService(UserManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        return userManager != null && keyguardManager != null &&
            shouldShowLocalSystemAssistant(
                isSystemUser = userManager.isSystemUser,
                isDeviceLocked = keyguardManager.isDeviceLocked,
                isKeyguardLocked = keyguardManager.isKeyguardLocked,
            )
    }
}
