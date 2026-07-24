package me.rerere.rikkahub.quickcapture

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_QUICK_CAPTURE_PROMPT: String =
    "分析当前屏幕，识别我正在处理的问题，并给出简洁、可执行的解决办法；信息不足时明确指出缺少什么。"

const val QUICK_CAPTURE_MAX_IMAGES: Int = 8
const val QUICK_CAPTURE_MAX_TOTAL_BYTES: Long = 32L * 1024L * 1024L
const val QUICK_CAPTURE_BATCH_IDLE_TIMEOUT_MS: Long = 5L * 60L * 1_000L
const val QUICK_CAPTURE_CROP_TIMEOUT_MS: Long = 60L * 1_000L

@Serializable
enum class QuickCaptureTargetMode {
    FOLLOW_SYSTEM_ASSISTANT,
    FIXED_ASSISTANT,
}

@Serializable
enum class QuickCaptureBackendPreference {
    AUTO,
    ACCESSIBILITY,
    MEDIA_PROJECTION,
}

@Serializable
enum class QuickCaptureAreaMode {
    FULL_SCREEN,
    SELECT_REGION,
}

@Serializable
enum class QuickCaptureBubbleEdge {
    LEFT,
    RIGHT,
}

@Serializable
data class QuickCaptureSettings(
    val enabled: Boolean = false,
    val targetMode: QuickCaptureTargetMode = QuickCaptureTargetMode.FOLLOW_SYSTEM_ASSISTANT,
    val fixedAssistantId: Uuid? = null,
    val prompt: String = DEFAULT_QUICK_CAPTURE_PROMPT,
    val autoSend: Boolean = true,
    val backend: QuickCaptureBackendPreference = QuickCaptureBackendPreference.AUTO,
    val areaMode: QuickCaptureAreaMode = QuickCaptureAreaMode.FULL_SCREEN,
    val bubbleSizeDp: Int = 56,
    val bubbleOpacity: Float = 0.9f,
    val bubbleEdge: QuickCaptureBubbleEdge = QuickCaptureBubbleEdge.RIGHT,
    val bubbleYFraction: Float = 0.5f,
) {
    fun normalized(): QuickCaptureSettings = copy(
        prompt = prompt.take(MAX_PROMPT_CHARS),
        bubbleSizeDp = bubbleSizeDp.coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP),
        bubbleOpacity = bubbleOpacity.coerceIn(MIN_BUBBLE_OPACITY, 1f),
        bubbleYFraction = bubbleYFraction.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_BUBBLE_SIZE_DP: Int = 40
        const val MAX_BUBBLE_SIZE_DP: Int = 80
        const val MIN_BUBBLE_OPACITY: Float = 0.35f
        const val MAX_PROMPT_CHARS: Int = 4_096
    }
}

enum class QuickCaptureTargetSource {
    TEMPORARY,
    FIXED,
    SYSTEM_ASSISTANT,
}

data class QuickCaptureTarget(
    val assistantId: Uuid,
    val assistantName: String,
    val conversationId: Uuid,
    val conversationTitle: String,
    val ownerDisplayName: String,
    val source: QuickCaptureTargetSource,
)

enum class QuickCaptureStage {
    IDLE,
    VALIDATING_TARGET,
    HIDING_OVERLAY,
    CAPTURING,
    SELECTING_REGION,
    PERSISTING,
    COLLECTING,
    SUBMITTING,
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
}

data class QuickCaptureAttachment(
    val managedFileId: Long,
    val uri: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val capturedAtMs: Long,
)

data class QuickCaptureUiState(
    val stage: QuickCaptureStage = QuickCaptureStage.IDLE,
    val captureSessionId: Uuid? = null,
    val commandId: Uuid? = null,
    val target: QuickCaptureTarget? = null,
    val attachments: List<QuickCaptureAttachment> = emptyList(),
    val answerPreview: String? = null,
    val errorCode: String? = null,
) {
    val totalBytes: Long get() = attachments.sumOf { it.sizeBytes }
    val isBatch: Boolean get() = stage == QuickCaptureStage.COLLECTING
}
