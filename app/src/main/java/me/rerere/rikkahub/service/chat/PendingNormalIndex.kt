package me.rerere.rikkahub.service.chat

import kotlin.uuid.Uuid

/**
 * Single in-memory index for pending normal commands.
 *
 * The event loop is the only semantic mutator (edit/promote/cancel/start), while submission
 * and Room recovery may publish envelopes from other dispatchers. Every operation is
 * synchronized so the FIFO order, latest envelope and UI snapshot are always derived from
 * the same state. Wake-up channels carry no envelopes.
 */
internal class PendingNormalIndex {
    private val lock = Any()
    private val order = ArrayList<Uuid>()
    private val envelopes = LinkedHashMap<Uuid, CommandEnvelope<out NormalCommand>>()

    val size: Int
        get() = synchronized(lock) { order.size }

    fun add(
        envelope: CommandEnvelope<out NormalCommand>,
        limit: Int = Int.MAX_VALUE,
    ): Boolean = synchronized(lock) {
        if (order.size >= limit || envelope.id in envelopes) return@synchronized false
        order += envelope.id
        envelopes[envelope.id] = envelope
        true
    }

    fun get(commandId: Uuid): CommandEnvelope<out NormalCommand>? = synchronized(lock) {
        envelopes[commandId]
    }

    fun replace(
        commandId: Uuid,
        envelope: CommandEnvelope<out NormalCommand>,
    ): Boolean = synchronized(lock) {
        if (commandId !in envelopes || envelope.id != commandId) return@synchronized false
        envelopes[commandId] = envelope
        true
    }

    fun peek(): CommandEnvelope<out NormalCommand>? = synchronized(lock) {
        order.firstOrNull()?.let(envelopes::get)
    }

    fun removeFirst(expectedId: Uuid? = null): CommandEnvelope<out NormalCommand>? = synchronized(lock) {
        val firstId = order.firstOrNull() ?: return@synchronized null
        if (expectedId != null && firstId != expectedId) return@synchronized null
        order.removeAt(0)
        envelopes.remove(firstId)
    }

    fun remove(commandId: Uuid): CommandEnvelope<out NormalCommand>? = synchronized(lock) {
        val removed = envelopes.remove(commandId) ?: return@synchronized null
        order.remove(commandId)
        removed
    }

    fun clear(): List<CommandEnvelope<out NormalCommand>> = synchronized(lock) {
        val removed = order.mapNotNull(envelopes::get)
        order.clear()
        envelopes.clear()
        removed
    }

    fun snapshot(): List<CommandEnvelope<out NormalCommand>> = synchronized(lock) {
        order.mapNotNull(envelopes::get)
    }

    fun uiSnapshot(): List<QueuedMessageUiEntry> = snapshot()
        .mapIndexedNotNull { index, envelope ->
            val content = (envelope.command as? SendMessageCommand)?.content
                ?: return@mapIndexedNotNull null
            QueuedMessageUiEntry(
                commandId = envelope.id,
                content = content,
                position = index + 1,
                createdAt = envelope.createdAt,
            )
        }
}
