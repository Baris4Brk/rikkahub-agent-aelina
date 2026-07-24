package me.rerere.rikkahub.quickcapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.Settings
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Touch-enabled owner-visible QuickCapture bubble. This is deliberately independent from
 * AgentOverlay, which is an informational non-touchable generation indicator.
 */
class QuickCaptureOverlayService : Service(), QuickCaptureOverlayHost {
    private val coordinator: QuickCaptureCoordinator by inject()
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var overlayToken: QuickCaptureOverlayToken
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var preview: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var selectionLayer: View? = null
    private var attached = false
    private var previewExpanded = false
    private var stateJob: Job? = null
    private var settingsJob: Job? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var moved = false
    private var longPressed = false
    private var lastTapAt = 0L
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val longPressRunnable = Runnable {
        longPressed = true
        coordinator.onLongPress()
    }
    private val singleTapRunnable = Runnable { coordinator.onSingleTap() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        overlayToken = QuickCaptureInvocationRegistry.registerOverlay()
        createChannel()
        startForegroundCompat(getString(R.string.quick_capture_notification_starting))
        stateJob = serviceScope.launch {
            coordinator.state.collectLatest(::render)
        }
        settingsJob = serviceScope.launch {
            settingsStore.settingsFlow.collectLatest(::applySettings)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopFromUser(startId)
            ACTION_START, null -> ensureVisible(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        settingsJob?.cancel()
        hideMenu()
        removeSelectionLayer()
        removeRoot()
        coordinator.detachOverlay(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureVisible(startId: Int) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopUnavailable(startId)
            return
        }
        serviceScope.launch {
            when (coordinator.preflightStart()) {
                is QuickCaptureStartEligibility.Ready -> withContext(Dispatchers.Main.immediate) {
                    if (root == null) addRoot()
                    if (root == null) {
                        // WindowManager can still reject an overlay after the permission check
                        // (for example when the user revokes it mid-start). Never keep a hidden
                        // foreground service or authorize a non-visible bubble in that case.
                        stopUnavailable(startId)
                        return@withContext
                    }
                    if (!attached) {
                        coordinator.attachOverlay(this@QuickCaptureOverlayService, overlayToken)
                        attached = true
                    }
                    root?.visibility = View.VISIBLE
                    startForegroundCompat(getString(R.string.quick_capture_notification_running))
                }
                is QuickCaptureStartEligibility.Blocked -> stopUnavailable(startId)
            }
        }
    }

    private fun stopFromUser(startId: Int) {
        serviceScope.launch {
            coordinator.stopAndDiscardUnsubmitted()
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private fun stopUnavailable(startId: Int) {
        hideMenu()
        removeRoot()
        coordinator.detachOverlay(this)
        attached = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun addRoot() {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.quick_capture_bubble_description)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xFF334155.toInt())
            }
            setOnTouchListener(::onBubbleTouch)
        }
        val stateLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            text = "AI"
        }
        val answerPreview = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 2
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        bubble.addView(
            stateLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        bubble.addView(
            answerPreview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root = bubble
        label = stateLabel
        preview = answerPreview
        val current = settingsStore.settingsFlow.value.quickCaptureSettings.normalized()
        val size = dp(current.bubbleSizeDp)
        params = overlayParams(size, current)
        runCatching { windowManager.addView(bubble, params) }.onFailure {
            root = null
            label = null
            preview = null
            params = null
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        val layout = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                initialX = layout.x
                initialY = layout.y
                moved = false
                longPressed = false
                view.removeCallbacks(longPressRunnable)
                view.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    moved = true
                    view.removeCallbacks(longPressRunnable)
                }
                if (moved) {
                    layout.x = (initialX + dx).roundToInt().coerceIn(0, screenWidth() - layout.width)
                    layout.y = (initialY + dy).roundToInt().coerceIn(0, screenHeight() - layout.height)
                    runCatching { windowManager.updateViewLayout(view, layout) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                if (moved) {
                    persistSnappedPosition(layout)
                    return true
                }
                if (longPressed) return true
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) return true
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTapAt <= ViewConfiguration.getDoubleTapTimeout()) {
                    view.removeCallbacks(singleTapRunnable)
                    lastTapAt = 0L
                    if (coordinator.state.value.stage == QuickCaptureStage.COLLECTING) {
                        coordinator.onDoubleTap()
                    } else {
                        showMenu()
                    }
                } else {
                    lastTapAt = now
                    view.postDelayed(singleTapRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
                }
                return true
            }
        }
        return false
    }

    private fun persistSnappedPosition(layout: WindowManager.LayoutParams) {
        val isLeft = layout.x + layout.width / 2 < screenWidth() / 2
        layout.x = if (isLeft) 0 else (screenWidth() - layout.width).coerceAtLeast(0)
        layout.y = layout.y.coerceIn(0, (screenHeight() - layout.height).coerceAtLeast(0))
        root?.let { runCatching { windowManager.updateViewLayout(it, layout) } }
        val fraction = if (screenHeight() <= layout.height) 0f else {
            layout.y.toFloat() / (screenHeight() - layout.height).toFloat()
        }
        serviceScope.launch {
            coordinator.updateBubblePosition(
                if (isLeft) QuickCaptureBubbleEdge.LEFT else QuickCaptureBubbleEdge.RIGHT,
                fraction,
            )
        }
    }

    private fun showMenu() {
        hideMenu()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF2222939.toInt())
            }
        }
        fun item(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }
        layout.addView(item(getString(R.string.quick_capture_menu_assistant)) {
            hideMenu()
            showTemporaryAssistantMenu()
        })
        layout.addView(item(getString(R.string.quick_capture_menu_settings)) {
            hideMenu()
            coordinator.openSettings()
        })
        layout.addView(item(getString(R.string.quick_capture_menu_stop)) {
            hideMenu()
            stopFromUser(0)
        })
        val bubbleParams = params ?: return
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x.coerceIn(0, screenWidth() - dp(160))
            y = (bubbleParams.y - dp(180)).coerceAtLeast(0)
        }
        runCatching {
            windowManager.addView(layout, menuParams)
            menu = layout
        }
    }

    private fun showTemporaryAssistantMenu() {
        serviceScope.launch {
            val candidates = coordinator.availableTemporaryAssistants()
            withContext(Dispatchers.Main.immediate) {
                hideMenu()
                val layout = LinearLayout(this@QuickCaptureOverlayService).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(0xF2222939.toInt())
                    }
                }
                if (candidates.isEmpty()) {
                    layout.addView(TextView(this@QuickCaptureOverlayService).apply {
                        text = getString(R.string.quick_capture_no_second_user_assistant)
                        setTextColor(Color.WHITE)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                    })
                } else {
                    candidates.forEach { (id, name) ->
                        layout.addView(Button(this@QuickCaptureOverlayService).apply {
                            text = name
                            setOnClickListener {
                                coordinator.setTemporaryAssistant(id)
                                hideMenu()
                            }
                        })
                    }
                }
                val bubbleParams = params ?: return@withContext
                val menuParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = bubbleParams.x.coerceIn(0, screenWidth() - dp(180))
                    y = (bubbleParams.y - dp(240)).coerceAtLeast(0)
                }
                runCatching {
                    windowManager.addView(layout, menuParams)
                    menu = layout
                }
            }
        }
    }

    private fun hideMenu() {
        menu?.let { runCatching { windowManager.removeViewImmediate(it) } }
        menu = null
    }

    private fun removeRoot() {
        root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        root = null
        label = null
        preview = null
        params = null
        previewExpanded = false
    }

    override suspend fun hideForCapture() = withContext(Dispatchers.Main.immediate) {
        hideMenu()
        root?.visibility = View.INVISIBLE
        root?.awaitFrame()
        root?.awaitFrame()
        Unit
    }

    override suspend fun restoreAfterCapture() = withContext(Dispatchers.Main.immediate) {
        root?.visibility = View.VISIBLE
    }

    override suspend fun selectRegion(source: Bitmap): Bitmap? = withContext(Dispatchers.Main.immediate) {
        root?.visibility = View.INVISIBLE
        withTimeoutOrNull<Bitmap?>(QUICK_CAPTURE_CROP_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val selector = QuickCaptureRegionSelectionView(this@QuickCaptureOverlayService)
                val container = FrameLayout(this@QuickCaptureOverlayService).apply {
                    // The selector must cover exactly the source-screen coordinate space. Keeping
                    // the confirmation row as a child overlay (rather than consuming layout
                    // height) avoids vertically stretching the crop rectangle.
                    addView(selector, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ))
                    val buttons = LinearLayout(this@QuickCaptureOverlayService).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(8), dp(8), dp(24))
                    }
                    buttons.addView(Button(this@QuickCaptureOverlayService).apply {
                        text = getString(android.R.string.cancel)
                    }.also { button ->
                        button.setOnClickListener { finishRegionSelection(completed, continuation, null, source) }
                    })
                    buttons.addView(Button(this@QuickCaptureOverlayService).apply {
                        text = getString(android.R.string.ok)
                    }.also { button ->
                        button.setOnClickListener {
                            val crop = crop(source, selector)
                            finishRegionSelection(completed, continuation, crop, source)
                        }
                    })
                    addView(
                        buttons,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM,
                        ),
                    )
                }
                val selectionParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
                runCatching {
                    windowManager.addView(container, selectionParams)
                    selectionLayer = container
                }.onFailure {
                    finishRegionSelection(completed, continuation, null, source)
                }
                continuation.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) {
                        removeSelectionLayer()
                        recycle(source)
                    }
                }
            }
        }.also {
            root?.visibility = View.VISIBLE
        }
    }

    private fun finishRegionSelection(
        completed: AtomicBoolean,
        continuation: kotlinx.coroutines.CancellableContinuation<Bitmap?>,
        result: Bitmap?,
        source: Bitmap,
    ) {
        if (!completed.compareAndSet(false, true)) {
            recycle(result)
            return
        }
        removeSelectionLayer()
        recycle(source)
        if (continuation.isActive) continuation.resume(result)
        else recycle(result)
    }

    private fun crop(source: Bitmap, selector: QuickCaptureRegionSelectionView): Bitmap? = runCatching {
        val selection = selector.selection() ?: return@runCatching null
        val width = selector.width.coerceAtLeast(1)
        val height = selector.height.coerceAtLeast(1)
        val left = (selection.left * source.width / width).roundToInt().coerceIn(0, source.width - 1)
        val top = (selection.top * source.height / height).roundToInt().coerceIn(0, source.height - 1)
        val right = (selection.right * source.width / width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (selection.bottom * source.height / height).roundToInt().coerceIn(top + 1, source.height)
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }.getOrNull()

    private fun removeSelectionLayer() {
        selectionLayer?.let { runCatching { windowManager.removeViewImmediate(it) } }
        selectionLayer = null
    }

    private fun applySettings(settings: Settings) {
        val quick = settings.quickCaptureSettings.normalized()
        if (!quick.enabled && root != null) {
            stopFromUser(0)
            return
        }
        val view = root ?: return
        applyBubbleLayout(quick, view)
    }

    private fun render(state: QuickCaptureUiState) {
        label?.text = when (state.stage) {
            QuickCaptureStage.IDLE -> getString(R.string.quick_capture_bubble_idle)
            QuickCaptureStage.VALIDATING_TARGET -> getString(R.string.quick_capture_bubble_validating)
            QuickCaptureStage.HIDING_OVERLAY,
            QuickCaptureStage.CAPTURING,
            QuickCaptureStage.SELECTING_REGION,
            QuickCaptureStage.PERSISTING,
            -> getString(R.string.quick_capture_bubble_capturing)
            QuickCaptureStage.COLLECTING -> getString(
                R.string.quick_capture_bubble_collecting,
                state.attachments.size,
                QUICK_CAPTURE_MAX_IMAGES,
            )
            QuickCaptureStage.SUBMITTING -> getString(R.string.quick_capture_bubble_submitting)
            QuickCaptureStage.QUEUED -> getString(R.string.quick_capture_bubble_queued)
            QuickCaptureStage.RUNNING -> getString(R.string.quick_capture_bubble_running)
            QuickCaptureStage.WAITING_APPROVAL -> getString(R.string.quick_capture_bubble_waiting_approval)
            QuickCaptureStage.COMPLETED -> getString(R.string.quick_capture_bubble_completed)
            QuickCaptureStage.FAILED -> getString(R.string.quick_capture_bubble_failed)
        }
        preview?.let { answer ->
            val text = state.answerPreview
            answer.text = text.orEmpty()
            answer.visibility = if (state.stage == QuickCaptureStage.COMPLETED && !text.isNullOrBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        updatePreviewExpansion(state)
    }

    private fun updatePreviewExpansion(state: QuickCaptureUiState) {
        val expanded = state.stage == QuickCaptureStage.COMPLETED && !state.answerPreview.isNullOrBlank()
        if (previewExpanded == expanded) return
        previewExpanded = expanded
        root?.let { view ->
            applyBubbleLayout(settingsStore.settingsFlow.value.quickCaptureSettings.normalized(), view)
        }
    }

    private fun applyBubbleLayout(settings: QuickCaptureSettings, view: View) {
        val layout = params ?: return
        val baseSize = dp(settings.bubbleSizeDp)
        val width = if (previewExpanded) {
            min(screenWidth(), max(baseSize, dp(ANSWER_PREVIEW_WIDTH_DP)))
        } else {
            baseSize
        }
        val height = if (previewExpanded) {
            min(screenHeight(), baseSize + dp(ANSWER_PREVIEW_EXTRA_HEIGHT_DP))
        } else {
            baseSize
        }
        layout.width = width
        layout.height = height
        val maxY = (screenHeight() - height).coerceAtLeast(0)
        layout.x = if (settings.bubbleEdge == QuickCaptureBubbleEdge.LEFT) 0 else {
            (screenWidth() - width).coerceAtLeast(0)
        }
        layout.y = (maxY * settings.bubbleYFraction).roundToInt().coerceIn(0, maxY)
        view.alpha = settings.bubbleOpacity
        runCatching { windowManager.updateViewLayout(view, layout) }
    }

    private fun overlayParams(size: Int, settings: QuickCaptureSettings): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.bubbleEdge == QuickCaptureBubbleEdge.LEFT) 0 else (screenWidth() - size).coerceAtLeast(0)
            y = ((screenHeight() - size).coerceAtLeast(0) * settings.bubbleYFraction).roundToInt()
            alpha = settings.bubbleOpacity
        }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun screenWidth(): Int = screenBounds().width().coerceAtLeast(1)

    private fun screenHeight(): Int = screenBounds().height().coerceAtLeast(1)

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION") Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.quick_capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    private fun startForegroundCompat(content: String) {
        val notification = notification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.quick_capture_notification_title))
        .setContentText(content)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RouteActivity::class.java).apply {
                    putExtra(RouteActivity.EXTRA_OPEN_QUICK_CAPTURE_SETTINGS, true)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            getString(R.string.quick_capture_menu_stop),
            PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private suspend fun View.awaitFrame() = suspendCancellableCoroutine { continuation ->
        postOnAnimation {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) runCatching { bitmap.recycle() }
    }

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.QUICK_CAPTURE_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.QUICK_CAPTURE_STOP"
        private const val CHANNEL_ID = "quick_capture"
        private const val NOTIFICATION_ID = 2410
        private const val ANSWER_PREVIEW_WIDTH_DP = 240
        private const val ANSWER_PREVIEW_EXTRA_HEIGHT_DP = 64

        fun startIntent(context: Context): Intent = Intent(context, QuickCaptureOverlayService::class.java)
            .setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, QuickCaptureOverlayService::class.java)
            .setAction(ACTION_STOP)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, startIntent(context))
        }
    }
}
