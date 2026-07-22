package me.rerere.rikkahub.browser

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Coroutine-propagated owner for one browser tool execution. */
object BrowserToolInvocationScope {
    private val conversationId = ThreadLocal<String?>()

    fun currentConversationId(): String? = conversationId.get()

    suspend fun <T> withConversation(conversationId: String?, block: suspend () -> T): T =
        withContext(this.conversationId.asContextElement(conversationId)) { block() }
}
