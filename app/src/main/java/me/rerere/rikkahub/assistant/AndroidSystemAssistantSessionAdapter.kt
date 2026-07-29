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
            val commandRunning = state.presentation?.status in setOf(
                SecondUserPresentationStatus.WAITING_APPROVAL,
                SecondUserPresentationStatus.CANCEL_REQUESTED,
                SecondUserPresentationStatus.TERMINATING,
                SecondUserPresentationStatus.TOOL_RUNNING,
                SecondUserPresentationStatus.MODEL_GENERATING,
                SecondUserPresentationStatus.QUEUED,
                SecondUserPresentationStatus.RECOVERING,
            ) || state.runtimeState == RuntimeState.Running ||
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
            views.status.text = statusText(state, deviceLocked = true)
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
        val waitingApproval = state.presentation?.status ==
            SecondUserPresentationStatus.WAITING_APPROVAL
        views.openChat.text = appContext.getString(
            if (waitingApproval) {
                R.string.system_assistant_view_approval
            } else {
                R.string.system_assistant_open_chat
            }
        )
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
        views.input.isEnabled = state.canSubmit && !deviceLocked && !waitingApproval
        views.send.isEnabled = state.canSubmit && !deviceLocked && !waitingApproval &&
            state.target is SystemAssistantTargetUiState.Ready
        views.openChat.isEnabled = state.conversationId != null
        views.configure.isEnabled = true
        views.input.alpha = if (views.input.isEnabled) 1f else 0.6f
        views.send.alpha = if (views.send.isEnabled) 1f else 0.45f
        views.openChat.alpha = if (views.openChat.isEnabled) 1f else 0.45f
    }

    private fun statusText(state: SystemAssistantUiState, deviceLocked: Boolean): String = when {
        deviceLocked && state.presentation?.status != null &&
            state.presentation.status != SecondUserPresentationStatus.IDLE ->
            presentationStatusText(state.presentation.status)
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
        state.presentation != null -> presentationStatusText(state.presentation.status)
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

    private fun presentationStatusText(status: SecondUserPresentationStatus): String =
        appContext.getString(
            when (status) {
                SecondUserPresentationStatus.SAFETY_BLOCKED ->
                    R.string.system_assistant_execution_safety_blocked
                SecondUserPresentationStatus.WAITING_APPROVAL ->
                    R.string.system_assistant_execution_waiting_approval
                SecondUserPresentationStatus.CANCEL_REQUESTED ->
                    R.string.system_assistant_execution_cancel_requested
                SecondUserPresentationStatus.TERMINATING ->
                    R.string.system_assistant_execution_terminating
                SecondUserPresentationStatus.TOOL_RUNNING ->
                    R.string.system_assistant_execution_tool_running
                SecondUserPresentationStatus.MODEL_GENERATING -> R.string.system_assistant_generating
                SecondUserPresentationStatus.QUEUED -> R.string.system_assistant_execution_queued
                SecondUserPresentationStatus.RECOVERING ->
                    R.string.system_assistant_execution_recovering
                SecondUserPresentationStatus.STALE -> R.string.system_assistant_execution_stale
                SecondUserPresentationStatus.FAILED_RECENTLY ->
                    R.string.system_assistant_execution_failed_recently
                SecondUserPresentationStatus.SUCCEEDED_RECENTLY ->
                    R.string.system_assistant_execution_succeeded_recently
                SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING ->
                    R.string.system_assistant_execution_background_service_running
                SecondUserPresentationStatus.IDLE -> R.string.system_assistant_ready
            }
        )

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
            elevation = dp(18).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(context, Color.rgb(22, 24, 30), 28, Color.rgb(57, 61, 73))
        }
        val surface = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(190, 211, 255))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedBackground(context, Color.rgb(42, 53, 78), 14)
        }
        val identity = TextView(context).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(166, 170, 181))
        }
        val latestUser = messageView(context, mine = true)
        val latestAssistant = messageView(context, mine = false)
        latestUser.maxWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt()
        latestAssistant.maxWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt()
        val status = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(194, 199, 211))
            setPadding(dp(2), dp(7), dp(2), dp(3))
        }
        val input = EditText(context).apply {
            hint = context.getString(R.string.system_assistant_overlay_hint)
            minLines = 1
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(SYSTEM_ASSISTANT_MAX_TEXT_LENGTH))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(context, Color.rgb(35, 38, 46), 18, Color.rgb(57, 61, 73))
            setTextColor(Color.rgb(242, 244, 250))
            setHintTextColor(Color.rgb(140, 145, 157))
        }
        val send = modernAction(context, context.getString(R.string.system_assistant_send), primary = true)
        val openChat = modernAction(context, context.getString(R.string.system_assistant_open_chat), primary = false)
        val configure = modernAction(
            context,
            context.getString(R.string.system_assistant_open_configuration),
            primary = false,
        )
        val close = TextView(context).apply {
            text = "×"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.rgb(166, 170, 181))
            background = roundedBackground(context, Color.rgb(35, 38, 46), 18)
            isClickable = true
            isFocusable = true
        }
        val avatar = TextView(context).apply {
            text = "AI"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.rgb(190, 211, 255))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground(context, Color.rgb(42, 53, 78), 19)
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.system_assistant_overlay_title)
            textSize = 17f
            setTextColor(Color.rgb(242, 244, 250))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, matchWrap())
            addView(identity, matchWrap(top = dp(1)))
        }
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
                addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
            },
            matchWrap(),
        )
        content.addView(surface, matchWrap(top = dp(12)))
        content.addView(latestUser, bubbleWrap(mine = true, top = dp(10)))
        content.addView(latestAssistant, bubbleWrap(mine = false, top = dp(7)))
        content.addView(status, matchWrap(top = dp(6)))
        content.addView(input, matchWrap(top = dp(8)))
        content.addView(buttonRow(context, send, openChat), matchWrap(top = dp(10)))
        content.addView(configure, matchWrap(top = dp(7)))
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

    private fun messageView(context: Context, mine: Boolean): TextView = TextView(context).apply {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        textSize = 13.5f
        setTextColor(Color.rgb(242, 244, 250))
        setTextIsSelectable(true)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = roundedBackground(
            context,
            if (mine) Color.rgb(62, 75, 116) else Color.rgb(40, 43, 52),
            17,
        )
    }

    private fun modernAction(context: Context, label: String, primary: Boolean): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.rgb(20, 24, 32) else Color.rgb(242, 244, 250))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(
                context,
                if (primary) Color.rgb(166, 196, 255) else Color.rgb(35, 38, 46),
                16,
                if (primary) null else Color.rgb(57, 61, 73),
            )
            isClickable = true
            isFocusable = true
        }
    }

    private fun roundedBackground(
        context: Context,
        color: Int,
        radiusDp: Int,
        stroke: Int? = null,
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            stroke?.let { setStroke(density.toInt().coerceAtLeast(1), it) }
        }
    }

    private fun buttonRow(context: Context, first: TextView, second: TextView): LinearLayout =
        LinearLayout(context).apply {
            val density = context.resources.displayMetrics.density
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            })
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = top }

    private fun bubbleWrap(mine: Boolean, top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = top
        }

    private data class AssistantViews(
        val root: View,
        val surface: TextView,
        val identity: TextView,
        val latestUser: TextView,
        val latestAssistant: TextView,
        val status: TextView,
        val input: EditText,
        val send: TextView,
        val close: TextView,
        val openChat: TextView,
        val configure: TextView,
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
