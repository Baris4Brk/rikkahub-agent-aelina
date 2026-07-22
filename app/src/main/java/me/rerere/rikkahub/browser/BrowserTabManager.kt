package me.rerere.rikkahub.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.net.Uri
import android.os.Message
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BrowserTabDescriptor(
    val id: String,
    val title: String,
    val url: String,
    val progress: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val desktopMode: Boolean,
    val errorDescription: String? = null,
    val errorRetryable: Boolean = true,
)

sealed interface BrowserPendingDialog {
    val tabId: String

    data class Ssl(
        override val tabId: String,
        val host: String,
        val primaryError: Int,
        val certificateIssuedTo: String,
    ) : BrowserPendingDialog

    data class JavaScript(
        override val tabId: String,
        val kind: Kind,
        val message: String,
        val defaultValue: String? = null,
    ) : BrowserPendingDialog {
        enum class Kind { ALERT, CONFIRM, PROMPT }
    }

    data class ExternalProtocol(
        override val tabId: String,
        val scheme: String,
    ) : BrowserPendingDialog
}

data class BrowserTabsState(
    val tabs: List<BrowserTabDescriptor> = emptyList(),
    val selectedTabId: String? = null,
    val pendingDialog: BrowserPendingDialog? = null,
    val attachmentEpoch: Long = 0L,
) {
    val selectedTab: BrowserTabDescriptor?
        get() = tabs.firstOrNull { it.id == selectedTabId }
}

/** Android adapter that owns user-visible WebViews while exposing immutable tab state. */
object BrowserTabManager : HeadlessBrowserForegroundEvents {
    private data class PendingExternalNavigation(
        val tabId: String,
        val url: String,
    )
    private data class Record(
        val id: String,
        val contextWrapper: MutableContextWrapper?,
        val webView: WebView,
        val mobileUserAgent: String,
        var descriptor: BrowserTabDescriptor,
        val headlessSession: HeadlessBrowserSession? = null,
    )

    private val ids = AtomicLong(1)
    private val records = linkedMapOf<String, Record>()
    private val mutableState = MutableStateFlow(BrowserTabsState())
    val state: StateFlow<BrowserTabsState> = mutableState.asStateFlow()

    private var attachedTabId: String? = null
    private var appContext: Context? = null
    private var sslHandler: SslErrorHandler? = null
    private var jsResult: JsResult? = null
    private var pendingExternalNavigation: PendingExternalNavigation? = null
    var historyRecorder: ((url: String, title: String) -> Unit)? = null

    @Synchronized
    fun ensureInitialized(context: Context, initialUrl: String) {
        appContext = context.applicationContext
        if (records.isEmpty()) newTab(context, initialUrl, select = true)
    }

    @Synchronized
    fun newTab(context: Context, url: String = "about:blank", select: Boolean = true): String {
        return createTab(context, url, select, load = true)
    }

    /**
     * Creates the destination WebView for WebChromeClient.onCreateWindow.
     *
     * Chromium requires WebViewTransport.webView to have never navigated before it receives
     * the popup. In particular, even loadUrl("about:blank") makes the WebView ineligible and
     * causes "New WebView for popup window must not have been previously navigated".
     */
    @Synchronized
    private fun newPopupTab(context: Context, select: Boolean = true): String {
        return createTab(context, url = "about:blank", select = select, load = false)
    }

