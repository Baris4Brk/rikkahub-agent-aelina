package me.rerere.rikkahub.browser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.BrowserBookmarkEntity
import me.rerere.rikkahub.data.db.entity.BrowserHistoryEntity
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.android.ext.android.inject

/** Foreground host for the persistent multi-tab browser. AI browsing does not launch it. */
@OptIn(ExperimentalUuidApi::class)
class BrowserActivity : ComponentActivity() {
    private val database: AppDatabase by inject()
    private var conversationId: Uuid? = null
    private val destroyed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        val notificationPageId = intent?.getStringExtra(EXTRA_PAGE_ID)
        if (notificationPageId != null) {
            BrowserTabManager.openAiPage(
                this,
                notificationPageId,
                getString(me.rerere.rikkahub.R.string.browser_ai_page_unavailable),
            )
        } else {
            BrowserTabManager.ensureInitialized(
                this,
                intent?.getStringExtra(EXTRA_INITIAL_URL) ?: "about:blank",
            )
        }
        BrowserTabManager.historyRecorder = { url, title ->
            if (!destroyed.get()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val safeUrl = BrowserLibraryPolicy.sanitizeForStorage(url)
                    database.browserLibraryDao().recordHistory(
                        BrowserHistoryEntity(
                            normalizedUrl = BrowserLibraryPolicy.normalize(safeUrl),
                            url = safeUrl,
                            title = title,
                            visitedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!BrowserTabManager.goBack()) finish()
            }
        })

        setContent {
            val state by BrowserTabManager.state.collectAsStateWithLifecycle()
            val bookmarks by database.browserLibraryDao().observeBookmarks()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val history by database.browserLibraryDao().observeHistory()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            RikkahubTheme {
                BrowserNextView(
                    state = state,
                    bookmarks = bookmarks,
                    history = history,
                    conversationId = conversationId,
                    onCloseActivity = ::finish,
                    onToggleBookmark = ::toggleBookmark,
                    onDeleteBookmark = { bookmark ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            database.browserLibraryDao().deleteBookmark(bookmark.normalizedUrl)
                        }
                    },
                    onDeleteHistory = { item ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            database.browserLibraryDao().deleteHistory(item.id)
                        }
                    },
                    onClearHistory = {
                        lifecycleScope.launch(Dispatchers.IO) { database.browserLibraryDao().clearHistory() }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        intent.getStringExtra(EXTRA_PAGE_ID)?.let { pageId ->
            BrowserTabManager.openAiPage(
                this,
                pageId,
                getString(me.rerere.rikkahub.R.string.browser_ai_page_unavailable),
            )
            return
        }
        intent.getStringExtra(EXTRA_INITIAL_URL)?.let { url ->
            if (url.isNotBlank()) BrowserTabManager.newTab(this, url)
        }
    }

    override fun onStart() {
        super.onStart()
        BrowserTabManager.requestReattach()
    }

    override fun onStop() {
        BrowserTabManager.detachActivity(this)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed.set(true)
        BrowserTabManager.historyRecorder = null
        BrowserTabManager.detachActivity(this)
        super.onDestroy()
    }

    private fun readIntent(intent: Intent?) {
        conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }

    private fun toggleBookmark(tab: BrowserTabDescriptor) {
        if (!BrowserLibraryPolicy.shouldRecordHistory(tab.url, mainFrameSuccess = true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val safeUrl = BrowserLibraryPolicy.sanitizeForStorage(tab.url)
            val normalized = BrowserLibraryPolicy.normalize(safeUrl)
            val dao = database.browserLibraryDao()
            val existing = dao.findBookmark(normalized)
            if (existing != null) {
                dao.deleteBookmark(normalized)
            } else {
                val now = System.currentTimeMillis()
                dao.insertBookmark(
                    BrowserBookmarkEntity(
                        normalizedUrl = normalized,
                        url = safeUrl,
                        title = tab.title,
                        createdAtMs = now,
                        updatedAtMs = now,
                    ),
                )
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_URL = "me.rerere.rikkahub.browser.EXTRA_INITIAL_URL"
        const val EXTRA_CONVERSATION_ID = "me.rerere.rikkahub.browser.EXTRA_CONVERSATION_ID"
        const val EXTRA_PAGE_ID = "me.rerere.rikkahub.browser.EXTRA_PAGE_ID"

        fun intent(
            context: android.content.Context,
            url: String? = null,
            conversationId: String? = null,
            pageId: String? = null,
        ): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url != null) putExtra(EXTRA_INITIAL_URL, url)
                if (conversationId != null) putExtra(EXTRA_CONVERSATION_ID, conversationId)
                if (pageId != null) putExtra(EXTRA_PAGE_ID, pageId)
            }
    }
}
