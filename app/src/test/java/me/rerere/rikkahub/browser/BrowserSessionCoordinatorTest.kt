package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionCoordinatorTest {

    private inline fun <reified T> assertIs(actual: Any?): T {
        assertTrue("expected ${T::class.simpleName}, got ${actual?.let { it::class.simpleName }}", actual is T)
        return actual as T
    }

    private val user = BrowserCaller.localUser()
    private val ai = BrowserCaller(
        assistantId = "assistant-77",
        conversationId = "conversation-a",
        runId = "run-a",
        origin = BrowserCallOrigin.LOCAL_CHAT,
    )

    @Test
    fun `switching the visible tab never changes the page controlled by an AI lease`() = runBlocking {
        val coordinator = InMemoryBrowserSessionCoordinator(nowMs = { 100L })
        val userPage = assertIs<BrowserResult.PageCreated>(
            coordinator.dispatch(BrowserRequest.NewPage(user, "https://user.example")),
        ).pageId
        val lease = assertIs<BrowserResult.LeaseCreated>(
            coordinator.dispatch(BrowserRequest.StartTask(ai, "https://ai.example")),
        )

        coordinator.dispatch(BrowserRequest.SelectPage(user, userPage))

        val state = coordinator.state.value
        assertEquals(userPage, state.uiSelectedPageId)
        assertEquals(lease.pageId, state.tasks.single().controlledPageId)
        assertNotEquals(state.uiSelectedPageId, state.tasks.single().controlledPageId)
    }

    @Test
    fun `a caller cannot operate a page owned by another AI lease`() = runBlocking {
        val coordinator = InMemoryBrowserSessionCoordinator()
        val first = assertIs<BrowserResult.LeaseCreated>(
            coordinator.dispatch(BrowserRequest.StartTask(ai, "https://first.example")),
        )
        val other = ai.copy(conversationId = "conversation-b", runId = "run-b")

        val result = coordinator.dispatch(
            BrowserRequest.Navigate(other, first.leaseId, first.pageId, "https://blocked.example"),
        )

        val error = assertIs<BrowserResult.Error>(result)
        assertEquals(BrowserError.TASK_CALLER_MISMATCH, error.code)
        assertEquals("https://first.example", coordinator.state.value.pages.single().url)
    }

    @Test
    fun `closing the selected tab chooses the page on its right before the page on its left`() = runBlocking {
        val coordinator = InMemoryBrowserSessionCoordinator()
        val left = assertIs<BrowserResult.PageCreated>(
            coordinator.dispatch(BrowserRequest.NewPage(user, "https://left.example")),
        ).pageId
        val middle = assertIs<BrowserResult.PageCreated>(
            coordinator.dispatch(BrowserRequest.NewPage(user, "https://middle.example")),
        ).pageId
        val right = assertIs<BrowserResult.PageCreated>(
            coordinator.dispatch(BrowserRequest.NewPage(user, "https://right.example")),
        ).pageId

        coordinator.dispatch(BrowserRequest.SelectPage(user, middle))
        coordinator.dispatch(BrowserRequest.ClosePage(user, middle))
        assertEquals(right, coordinator.state.value.uiSelectedPageId)

        coordinator.dispatch(BrowserRequest.ClosePage(user, right))
        assertEquals(left, coordinator.state.value.uiSelectedPageId)
    }

    @Test
    fun `closing the last tab creates a selected blank page`() = runBlocking {
        val coordinator = InMemoryBrowserSessionCoordinator()
        val only = assertIs<BrowserResult.PageCreated>(
            coordinator.dispatch(BrowserRequest.NewPage(user, "https://only.example")),
        ).pageId

        coordinator.dispatch(BrowserRequest.ClosePage(user, only))

        val pages = coordinator.state.value.pages
        assertEquals(1, pages.size)
        assertEquals("about:blank", pages.single().url)
        assertEquals(pages.single().id, coordinator.state.value.uiSelectedPageId)
    }

    @Test
    fun `finishing an AI task keeps its tab and makes it eligible for hibernation`() = runBlocking {
        var now = 1_000L
        val coordinator = InMemoryBrowserSessionCoordinator(
            nowMs = { now },
            completedPageIdleMs = 600_000L,
        )
        val lease = assertIs<BrowserResult.LeaseCreated>(
            coordinator.dispatch(BrowserRequest.StartTask(ai, "https://result.example")),
        )

        coordinator.dispatch(BrowserRequest.FinishTask(ai, lease.leaseId))
        assertTrue(coordinator.state.value.pages.any { it.id == lease.pageId })
        assertFalse(coordinator.state.value.tasks.single().running)

        now += 600_001L
        coordinator.dispatch(BrowserRequest.HibernateIdle)
        val page = coordinator.state.value.pages.single { it.id == lease.pageId }
        assertEquals(BrowserPageLifecycle.HIBERNATED, page.lifecycle)
    }
}