    @Synchronized
    private fun createTab(
        context: Context,
        url: String,
        select: Boolean,
        load: Boolean,
        errorDescription: String? = null,
        errorRetryable: Boolean = true,
    ): String {
        appContext = context.applicationContext
        val id = "ui-${ids.getAndIncrement()}"
        val wrapper = MutableContextWrapper(context.applicationContext)
        val isLocalSkill = url.startsWith("file:", ignoreCase = true)
        val webView = WebView(wrapper)
        BrowserProfileManager.apply(webView, BrowserProfileClass.LOCAL_SHARED, "ui")
        configureWebViewForRikka(webView, allowLocalFiles = isLocalSkill)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        val mobileUa = webView.settings.userAgentString.orEmpty()
        val descriptor = BrowserTabDescriptor(
            id = id,
            title = "",
            url = url,
            progress = 0,
            canGoBack = false,
            canGoForward = false,
            desktopMode = false,
            errorDescription = errorDescription,
            errorRetryable = errorRetryable,
        )
        val record = Record(id, wrapper, webView, mobileUa, descriptor)
        records[id] = record
        installClients(record, context.applicationContext)
        if (select || mutableState.value.selectedTabId == null) {
            mutableState.value = mutableState.value.copy(selectedTabId = id)
        }
        publish()
        if (load) webView.loadUrl(url)
        return id
    }

    /** Register and select the exact AI WebView referenced by a notification page id. */
    @Synchronized
    fun openAiPage(
        context: Context,
        pageId: String,
        unavailableMessage: String,
    ): BrowserNotificationResolution {
        appContext = context.applicationContext
        val resolution = BrowserNotificationHandoff.resolve(
            BrowserNotificationTarget(pageId = pageId),
            HeadlessBrowserSessionPool.registeredPageIds(),
        )
        if (resolution !is BrowserNotificationResolution.ShowRegisteredPage) {
            showMissingAiPage(context, unavailableMessage)
            return resolution
        }

        val session = HeadlessBrowserSessionPool.findByPageId(pageId)
            ?: return BrowserNotificationResolution.TargetMissing(pageId).also {
                showMissingAiPage(context, unavailableMessage)
            }
        val webView = session.activeWebView()
            ?: return BrowserNotificationResolution.TargetMissing(pageId).also {
                showMissingAiPage(context, unavailableMessage)
            }
        val pageState = session.currentPageState()
        val existing = records[pageId]
        if (existing != null && existing.headlessSession !== session) {
            existing.headlessSession?.observePageState(null)
            (existing.webView.parent as? ViewGroup)?.removeView(existing.webView)
            records.remove(pageId)
        }
        if (records[pageId] == null) {
            records[pageId] = Record(
                id = pageId,
                contextWrapper = null,
                webView = webView,
                mobileUserAgent = webView.settings.userAgentString.orEmpty(),
                descriptor = BrowserTabDescriptor(
                    id = pageId,
                    title = pageState?.title.orEmpty().ifBlank { "AI" },
                    url = pageState?.url ?: "about:blank",
                    progress = pageState?.progress ?: webView.progress,
                    canGoBack = pageState?.canGoBack ?: webView.canGoBack(),
                    canGoForward = pageState?.canGoForward ?: webView.canGoForward(),
                    desktopMode = false,
                    errorDescription = pageState?.errorDescription,
                ),
                headlessSession = session,
            )
            session.observePageState { state ->
                update(pageId) {
                    it.copy(
                        title = state.title.ifBlank { it.title },
                        url = state.url,
                        progress = state.progress,
                        canGoBack = state.canGoBack,
                        canGoForward = state.canGoForward,
                        errorDescription = state.errorDescription,
                        errorRetryable = true,
                    )
                }
            }
        }
        mutableState.value = mutableState.value.copy(selectedTabId = pageId)
        publish()
        return resolution
    }

    private fun showMissingAiPage(context: Context, unavailableMessage: String) {
        createTab(
            context = context,
            url = "about:blank",
            select = true,
            load = false,
            errorDescription = unavailableMessage,
            errorRetryable = false,
        )
    }

    @Synchronized
    fun select(tabId: String) {
        if (records.containsKey(tabId)) {
            mutableState.value = mutableState.value.copy(selectedTabId = tabId)
            publish()
        }
    }

