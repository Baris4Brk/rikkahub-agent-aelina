package me.rerere.rikkahub.subagent

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Process-scoped handoff between [SubAgentEngine] and the existing ChatService generation path.
 * Every entry is owned by one run and one conversation; compare-and-remove prevents a late
 * cleanup from deleting a newer profile if a conversation id is ever reused.
 */
class SubAgentExecutionProfileRegistry {
    private val profiles = ConcurrentHashMap<Uuid, SubAgentExecutionProfile>()

    fun register(
        conversationId: Uuid,
        profile: SubAgentExecutionProfile,
    ): Boolean = profiles.putIfAbsent(conversationId, profile) == null

    fun get(conversationId: Uuid): SubAgentExecutionProfile? = profiles[conversationId]

    fun remove(conversationId: Uuid, expectedRunId: String) {
        profiles.computeIfPresent(conversationId) { _, current ->
            if (current.runId == expectedRunId) null else current
        }
    }

    internal fun activeCount(): Int = profiles.size
}
