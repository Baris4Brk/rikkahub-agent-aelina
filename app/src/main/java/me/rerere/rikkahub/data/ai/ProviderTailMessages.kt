package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage

/**
 * Volatile provider-only messages pinned after the persisted conversation context.
 *
 * The caller owns the database history; this module only assembles one request payload, so live
 * steering can never be mistaken for the next ordinary user turn or leak into later turns.
 */
class ProviderTailMessages private constructor(
    private val messages: List<UIMessage>,
) {
    fun appendTo(context: List<UIMessage>): List<UIMessage> = context + messages

    companion object {
        val Empty = ProviderTailMessages(emptyList())

        fun fromSteering(deliveries: List<SteeringDelivery>): ProviderTailMessages =
            buildSteeringUserGuidanceMessage(deliveries)
                ?.let { ProviderTailMessages(listOf(it)) }
                ?: Empty
    }
}