    @Synchronized
    fun close(tabId: String) {
        val keys = records.keys.toList()
        val index = keys.indexOf(tabId)
        if (index < 0) return
        val record = records.remove(tabId) ?: return
        if (mutableState.value.pendingDialog?.tabId == tabId) dismissDialog()
        if (record.headlessSession != null) {
            record.headlessSession.observePageState(null)
            if (HeadlessBrowserSessionPool.releaseByPageId(tabId)) {
                finishRecordRemoval(tabId, index, record)
                return
            }
        }
        (record.webView.parent as? ViewGroup)?.removeView(record.webView)
        record.webView.stopLoading()
        record.webView.destroy()
        finishRecordRemoval(tabId, index, record)
    }

    @Synchronized
    private fun finishRecordRemoval(tabId: String, index: Int, record: Record) {
        if (attachedTabId == tabId) attachedTabId = null
        var selected = mutableState.value.selectedTabId
        if (selected == tabId) {
            val remaining = records.keys.toList()
            selected = remaining.getOrNull(index) ?: remaining.getOrNull(index - 1)
        }
        mutableState.value = mutableState.value.copy(selectedTabId = selected)
        if (records.isEmpty()) {
            val context = record.contextWrapper?.baseContext ?: appContext
            if (context != null) newTab(context, "about:blank", select = true) else publish()
        } else publish()
    }

