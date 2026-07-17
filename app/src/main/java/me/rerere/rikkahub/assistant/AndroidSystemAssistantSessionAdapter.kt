package me.rerere.rikkahub.assistant

import android.app.KeyguardManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.service.chat.RuntimeState
import java.util.WeakHashMap

/**
 * Native-View assistant surface used by both navigation-bar/Home and long-power invocations.
 *
 * A binding exists only while the platform session is visible. Hiding the overlay closes the
 * controller and its visibility token, but does not cancel a chat command already accepted by the
 * per-conversation Runtime.
 */
class AndroidSystemAssistantSessionAdapter(
    context: Context,
    private val controllerFactory: SystemAssistantSessionControllerFactory,
) : SystemAssistantSessionAdapter {
    private val appContext = context.applicationContext
    private val entries = WeakHashMap<Any, SessionEntry>()

    override fun createContentView(session: RikkaVoiceInteractionSession): View = synchronized(entries) {
        entries.getOrPut(session) { SessionEntry(createViews(session.context)) }.views.root
    }

    override fun onShow(
        session: RikkaVoiceInteractionSession,
        args: android.os.Bundle?,
        showFlags: Int,
    ) = bindSurface(
        surface = session,
        viewContext = session.context,
        hostKind = SystemAssistantHostKind.VOICE_SESSION,
        finishSurface = session::finish,
    )

    override fun onHide(session: RikkaVoiceInteractionSession) {
        unbindSurface(session)
    }

    override fun onDestroy(session: RikkaVoiceInteractionSession) {
        destroySurface(session)
    }

    fun createActivityContentView(activity: Activity): View = synchronized(entries) {
        entries.getOrPut(activity) { SessionEntry(createViews(activity)) }.views.root
    }

    fun onActivityShow(activity: Activity) {
        bindSurface(
            surface = activity,
            viewContext = activity,
            hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
            finishSurface = activity::finish,
        )
    }

    fun onActivityHide(activity: Activity) {
        unbindSurface(activity)
    }

    fun onActivityDestroy(activity: Activity) {
        destroySurface(activity)
    }

    private fun bindSurface(
        surface: Any,
        viewContext: Context,
        hostKind: SystemAssistantHostKind,
        finishSurface: () -> Unit,
    ) {
        val entry = synchronized(entries) {
            entries.getOrPut(surface) { SessionEntry(createViews(viewContext)) }
        }
        entry.closeBinding(appContext)
        val invokedFromKeyguard = isDeviceLocked()
        val controller = controllerFactory.create(invokedFromKeyguard, hostKind)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        entry.controller = controller
        entry.scope = scope
        entry.submitJob = null
        entry.deviceLocked = invokedFromKeyguard
        entry.hostKind = hostKind
        entry.lockReceiver = createLockReceiver(entry, finishSurface)
        registerLockReceiver(entry.lockReceiver!!)
        entry.views.input.setText("")
        entry.views.send.setOnClickListener {
            val text = entry.views.input.text?.toString().orEmpty()
            if (entry.submitJob?.isActive == true) return@setOnClickListener
            entry.submitJob = scope.launch {
                when (controller.submitText(text)) {
                    is SystemAssistantSubmitResult.Accepted -> entry.views.input.setText("")
                    is SystemAssistantSubmitResult.Rejected -> Unit
                }
            }
        }
        entry.views.close.setOnClickListener { finishSurface() }
        entry.views.openChat.setOnClickListener {
            if (entry.deviceLocked ||
                controller.state.value.inputAvailability ==
                SystemAssistantInputAvailability.InvokedFromKeyguard
            ) return@setOnClickListener
            controller.state.value.conversationId?.let { conversationId ->
                openMainApp(RouteActivity.EXTRA_CONVERSATION_ID, conversationId.toString())
                finishSurface()
            }
        }
        entry.views.configure.setOnClickListener {
            if (entry.deviceLocked ||
                controller.state.value.inputAvailability ==
                SystemAssistantInputAvailability.InvokedFromKeyguard
            ) return@setOnClickListener
            openMainApp(RouteActivity.EXTRA_OPEN_SYSTEM_ASSISTANT_SETTINGS, true)
            finishSurface()
        }
        scope.launch {
            controller.state.collectLatest { state ->
                entry.lastState = state
                entry.deviceLocked = isDeviceLocked()
                render(entry.views, state, entry.deviceLocked, entry.hostKind)
            }
        }
    }

    private fun unbindSurface(surface: Any) {
        synchronized(entries) { entries[surface] }?.closeBinding(appContext)
    }

    private fun destroySurface(surface: Any) {
        synchronized(entries) { entries.remove(surface) }?.closeBinding(appContext)
    }

    private fun openMainApp(extra: String, value: Any) {
        val intent = Intent(appContext, RouteActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            when (value) {
                is Boolean -> putExtra(extra, value)
                is String -> putExtra(extra, value)
            }
        }
        appContext.startActivity(intent)
    }

    private fun render(
        views: AssistantViews,
        state: SystemAssistantUiState,
        deviceLocked: Boolean,
        hostKind: SystemAssistantHostKind,
    ) {
        views.surface.visibility = if (hostKind == SystemAssistantHostKind.ACTIVITY_OVERLAY) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (hostKind == SystemAssistantHostKind.ACTIVITY_OVERLAY) {
            val commandRunning = state.runtimeState == RuntimeState.Running ||
                state.submission is SystemAssistantSubmissionUiState.Submitting ||
                state.submission is SystemAssistantSubmissionUiState.Accepted
            views.surface.text = appContext.getString(
                if (commandRunning) {
                    R.string.system_assistant_surface_ai_key_overlay_running
                } else {
                    R.string.system_assistant_surface_ai_key_overlay
                }
            )
        }
        val hideSensitiveContent = deviceLocked ||
            state.inputAvailability == SystemAssistantInputAvailability.InvokedFromKeyguard
        if (hideSensitiveContent) {
            views.identity.text = appContext.getString(R.string.system_assistant_unlock_required)
            views.latestUser.text = ""
            views.latestUser.visibility = View.GONE
            views.latestAssistant.text = ""
            views.latestAssistant.visibility = View.GONE
            views.status.text = appContext.getString(R.string.system_assistant_unlock_required)
            views.input.visibility = View.GONE
            views.input.isEnabled = false
            views.send.visibility = View.GONE
            views.send.isEnabled = false
            views.openChat.visibility = View.GONE
            views.openChat.isEnabled = false
            views.configure.visibility = View.GONE
            views.configure.isEnabled = false
            return
        }
        views.input.visibility = View.VISIBLE
        views.send.visibility = View.VISIBLE
        views.openChat.visibility = View.VISIBLE
        views.configure.visibility = View.VISIBLE
        views.identity.text = when (val target = state.target) {
            is SystemAssistantTargetUiState.Ready -> "${target.assistantName} · ${target.displayName}"
            is SystemAssistantTargetUiState.Unavailable -> targetResolutionMessage(target.resolution)
            is SystemAssistantTargetUiState.Failed ->
                appContext.getString(R.string.system_assistant_error_target_resolution_failed)
            SystemAssistantTargetUiState.NotResolved,
            SystemAssistantTargetUiState.Resolving,
            -> appContext.getString(R.string.system_assistant_loading)
        }
        views.latestUser.text = state.latestUserText.orEmpty()
        views.latestUser.visibility = if (state.latestUserText.isNullOrBlank()) View.GONE else View.VISIBLE
        views.latestAssistant.text = state.latestAssistantText.orEmpty()
        views.latestAssistant.visibility =
            if (state.latestAssistantText.isNullOrBlank()) View.GONE else View.VISIBLE
        views.status.text = statusText(state, deviceLocked)
        views.input.isEnabled = state.canSubmit && !deviceLocked
        views.send.isEnabled = state.canSubmit && !deviceLocked &&
            state.target is SystemAssistantTargetUiState.Ready
        views.openChat.isEnabled = state.conversationId != null
        views.configure.isEnabled = true
    }

    private fun statusText(state: SystemAssistantUiState, deviceLocked: Boolean): String = when {
        state.inputAvailability == SystemAssistantInputAvailability.InvokedFromKeyguard ->
            appContext.getString(R.string.system_assistant_unlock_required)
        state.inputAvailability == SystemAssistantInputAvailability.UnsupportedAndroidUser ->
            appContext.getString(R.string.system_assistant_owner_user_required)
        state.inputAvailability == SystemAssistantInputAvailability.Closed ->
            appContext.getString(R.string.system_assistant_close)
        deviceLocked -> appContext.getString(R.string.system_assistant_unlock_required)
        state.target == SystemAssistantTargetUiState.NotResolved ||
            state.target == SystemAssistantTargetUiState.Resolving ->
            appContext.getString(R.string.system_assistant_loading)
        state.answer is SystemAssistantAnswerUiState.Recovering -> {
            val recovery = state.answer
            appContext.getString(
                R.string.final_answer_recovery_in_progress,
                recovery.attempt,
                recovery.maxAttempts,
            )
        }
        state.answer is SystemAssistantAnswerUiState.RecoveryFailed -> {
            val recovery = state.answer
            recovery.attempt?.let { attempt ->
                appContext.getString(R.string.final_answer_recovery_failed, attempt)
            } ?: appContext.getString(R.string.system_assistant_error_request_failed)
        }
        state.submission is SystemAssistantSubmissionUiState.Error ->
            submissionErrorText(state.submission)
        state.queueStatus?.paused == true ->
            appContext.getString(R.string.system_assistant_queue_paused)
        (state.queueStatus?.pendingCount ?: 0) > 0 ->
            appContext.getString(
                R.string.system_assistant_queue_pending,
                state.queueStatus?.pendingCount ?: 0,
            )
        state.runtimeState == RuntimeState.Running ->
            appContext.getString(R.string.system_assistant_generating)
        state.submission is SystemAssistantSubmissionUiState.Submitting ->
            appContext.getString(R.string.system_assistant_submitting)
        state.history == SystemAssistantHistoryUiState.Loading ->
            appContext.getString(R.string.workspace_detail_loading)
        state.history == SystemAssistantHistoryUiState.Failed ->
            appContext.getString(R.string.system_assistant_error_request_failed)
        else -> appContext.getString(R.string.system_assistant_ready)
    }

    private fun submissionErrorText(error: SystemAssistantSubmissionUiState.Error): String =
        when (error.code) {
            SystemAssistantSubmissionErrorCode.EMPTY_TEXT ->
                appContext.getString(R.string.system_assistant_error_empty_text)
            SystemAssistantSubmissionErrorCode.TEXT_TOO_LONG ->
                appContext.getString(
                    R.string.system_assistant_error_text_too_long,
                    SYSTEM_ASSISTANT_MAX_TEXT_LENGTH,
                )
            SystemAssistantSubmissionErrorCode.INVOKED_FROM_KEYGUARD,
            SystemAssistantSubmissionErrorCode.DEVICE_LOCKED,
            -> appContext.getString(R.string.system_assistant_unlock_required)
            SystemAssistantSubmissionErrorCode.UNSUPPORTED_ANDROID_USER ->
                appContext.getString(R.string.system_assistant_owner_user_required)
            SystemAssistantSubmissionErrorCode.EMERGENCY_STOP_ACTIVE ->
                appContext.getString(R.string.system_assistant_error_emergency_stop_active)
            SystemAssistantSubmissionErrorCode.EMERGENCY_STOP_CHECK_FAILED ->
                appContext.getString(R.string.system_assistant_error_safety_check_failed)
            SystemAssistantSubmissionErrorCode.TARGET_UNAVAILABLE ->
                error.targetResolution?.let(::targetResolutionMessage)
                    ?: appContext.getString(R.string.system_assistant_error_target_unavailable)
            SystemAssistantSubmissionErrorCode.TARGET_RESOLUTION_FAILED ->
                appContext.getString(R.string.system_assistant_error_target_resolution_failed)
            SystemAssistantSubmissionErrorCode.QUEUE_FULL ->
                appContext.getString(R.string.system_assistant_queue_full)
            SystemAssistantSubmissionErrorCode.BACKEND_REJECTED,
            SystemAssistantSubmissionErrorCode.COMMAND_REJECTED,
            SystemAssistantSubmissionErrorCode.COMMAND_CONFLICT,
            SystemAssistantSubmissionErrorCode.COMMAND_NOT_APPLIED,
            -> appContext.getString(R.string.system_assistant_error_request_rejected)
            SystemAssistantSubmissionErrorCode.RUNTIME_UNAVAILABLE ->
                appContext.getString(R.string.system_assistant_error_runtime_unavailable)
            SystemAssistantSubmissionErrorCode.BACKEND_FAILED,
            SystemAssistantSubmissionErrorCode.COMMAND_FAILED,
            SystemAssistantSubmissionErrorCode.COMMAND_DEPENDENCY_FAILED,
            -> appContext.getString(R.string.system_assistant_error_request_failed)
            SystemAssistantSubmissionErrorCode.COMMAND_CANCELLED ->
                appContext.getString(R.string.system_assistant_error_command_cancelled)
            SystemAssistantSubmissionErrorCode.COMMAND_SUPERSEDED ->
                appContext.getString(R.string.system_assistant_error_command_superseded)
            SystemAssistantSubmissionErrorCode.CONTROLLER_CLOSED,
            SystemAssistantSubmissionErrorCode.OVERLAY_NOT_VISIBLE,
            ->
                appContext.getString(R.string.system_assistant_error_session_closed)
        }

    private fun createLockReceiver(
        entry: SessionEntry,
        finishSurface: () -> Unit,
    ): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (shouldTerminateSystemAssistantInvocation(intent?.action)) {
                    entry.closeBinding(appContext)
                    finishSurface()
                    return
                }
                entry.deviceLocked = isDeviceLocked()
                entry.lastState?.let { state ->
                    render(entry.views, state, entry.deviceLocked, entry.hostKind)
                }
            }
        }

    private fun registerLockReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun isDeviceLocked(): Boolean {
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        return keyguard?.let { it.isDeviceLocked || it.isKeyguardLocked } ?: true
    }

    private fun targetResolutionMessage(resolution: SecondUserTargetResolution): String = when (resolution) {
        SecondUserTargetResolution.TargetNotSelected ->
            appContext.getString(R.string.system_assistant_target_not_selected)
        is SecondUserTargetResolution.AssistantNotFound ->
            appContext.getString(R.string.system_assistant_target_assistant_missing)
        is SecondUserTargetResolution.PrivilegedConversationNotConfigured ->
            appContext.getString(R.string.system_assistant_target_conversation_unconfigured)
        is SecondUserTargetResolution.ConversationNotFound ->
            appContext.getString(R.string.system_assistant_target_conversation_missing)
        is SecondUserTargetResolution.ConversationAssistantMismatch ->
            appContext.getString(R.string.system_assistant_target_conversation_mismatch)
        is SecondUserTargetResolution.Resolved ->
            "${resolution.assistantName} · ${resolution.displayName}"
    }

    private fun createViews(context: Context): AssistantViews {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                color = android.content.res.ColorStateList.valueOf(Color.rgb(30, 30, 34))
                cornerRadius = dp(24).toFloat()
            }
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.system_assistant_overlay_title)
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        val surface = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        val identity = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        val latestUser = messageView(context, Color.rgb(215, 225, 255))
        val latestAssistant = messageView(context, Color.WHITE)
        val status = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        val input = EditText(context).apply {
            hint = context.getString(R.string.system_assistant_overlay_hint)
            minLines = 2
            maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(SYSTEM_ASSISTANT_MAX_TEXT_LENGTH))
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.rgb(105, 105, 110))
        }
        val send = Button(context).apply { text = context.getString(R.string.system_assistant_send) }
        val close = Button(context).apply { text = context.getString(R.string.system_assistant_close) }
        val openChat = Button(context).apply { text = context.getString(R.string.system_assistant_open_chat) }
        val configure = Button(context).apply {
            text = context.getString(R.string.system_assistant_open_configuration)
        }
        content.addView(title, matchWrap())
        content.addView(surface, matchWrap(top = dp(3)))
        content.addView(identity, matchWrap(top = dp(4)))
        content.addView(latestUser, matchWrap(top = dp(14)))
        content.addView(latestAssistant, matchWrap(top = dp(8)))
        content.addView(status, matchWrap(top = dp(10)))
        content.addView(input, matchWrap(top = dp(8)))
        content.addView(buttonRow(context, send, close), matchWrap(top = dp(8)))
        content.addView(buttonRow(context, openChat, configure), matchWrap(top = dp(4)))
        return AssistantViews(
            root = ScrollView(context).apply {
                isFillViewport = true
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            surface = surface,
            identity = identity,
            latestUser = latestUser,
            latestAssistant = latestAssistant,
            status = status,
            input = input,
            send = send,
            close = close,
            openChat = openChat,
            configure = configure,
        )
    }

    private fun messageView(context: Context, color: Int): TextView = TextView(context).apply {
        textSize = 15f
        setTextColor(color)
        setTextIsSelectable(true)
    }

    private fun buttonRow(context: Context, first: Button, second: Button): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = top }

    private data class AssistantViews(
        val root: View,
        val surface: TextView,
        val identity: TextView,
        val latestUser: TextView,
        val latestAssistant: TextView,
        val status: TextView,
        val input: EditText,
        val send: Button,
        val close: Button,
        val openChat: Button,
        val configure: Button,
    )

    private data class SessionEntry(
        val views: AssistantViews,
        var controller: SystemAssistantSessionController? = null,
        var scope: CoroutineScope? = null,
        var submitJob: Job? = null,
        var lockReceiver: BroadcastReceiver? = null,
        var lastState: SystemAssistantUiState? = null,
        var deviceLocked: Boolean = true,
        var hostKind: SystemAssistantHostKind = SystemAssistantHostKind.VOICE_SESSION,
    ) {
        fun closeBinding(context: Context) {
            lockReceiver?.let { receiver ->
                runCatching { context.unregisterReceiver(receiver) }
            }
            lockReceiver = null
            lastState = null
            submitJob?.cancel()
            submitJob = null
            scope?.cancel()
            scope = null
            controller?.close()
            controller = null
        }
    }
}

internal fun shouldTerminateSystemAssistantInvocation(action: String?): Boolean =
    action == Intent.ACTION_SCREEN_OFF
