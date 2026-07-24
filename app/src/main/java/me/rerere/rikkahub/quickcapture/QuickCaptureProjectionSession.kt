package me.rerere.rikkahub.quickcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface QuickCaptureProjectionState {
    data object NeedsConsent : QuickCaptureProjectionState
    data class Ready(val width: Int, val height: Int) : QuickCaptureProjectionState
    data class Failed(val detail: String) : QuickCaptureProjectionState
}

/**
 * Process-local MediaProjection session. The service owns the platform token; this object only
 * exposes on-demand bitmaps in-process so no large PNG crosses a Binder boundary.
 */
object QuickCaptureProjectionSession {
    private const val IMAGE_TIMEOUT_MS = 5_000L
    private val lock = Mutex()
    private val captureLock = Mutex()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbackThread = HandlerThread("QuickCaptureProjection").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private var runtime: Runtime? = null
    private val _state = MutableStateFlow<QuickCaptureProjectionState>(QuickCaptureProjectionState.NeedsConsent)
    val state = _state.asStateFlow()

    suspend fun install(context: Context, projection: MediaProjection): Result<Unit> = runCatching {
        lock.withLock {
            closeLocked(stopProjection = true)
            val size = displaySize(context)
            val reader = newReader(size.width, size.height)
            val callback = projectionCallback()
            try {
                projection.registerCallback(callback, callbackHandler)
                val display = projection.createVirtualDisplay(
                    "RikkaHubQuickCapture",
                    size.width,
                    size.height,
                    size.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    callbackHandler,
                )
                val virtualDisplay = display
                    ?: error("MediaProjection did not create a VirtualDisplay")
                runtime = Runtime(context.applicationContext, projection, virtualDisplay, reader, callback, size)
                _state.value = QuickCaptureProjectionState.Ready(size.width, size.height)
            } catch (failure: Throwable) {
                // A consent token may only create one VirtualDisplay. If creation fails, release
                // every partial resource and force the user through a fresh consent request.
                runCatching { projection.unregisterCallback(callback) }
                runCatching { reader.close() }
                runCatching { projection.stop() }
                throw failure
            }
        }
    }.onFailure { _state.value = QuickCaptureProjectionState.Failed(it.readableProjectionMessage()) }

