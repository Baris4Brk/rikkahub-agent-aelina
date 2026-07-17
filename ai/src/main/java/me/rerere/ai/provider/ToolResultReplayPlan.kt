package me.rerere.ai.provider

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.EncodedImage
import me.rerere.ai.util.encodeBase64

internal class ToolResultReplayPlan private constructor(
    val items: List<ToolResultReplayItem>,
) {
    val hasImages: Boolean = items.any { it is ToolResultReplayItem.Image }

    val text: String = items
        .filterIsInstance<ToolResultReplayItem.Text>()
        .joinToString("\n") { it.value }

    fun asMessageParts(): List<UIMessagePart> = items.map { item ->
        when (item) {
            is ToolResultReplayItem.Text -> UIMessagePart.Text(item.value)
            is ToolResultReplayItem.Image -> UIMessagePart.Image(item.source)
        }
    }

    companion object {
        const val IMAGE_OMITTED_TEXT =
            "[Image omitted because this model does not accept image input.]"
        const val IMAGE_ENCODING_FAILED_TEXT =
            "[Image unavailable because it could not be encoded.]"
        const val MULTIMODAL_RESULT_FOLLOWS_TEXT =
            "[Tool result is provided in the following multimodal input.]"

        fun create(
            output: List<UIMessagePart>,
            inputModalities: Collection<Modality>,
            encodeImage: (UIMessagePart.Image) -> Result<EncodedImage> = { image ->
                image.encodeBase64(withPrefix = true)
            },
        ): ToolResultReplayPlan {
            val acceptsImages = Modality.IMAGE in inputModalities
            val items = buildList {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> add(ToolResultReplayItem.Text(part.text))
                        is UIMessagePart.Image -> {
                            if (!acceptsImages) {
                                add(ToolResultReplayItem.Text(IMAGE_OMITTED_TEXT))
                            } else {
                                val encoded = encodeImage(part).getOrNull()
                                if (encoded == null) {
                                    add(ToolResultReplayItem.Text(IMAGE_ENCODING_FAILED_TEXT))
                                } else {
                                    add(
                                        ToolResultReplayItem.Image(
                                            source = encoded.base64,
                                            mimeType = encoded.mimeType,
                                            base64Payload = encoded.base64.toInlineBase64Payload(),
                                        ),
                                    )
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
            return ToolResultReplayPlan(items)
        }

        private fun String.toInlineBase64Payload(): String? = when {
            startsWith("data:") && ',' in this -> substringAfter(',')
            startsWith("http://") || startsWith("https://") -> null
            else -> this
        }
    }
}

internal sealed interface ToolResultReplayItem {
    data class Text(val value: String) : ToolResultReplayItem

    data class Image(
        val source: String,
        val mimeType: String,
        val base64Payload: String?,
    ) : ToolResultReplayItem
}
