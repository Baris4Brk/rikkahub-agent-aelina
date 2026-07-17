package me.rerere.rikkahub.ui.pages.chat

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationListPresentationTest {
    @Test
    fun `zero rows distinguish loading error and a genuinely empty history`() {
        assertEquals(
            ConversationListPresentation.Loading,
            conversationListPresentation(0, LoadState.Loading),
        )
        assertEquals(
            ConversationListPresentation.Error,
            conversationListPresentation(0, LoadState.Error(IllegalStateException("db"))),
        )
        assertEquals(
            ConversationListPresentation.Empty,
            conversationListPresentation(0, LoadState.NotLoading(endOfPaginationReached = true)),
        )
        assertEquals(
            ConversationListPresentation.Loading,
            conversationListPresentation(0, LoadState.NotLoading(endOfPaginationReached = false)),
        )
    }

    @Test
    fun `loaded rows always present content while append work continues`() {
        assertEquals(
            ConversationListPresentation.Content,
            conversationListPresentation(1, LoadState.Loading),
        )
        assertEquals(
            ConversationListPresentation.Content,
            conversationListPresentation(
                10,
                LoadState.NotLoading(endOfPaginationReached = false),
            ),
        )
    }
}