    @Synchronized
    fun attachSelected(container: FrameLayout, activity: Activity) {
        val selected = records[mutableState.value.selectedTabId] ?: return
        if (attachedTabId != selected.id) {
            attachedTabId?.let { id ->
                records[id]?.let { detachRecordFromForeground(it, activity) }
            }
            attachedTabId = selected.id
        }
        if (selected.headlessSession != null) {
            selected.headlessSession.attachToForeground(container, activity, this)
        } else {
            selected.contextWrapper?.baseContext = activity
            val currentParent = selected.webView.parent as? ViewGroup
            if (currentParent !== container) {
                currentParent?.removeView(selected.webView)
                container.removeAllViews()
                container.addView(
                    selected.webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    @Synchronized
    fun detachActivity(activity: Activity) {
        attachedTabId?.let { id ->
            records[id]?.let { record ->
                detachRecordFromForeground(record, activity)
            }
        }
        attachedTabId = null
    }

    /** Force AndroidView to re-run its attach update after the Activity becomes visible. */
    @Synchronized
    fun requestReattach() {
        mutableState.value = mutableState.value.copy(
            attachmentEpoch = mutableState.value.attachmentEpoch + 1,
        )
    }

    private fun detachRecordFromForeground(record: Record, activity: Activity) {
        if (record.headlessSession != null) {
            if (mutableState.value.pendingDialog?.tabId == record.id) dismissDialog()
            record.headlessSession.attachToOffscreen()
        } else {
            (record.webView.parent as? ViewGroup)?.removeView(record.webView)
            record.contextWrapper?.baseContext = activity.applicationContext
        }
    }

    /** Remove a tab whose pooled AI session was stopped outside the foreground UI. */
    internal fun onHeadlessSessionReleased(pageId: String) {
        val remove = Runnable {
            synchronized(this) {
                val index = records.keys.indexOf(pageId)
                val record = records.remove(pageId) ?: return@synchronized
                record.observePageStateSafely(null)
                (record.webView.parent as? ViewGroup)?.removeView(record.webView)
                finishRecordRemoval(pageId, index, record)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run()
        else Handler(Looper.getMainLooper()).post(remove)
    }

    private fun Record.observePageStateSafely(listener: ((HeadlessBrowserPageState) -> Unit)?) {
        headlessSession?.observePageState(listener)
    }

    @Synchronized
    fun navigate(raw: String) {
        val record = records[mutableState.value.selectedTabId] ?: return
        val targetUrl = BrowserNavigationPolicy.resolveAddressInput(raw)
        when (
            val decision = BrowserNavigationPolicy.decide(
                currentUrl = record.webView.url,
                targetUrl = targetUrl,
                mode = BrowserInteractionMode.FOREGROUND_USER,
            )
        ) {
            BrowserNavigationDecision.AllowInWebView -> record.webView.loadUrl(targetUrl)
            BrowserNavigationDecision.Block -> Unit
            is BrowserNavigationDecision.AskForegroundUser ->
                showExternalProtocol(record.id, decision.url, decision.scheme)
        }
    }
    fun goBack(): Boolean = selectedWebView()?.takeIf(WebView::canGoBack)?.let { it.goBack(); true } == true
    fun goForward() = selectedWebView()?.takeIf(WebView::canGoForward)?.goForward()
    fun reload() = selectedWebView()?.reload()

    @Synchronized
    fun setDesktopMode(tabId: String, enabled: Boolean) {
        val record = records[tabId] ?: return
        if (record.descriptor.desktopMode == enabled) return
        record.webView.settings.apply {
            userAgentString = if (enabled) DESKTOP_USER_AGENT else record.mobileUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        record.descriptor = record.descriptor.copy(desktopMode = enabled)
        publish()
        record.webView.reload()
    }

    @Synchronized
    fun acceptDialog(promptValue: String? = null) {
        when (mutableState.value.pendingDialog) {
            is BrowserPendingDialog.Ssl -> sslHandler?.proceed()
            is BrowserPendingDialog.JavaScript -> {
                val result = jsResult
                if (result is JsPromptResult) result.confirm(promptValue.orEmpty()) else result?.confirm()
            }
            is BrowserPendingDialog.ExternalProtocol -> openPendingExternalNavigation()
            null -> Unit
        }
        clearDialog()
    }

    @Synchronized
    fun dismissDialog() {
        sslHandler?.cancel()
        jsResult?.cancel()
        clearDialog()
    }

    @Synchronized
    fun selectedWebView(): WebView? = records[mutableState.value.selectedTabId]?.webView

    @Synchronized
    private fun installClients(record: Record, appContext: Context) {
        record.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString() ?: return true
                return when (
                    val decision = BrowserNavigationPolicy.decide(
                        currentUrl = view?.url,
                        targetUrl = targetUrl,
                        mode = BrowserInteractionMode.FOREGROUND_USER,
                    )
                ) {
                    BrowserNavigationDecision.AllowInWebView -> false
                    BrowserNavigationDecision.Block -> true
                    is BrowserNavigationDecision.AskForegroundUser ->
                        showExternalProtocol(record.id, decision.url, decision.scheme)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                update(record.id) {
                    it.copy(
                        url = url ?: it.url,
                        progress = 1,
                        errorDescription = null,
                        errorRetryable = true,
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val finalUrl = url ?: view?.url ?: record.descriptor.url
                val title = view?.title.orEmpty()
                update(record.id) {
                    it.copy(
                        url = finalUrl,
                        title = title,
                        progress = 100,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true,
                    )
                }
                if (BrowserLibraryPolicy.shouldRecordHistory(finalUrl, mainFrameSuccess = true)) {
                    historyRecorder?.invoke(finalUrl, title)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    update(record.id) {
                        it.copy(progress = 100, errorDescription = error?.description?.toString() ?: "Load failed")
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler ?: return
                synchronized(this@BrowserTabManager) { showSslDialog(record.id, handler, error) }
            }
        }
        record.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                update(record.id) { it.copy(progress = newProgress) }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                update(record.id) { it.copy(title = title.orEmpty()) }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
                showJsDialog(record.id, BrowserPendingDialog.JavaScript.Kind.ALERT, message, null, result)

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
                showJsDialog(record.id, BrowserPendingDialog.JavaScript.Kind.CONFIRM, message, null, result)

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?,
            ): Boolean = showJsDialog(
                record.id, BrowserPendingDialog.JavaScript.Kind.PROMPT, message, defaultValue, result,
            )

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (!isUserGesture || resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val newId = newPopupTab(appContext, select = true)
                transport.webView = records[newId]?.webView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    @Synchronized
    private fun showJsDialog(
        tabId: String,
        kind: BrowserPendingDialog.JavaScript.Kind,
        message: String?,
        defaultValue: String?,
        result: JsResult?,
    ): Boolean {
        result ?: return false
        sslHandler?.cancel()
        sslHandler = null
        pendingExternalNavigation = null
        jsResult?.cancel()
        jsResult = result
        mutableState.value = mutableState.value.copy(
            pendingDialog = BrowserPendingDialog.JavaScript(tabId, kind, message.orEmpty(), defaultValue),
        )
        return true
    }

    @Synchronized
    override fun onExternalProtocol(pageId: String, url: String, scheme: String): Boolean {
        return showExternalProtocol(pageId, url, scheme)
    }

    @Synchronized
    private fun showExternalProtocol(pageId: String, url: String, scheme: String): Boolean {
        if (records[pageId] == null) return true
        sslHandler?.cancel()
        sslHandler = null
        jsResult?.cancel()
        jsResult = null
        pendingExternalNavigation = PendingExternalNavigation(pageId, url)
        mutableState.value = mutableState.value.copy(
            pendingDialog = BrowserPendingDialog.ExternalProtocol(pageId, scheme),
        )
        return true
    }

    @Synchronized
    override fun onCreateWindow(pageId: String, resultMsg: Message): Boolean {
        if (records[pageId]?.headlessSession == null) return false
        val context = appContext ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val newId = newPopupTab(context, select = true)
        transport.webView = records[newId]?.webView ?: return false
        resultMsg.sendToTarget()
        return true
    }

    override fun onJavaScriptDialog(
        pageId: String,
        kind: BrowserPendingDialog.JavaScript.Kind,
        message: String?,
        defaultValue: String?,
        result: JsResult?,
    ): Boolean = showJsDialog(pageId, kind, message, defaultValue, result)

    @Synchronized
    override fun onSslError(pageId: String, handler: SslErrorHandler, error: SslError?) {
        showSslDialog(pageId, handler, error)
    }

    @Synchronized
    private fun openPendingExternalNavigation() {
        val pending = pendingExternalNavigation ?: return
        val context = appContext ?: return
        val parsed = runCatching {
            if (pending.url.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(pending.url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(pending.url))
            }
        }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        val target = Intent(Intent.ACTION_VIEW, parsed.data ?: Uri.parse(pending.url)).apply {
            component = null
            selector = null
            setPackage(null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { context.startActivity(target) }.isSuccess
        if (!started && fallback != null && BrowserNavigationPolicy.isSafeWebFallback(fallback)) {
            records[pending.tabId]?.webView?.loadUrl(fallback)
        }
    }

    @Synchronized
    private fun showSslDialog(tabId: String, handler: SslErrorHandler, error: SslError?) {
        sslHandler?.cancel()
        jsResult?.cancel()
        jsResult = null
        pendingExternalNavigation = null
        sslHandler = handler
        mutableState.value = mutableState.value.copy(
            pendingDialog = BrowserPendingDialog.Ssl(
                tabId = tabId,
                host = runCatching { java.net.URI(error?.url.orEmpty()).host.orEmpty() }.getOrDefault(""),
                primaryError = error?.primaryError ?: -1,
                certificateIssuedTo = error?.certificate?.issuedTo?.dName.orEmpty(),
            ),
        )
    }

    @Synchronized
    private fun update(id: String, transform: (BrowserTabDescriptor) -> BrowserTabDescriptor) {
        val record = records[id] ?: return
        record.descriptor = transform(record.descriptor)
        publish()
    }

    @Synchronized
    private fun publish() {
        mutableState.value = mutableState.value.copy(tabs = records.values.map(Record::descriptor))
    }

    @Synchronized
    private fun clearDialog() {
        sslHandler = null
        jsResult = null
        pendingExternalNavigation = null
        mutableState.value = mutableState.value.copy(pendingDialog = null)
    }

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
}