    suspend fun capture(displayId: Int): ScreenCaptureResult = captureLock.withLock {
        if (displayId != ScreenCaptureManager.DEFAULT_DISPLAY_ID) {
            return ScreenCaptureResult.Failure(CaptureFailureCode.INVALID_DISPLAY)
        }
        val preparationFailure = lock.withLock {
            val active = runtime
                ?: return@withLock CaptureFailureCode.MEDIA_PROJECTION_NOT_AUTHORIZED
            val currentSize = runCatching { displaySize(active.context) }.getOrElse { failure ->
                closeLocked(stopProjection = true)
                _state.value = QuickCaptureProjectionState.Failed(failure.readableProjectionMessage())
                return@withLock CaptureFailureCode.INTERNAL_ERROR
            }
            if (resizeLocked(currentSize.width, currentSize.height, currentSize.densityDpi)) {
                null
            } else {
                CaptureFailureCode.INTERNAL_ERROR
            }
        }
        if (preparationFailure != null) {
            return ScreenCaptureResult.Failure(preparationFailure)
        }
        val result = withTimeoutOrNull<ProjectionRead>(IMAGE_TIMEOUT_MS) {
            while (true) {
                // Read the current reader every turn. Rotation replaces only the reader/surface
                // on the same VirtualDisplay, so an in-flight capture must not keep polling the
                // closed pre-rotation reader until timeout.
                val reader = lock.withLock { runtime?.reader }
                    ?: return@withTimeoutOrNull ProjectionRead.SessionStopped
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image != null) {
                    val bitmap = runCatching { image.toOwnedBitmap() }
                        .getOrElse { return@withTimeoutOrNull ProjectionRead.DecodeFailed }
                    return@withTimeoutOrNull ProjectionRead.BitmapReady(bitmap)
                }
                delay(32)
            }
            error("Unreachable while waiting for a projection image")
        }
        when (result) {
            is ProjectionRead.BitmapReady -> ScreenCaptureResult.Success(
                ManagedScreenCapture(
                    bitmap = result.bitmap,
                    backend = ScreenCaptureBackendKind.MEDIA_PROJECTION,
                    displayId = displayId,
                ),
            )
            ProjectionRead.DecodeFailed -> ScreenCaptureResult.Failure(CaptureFailureCode.BITMAP_DECODE_FAILED)
            ProjectionRead.SessionStopped ->
                ScreenCaptureResult.Failure(CaptureFailureCode.MEDIA_PROJECTION_NOT_AUTHORIZED)
            null -> ScreenCaptureResult.Failure(CaptureFailureCode.TIMED_OUT)
        }
    }

    suspend fun stop() {
        lock.withLock { closeLocked(stopProjection = true) }
    }

    private fun projectionCallback(): MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The platform may call this from its own thread. Avoid re-calling MediaProjection.stop().
            sessionScope.launch {
                lock.withLock { closeLocked(stopProjection = false) }
            }
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            sessionScope.launch {
                lock.withLock {
                    val active = runtime ?: return@withLock
                    resizeLocked(width, height, active.size.densityDpi)
                }
            }
        }
    }

    private fun closeLocked(stopProjection: Boolean) {
        val active = runtime ?: run {
            _state.value = QuickCaptureProjectionState.NeedsConsent
            return
        }
        runtime = null
        runCatching { active.display.release() }
        runCatching { active.reader.close() }
        runCatching { active.projection.unregisterCallback(active.callback) }
        if (stopProjection) runCatching { active.projection.stop() }
        _state.value = QuickCaptureProjectionState.NeedsConsent
    }

    /** Resizes the existing VirtualDisplay; it never consumes the consent token a second time. */
    private fun resizeLocked(width: Int, height: Int, densityDpi: Int): Boolean {
        val active = runtime ?: return false
        if (active.size.width == width && active.size.height == height) return true
        val replacement = runCatching { newReader(width, height) }.getOrElse { failure ->
            closeLocked(stopProjection = true)
            _state.value = QuickCaptureProjectionState.Failed(failure.readableProjectionMessage())
            return false
        }
        return try {
            active.display.resize(width, height, densityDpi)
            active.display.surface = replacement.surface
            val oldReader = active.reader
            runtime = active.copy(
                reader = replacement,
                size = active.size.copy(width = width, height = height, densityDpi = densityDpi),
            )
            runCatching { oldReader.close() }
            _state.value = QuickCaptureProjectionState.Ready(width, height)
            true
        } catch (failure: Throwable) {
            runCatching { replacement.close() }
            closeLocked(stopProjection = true)
            _state.value = QuickCaptureProjectionState.Failed(failure.readableProjectionMessage())
            false
        }
    }

    private fun newReader(width: Int, height: Int): ImageReader = ImageReader.newInstance(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        PixelFormat.RGBA_8888,
        2,
    )

    private fun displaySize(context: Context): DisplaySize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val bounds = metrics.bounds
            return DisplaySize(bounds.width(), bounds.height(), context.resources.configuration.densityDpi)
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
        }
        return DisplaySize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private data class Runtime(
        val context: Context,
        val projection: MediaProjection,
        val display: VirtualDisplay,
        val reader: ImageReader,
        val callback: MediaProjection.Callback,
        val size: DisplaySize,
    )

    private data class DisplaySize(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private sealed interface ProjectionRead {
        data class BitmapReady(val bitmap: Bitmap) : ProjectionRead

        data object DecodeFailed : ProjectionRead

        data object SessionStopped : ProjectionRead
    }
}

private fun Image.toOwnedBitmap(): Bitmap {
    try {
        val plane = planes.firstOrNull() ?: error("Projection image has no planes")
        val imageWidth = this.width
        val imageHeight = this.height
        val pixelStride = plane.pixelStride
        val paddedWidth = imageWidth + (plane.rowStride - pixelStride * imageWidth) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (paddedWidth == imageWidth) padded else Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight)
            .also { padded.recycle() }
    } finally {
        close()
    }
}

private fun Throwable.readableProjectionMessage(): String = message ?: javaClass.simpleName
