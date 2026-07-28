package me.rerere.rikkahub.pet.overlay

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.assistant.SecondUserPresentationSource
import me.rerere.rikkahub.assistant.SecondUserTarget
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.pet.PetAction
import me.rerere.rikkahub.pet.PetPresentationMapper
import me.rerere.rikkahub.pet.PetDialogueGenerator
import me.rerere.rikkahub.pet.PetDialogueInputKind
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetDialogueTurnDraft
import me.rerere.rikkahub.pet.PetDialogueTurnEntityView
import me.rerere.rikkahub.pet.PetGenerationResult
import me.rerere.rikkahub.pet.PetHandoffCoordinator
import me.rerere.rikkahub.pet.PetHandoffMode
import me.rerere.rikkahub.pet.PetInteractionPayload
import me.rerere.rikkahub.pet.PetPersonaSource
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_HEIGHT
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_WIDTH
import me.rerere.rikkahub.pet.render.CodexPetAtlas
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.PetInteractionSlotResult
import org.koin.android.ext.android.inject

class DesktopPetService : Service() {
    private val settingsStore: SettingsStore by inject()
    private val presentationSource: SecondUserPresentationSource by inject()
    private val dialogueRepository: PetDialogueRepository by inject()
    private val dialogueGenerator: PetDialogueGenerator by inject()
    private val personaSource: PetPersonaSource by inject()
    private val handoffCoordinator: PetHandoffCoordinator by inject()
    private val chatService: ChatService by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var windowManager: WindowManager
    private var spriteView: PetSpriteView? = null
    private var placeholderView: View? = null
    private var bubbleView: TextView? = null
    private var spriteParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var atlas: CodexPetAtlas? = null
    private var loadedRenderConfig: PetRenderConfig? = null
    private var currentAction = PetAction.IDLE
    private var currentStatusBubble: String? = null
    private var sidecarAllowed = false
    private var configuredAssistant: me.rerere.rikkahub.data.model.Assistant? = null
    private var interactionJob: Job? = null
    private var lastModelInteractionAtMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshVisibility()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle("桌宠正在运行")
                .setContentText("与第二用户绑定的本地桌宠")
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { observeConfiguredPet() }
        scope.launch { TrustedApprovalSurfaceVisibility.visible.collect { refreshVisibility() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        removeWindows()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun observeConfiguredPet() {
        settingsStore.settingsFlow.collectLatest { settings ->
            if (settings.init) return@collectLatest
            val assistant = settings.assistants.firstOrNull {
                it.petEnabled && it.privilegedConversationId != null
            }
            if (assistant == null) {
                removeWindows()
                // The settings screen persists asynchronously. On a first enable,
                // collectLatest will cancel this grace delay as soon as the enabled
                // assistant reaches SettingsStore.
                if (configuredAssistant == null) {
                    Log.i(TAG, "Waiting for enabled pet configuration")
                    delay(STARTUP_CONFIGURATION_GRACE_MS)
                }
                configuredAssistant = null
                stopSelf()
                return@collectLatest
            }
            Log.i(TAG, "Loaded enabled pet configuration")
            configuredAssistant = assistant
            loadConfiguredPackage(assistant)
            val target = SecondUserTarget(assistant.id, checkNotNull(assistant.privilegedConversationId))
            presentationSource.observe(target).collect { state ->
                currentAction = PetPresentationMapper.action(state.status)
                sidecarAllowed = state.status in setOf(
                    me.rerere.rikkahub.assistant.SecondUserPresentationStatus.IDLE,
                    me.rerere.rikkahub.assistant.SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING,
                )
                spriteView?.setAction(currentAction)
                currentStatusBubble = PetPresentationMapper.bubble(state.status)
                renderBubble(currentStatusBubble)
                refreshVisibility()
            }
        }
    }

    private fun loadConfiguredPackage(assistant: me.rerere.rikkahub.data.model.Assistant) {
        val config = PetRenderConfig(
            packageId = assistant.petPackageId,
            scale = assistant.petScale.coerceIn(MIN_PET_SCALE, MAX_PET_SCALE),
            animationFps = assistant.petAnimationFps.coerceIn(MIN_ANIMATION_FPS, MAX_ANIMATION_FPS),
        )
        if (config == loadedRenderConfig && (spriteView != null || placeholderView != null)) return
        removeWindows()
        loadedRenderConfig = config
        if (!Settings.canDrawOverlays(this)) return
        val packageDir = config.packageId?.let { File(filesDir, "pets/$it") }
        val loaded = runCatching {
            val manifestFile = File(checkNotNull(packageDir), "pet.json")
            val manifest = json.decodeFromString<CodexPetManifest>(manifestFile.readText())
            CodexPetAtlas.decode(File(packageDir, manifest.resolvedSpritesheetPath), manifest.resolvedVersion)
        }.getOrNull()
        if (loaded == null) showPlaceholder() else {
            atlas = loaded
            showSprite(loaded, config)
        }
        refreshVisibility()
    }

    private fun showSprite(atlas: CodexPetAtlas, config: PetRenderConfig) {
        val requestedWidth = dp((CODEX_FRAME_WIDTH * config.scale).roundToInt()).coerceAtLeast(1)
        val requestedHeight = dp((CODEX_FRAME_HEIGHT * config.scale).roundToInt()).coerceAtLeast(1)
        val fit = minOf(
            1f,
            resources.displayMetrics.widthPixels * 0.95f / requestedWidth,
            resources.displayMetrics.heightPixels * 0.80f / requestedHeight,
        )
        val sizeWidth = (requestedWidth * fit).roundToInt().coerceAtLeast(1)
        val sizeHeight = (requestedHeight * fit).roundToInt().coerceAtLeast(1)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val params = baseParams(sizeWidth, sizeHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, resources.displayMetrics.widthPixels - sizeWidth)
            y = prefs.getInt(KEY_Y, resources.displayMetrics.heightPixels - sizeHeight - dp(96))
        }
        val view = PetSpriteView(
            context = this,
            atlas = atlas,
            onInteraction = { gesture, region ->
                spriteView?.showLocalFeedback()
                if (sidecarAllowed) {
                    spriteView?.setAction(
                        when (region) {
                            me.rerere.rikkahub.pet.PetBodyRegion.HEAD -> PetAction.WAVING
                            me.rerere.rikkahub.pet.PetBodyRegion.BODY -> PetAction.JUMPING
                            else -> PetAction.IDLE
                        },
                    )
                    submitTouchInteraction(gesture, region)
                }
            },
            onDrag = { dx, dy, finished ->
                params.x = (params.x + dx).coerceIn(0, (resources.displayMetrics.widthPixels - sizeWidth).coerceAtLeast(0))
                params.y = (params.y + dy).coerceIn(0, (resources.displayMetrics.heightPixels - sizeHeight).coerceAtLeast(0))
                spriteView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                updateBubblePosition(params)
                if (finished) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
            },
            headBoundary = configuredAssistant?.petHeadBoundary ?: 0.34f,
            bodyBoundary = configuredAssistant?.petBodyBoundary ?: 0.76f,
            animationFps = config.animationFps,
        ).apply { setAction(currentAction) }
        windowManager.addView(view, params)
        spriteView = view
        spriteParams = params
        view.resumeAnimation()
    }

    private fun showPlaceholder() {
        val image = ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            alpha = 0.55f
            isClickable = false
        }
        val params = baseParams(dp(64), dp(64)).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(96)
        }
        windowManager.addView(image, params)
        placeholderView = image
    }

    private fun renderBubble(text: String?) {
        if (text.isNullOrBlank()) {
            bubbleView?.let { runCatching { windowManager.removeViewImmediate(it) } }
            bubbleView = null
            bubbleParams = null
            return
        }
        bubbleView?.let { it.text = text; return }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(0xDD202020.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { openApp() }
        }
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        spriteParams?.let { sprite ->
            params.gravity = Gravity.TOP or Gravity.START
            params.x = sprite.x
            params.y = (sprite.y - dp(48)).coerceAtLeast(0)
        }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
    }

    private fun submitTouchInteraction(
        gesture: String,
        region: me.rerere.rikkahub.pet.PetBodyRegion,
    ) {
        val assistant = configuredAssistant ?: return
        val conversationId = assistant.privilegedConversationId ?: return
        val now = System.currentTimeMillis()
        if (!sidecarAllowed || interactionJob?.isActive == true ||
            now - lastModelInteractionAtMs < MODEL_TOUCH_COOLDOWN_MS
        ) return
        lastModelInteractionAtMs = now
        interactionJob = scope.launch {
            val payload = PetInteractionPayload(type = gesture, region = region)
            val interactionJson = json.encodeToString(payload)
            var bubbleText: String? = null
            var autoHandoffId: String? = null
            val slot = chatService.runPetInteraction(conversationId) {
                val current = dialogueRepository.observeActive(
                    assistant.id.toString(),
                    conversationId.toString(),
                ).first()
                val history = current?.turns.orEmpty().map { turn ->
                    PetDialogueTurnEntityView(
                        userInput = turn.userText ?: turn.interactionJson.orEmpty(),
                        assistantText = turn.assistantText,
                    )
                }
                val mode = runCatching { PetHandoffMode.valueOf(assistant.petHandoffMode) }
                    .getOrDefault(PetHandoffMode.CONFIRM)
                val persona = personaSource.observe(assistant.id).first()
                when (val generated = dialogueGenerator.generate(
                    persona = persona,
                    history = history,
                    input = "用户对桌宠的 ${region.name.lowercase()} 区域做了 $gesture 互动。",
                    handoffMode = mode,
                )) {
                    is PetGenerationResult.Success -> {
                        val updated = dialogueRepository.append(
                            assistant.id.toString(),
                            conversationId.toString(),
                            PetDialogueTurnDraft(
                                inputKind = PetDialogueInputKind.TOUCH,
                                interactionJson = interactionJson,
                                assistantText = generated.text.ifBlank { null },
                                action = generated.action,
                                handoff = generated.handoff,
                            ),
                        )
                        if (sidecarAllowed) spriteView?.setAction(generated.action)
                        bubbleText = generated.text.ifBlank { null }
                        if (mode == PetHandoffMode.AUTO) {
                            autoHandoffId = updated.turns.lastOrNull()?.handoffRequestId
                        }
                    }
                    PetGenerationResult.LocalAnimationOnly -> dialogueRepository.append(
                        assistant.id.toString(),
                        conversationId.toString(),
                        PetDialogueTurnDraft(
                            inputKind = PetDialogueInputKind.TOUCH,
                            interactionJson = interactionJson,
                        ),
                    )
                    is PetGenerationResult.Failure -> {
                        dialogueRepository.append(
                            assistant.id.toString(),
                            conversationId.toString(),
                            PetDialogueTurnDraft(
                                inputKind = PetDialogueInputKind.TOUCH,
                                interactionJson = interactionJson,
                            ),
                        )
                        bubbleText = me.rerere.rikkahub.pet.petGenerationErrorMessage(generated.code)
                    }
                }
            }
            if (slot is PetInteractionSlotResult.Completed) {
                autoHandoffId?.let { handoffCoordinator.submit(it, automatic = true) }
                if (currentStatusBubble == null && bubbleText != null) {
                    renderBubble(bubbleText)
                    delay(8_000)
                    if (currentStatusBubble == null) renderBubble(null)
                }
            }
        }
    }

    private fun updateBubblePosition(sprite: WindowManager.LayoutParams) {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        params.x = sprite.x
        params.y = (sprite.y - dp(48)).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun refreshVisibility() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val visible = power.isInteractive && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            !TrustedApprovalSurfaceVisibility.visible.value
        val visibility = if (visible) View.VISIBLE else View.GONE
        spriteView?.visibility = visibility
        placeholderView?.visibility = visibility
        bubbleView?.visibility = visibility
        if (visible) spriteView?.resumeAnimation() else spriteView?.pauseAnimation()
    }

    private fun removeWindows() {
        spriteView?.pauseAnimation()
        listOfNotNull<View>(spriteView, placeholderView, bubbleView).forEach { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        spriteView = null
        placeholderView = null
        bubbleView = null
        spriteParams = null
        bubbleParams = null
        atlas?.close()
        atlas = null
        interactionJob?.cancel()
        interactionJob = null
    }

    private fun baseParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }?.let(::startActivity)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "me.rerere.rikkahub.pet.STOP"
        private const val TAG = "DesktopPetService"
        private const val CHANNEL_ID = "desktop_pet"
        private const val NOTIFICATION_ID = 7301
        private const val PREFS = "desktop_pet_position"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val MODEL_TOUCH_COOLDOWN_MS = 15_000L
        private const val STARTUP_CONFIGURATION_GRACE_MS = 3_000L
        private const val MIN_PET_SCALE = 0.05f
        private const val MAX_PET_SCALE = 2.0f
        private const val MIN_ANIMATION_FPS = 4
        private const val MAX_ANIMATION_FPS = 12

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DesktopPetService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DesktopPetService::class.java).setAction(ACTION_STOP))
        }
    }
}

private data class PetRenderConfig(
    val packageId: String?,
    val scale: Float,
    val animationFps: Int,
)
