package me.rerere.rikkahub.browser

import android.app.Activity
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.BrowserBookmarkEntity
import me.rerere.rikkahub.data.db.entity.BrowserHistoryEntity

@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserNextView(
    state: BrowserTabsState,
    bookmarks: List<BrowserBookmarkEntity>,
    history: List<BrowserHistoryEntity>,
    conversationId: Uuid?,
    onCloseActivity: () -> Unit,
    onToggleBookmark: (BrowserTabDescriptor) -> Unit,
    onDeleteBookmark: (BrowserBookmarkEntity) -> Unit,
    onDeleteHistory: (BrowserHistoryEntity) -> Unit,
    onClearHistory: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val selected = state.selectedTab
    var showLibrary by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var address by remember(selected?.id, selected?.url) { mutableStateOf(selected?.url.orEmpty()) }
    val normalized = selected?.url?.let(BrowserLibraryPolicy::normalize)
    val isBookmarked = normalized != null && bookmarks.any { it.normalizedUrl == normalized }

    DisposableEffect(activity) {
        onDispose { BrowserTabManager.detachActivity(activity) }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrowserGlyph("‹", stringResource(R.string.browser_next_back)) { BrowserTabManager.goBack() }
                    BrowserGlyph("›", stringResource(R.string.browser_next_forward)) { BrowserTabManager.goForward() }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { BrowserTabManager.navigate(address) }),
                    )
                    BrowserGlyph(if (isBookmarked) "★" else "☆", stringResource(R.string.browser_next_bookmark)) {
                        selected?.let(onToggleBookmark)
                    }
                    Box {
                        BrowserGlyph("⋮", stringResource(R.string.browser_next_menu)) { showMenu = true }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_next_bookmarks_history)) },
                                onClick = { showMenu = false; showLibrary = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (selected?.desktopMode == true) stringResource(R.string.browser_next_mobile_site)
                                        else stringResource(R.string.browser_next_desktop_site),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    selected?.let { BrowserTabManager.setDesktopMode(it.id, !it.desktopMode) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_next_refresh)) },
                                onClick = { showMenu = false; BrowserTabManager.reload() },
                            )
                        }
                    }
                    BrowserGlyph("×", stringResource(R.string.browser_next_close_browser), onCloseActivity)
                }
                if ((selected?.progress ?: 100) in 1..99) {
                    LinearProgressIndicator(
                        progress = { (selected?.progress ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                BrowserTabStrip(state)
            }
        },
        bottomBar = { BrowserAiStripe() },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            key(state.selectedTabId, state.attachmentEpoch) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { FrameLayout(it) },
                    update = { BrowserTabManager.attachSelected(it, activity) },
                )
            }
            selected?.errorDescription?.let { error ->
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.browser_next_page_failed), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        if (selected.errorRetryable) {
                            Button(onClick = BrowserTabManager::reload) {
                                Text(stringResource(R.string.browser_next_retry))
                            }
                        }
                    }
                }
            }
            BrowserMiniChat(
                conversationId = conversationId,
                modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
            )
        }
    }

    if (showLibrary) {
        BrowserLibrarySheet(
            bookmarks = bookmarks,
            history = history,
            onDismiss = { showLibrary = false },
            onOpenCurrent = { url -> showLibrary = false; BrowserTabManager.navigate(url) },
            onOpenNew = { url -> showLibrary = false; BrowserTabManager.newTab(activity, url) },
            onDeleteBookmark = onDeleteBookmark,
            onDeleteHistory = onDeleteHistory,
            onClearHistory = onClearHistory,
        )
    }
    BrowserPendingDialog(state.pendingDialog)
}

@Composable
private fun BrowserGlyph(text: String, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(38.dp).semantics { contentDescription = description },
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BrowserTabStrip(state: BrowserTabsState) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tabs.forEach { tab ->
            val selected = tab.id == state.selectedTabId
            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.width(150.dp).clickable { BrowserTabManager.select(tab.id) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.title.ifBlank { tab.url.ifBlank { stringResource(R.string.browser_next_new_tab) } },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = { BrowserTabManager.close(tab.id) }) { Text("×") }
                }
            }
        }
        TextButton(onClick = { BrowserTabManager.newTab(context, "about:blank") }) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun BrowserLibrarySheet(
    bookmarks: List<BrowserBookmarkEntity>,
    history: List<BrowserHistoryEntity>,
    onDismiss: () -> Unit,
    onOpenCurrent: (String) -> Unit,
    onOpenNew: (String) -> Unit,
    onDeleteBookmark: (BrowserBookmarkEntity) -> Unit,
    onDeleteHistory: (BrowserHistoryEntity) -> Unit,
    onClearHistory: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.browser_next_bookmarks)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.browser_next_history)) })
        }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            if (tab == 1 && history.isNotEmpty()) {
                TextButton(onClick = onClearHistory, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.browser_next_clear_history))
                }
            }
            val items = if (tab == 0) bookmarks else history
            if (items.isEmpty()) {
                Text(
                    if (tab == 0) stringResource(R.string.browser_next_no_bookmarks)
                    else stringResource(R.string.browser_next_no_history),
                    modifier = Modifier.padding(24.dp),
                )
            }
            items.take(200).forEach { item ->
                val title: String
                val url: String
                when (item) {
                    is BrowserBookmarkEntity -> { title = item.title; url = item.url }
                    is BrowserHistoryEntity -> { title = item.title; url = item.url }
                    else -> return@forEach
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenCurrent(url) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title.ifBlank { url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onOpenNew(url) }) { Text(stringResource(R.string.browser_next_new_tab_short)) }
                    TextButton(onClick = {
                        when (item) {
                            is BrowserBookmarkEntity -> onDeleteBookmark(item)
                            is BrowserHistoryEntity -> onDeleteHistory(item)
                        }
                    }) { Text(stringResource(R.string.browser_next_delete)) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BrowserPendingDialog(dialog: BrowserPendingDialog?) {
    when (dialog) {
        is BrowserPendingDialog.Ssl -> AlertDialog(
            onDismissRequest = BrowserTabManager::dismissDialog,
            title = { Text(stringResource(R.string.browser_next_ssl_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.browser_next_ssl_message,
                        dialog.host,
                        dialog.primaryError,
                        dialog.certificateIssuedTo,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { BrowserTabManager.acceptDialog() }) {
                    Text(stringResource(R.string.browser_next_continue_once))
                }
            },
            dismissButton = {
                TextButton(onClick = BrowserTabManager::dismissDialog) {
                    Text(stringResource(R.string.browser_next_go_back))
                }
            },
        )
        is BrowserPendingDialog.JavaScript -> {
            var prompt by remember(dialog) { mutableStateOf(dialog.defaultValue.orEmpty()) }
            AlertDialog(
                onDismissRequest = BrowserTabManager::dismissDialog,
                title = { Text(stringResource(R.string.browser_next_webpage_dialog)) },
                text = {
                    Column {
                        Text(dialog.message)
                        if (dialog.kind == BrowserPendingDialog.JavaScript.Kind.PROMPT) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, singleLine = true)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { BrowserTabManager.acceptDialog(prompt) }) {
                        Text(stringResource(R.string.browser_next_ok))
                    }
                },
                dismissButton = if (dialog.kind == BrowserPendingDialog.JavaScript.Kind.ALERT) null else {
                    { TextButton(onClick = BrowserTabManager::dismissDialog) { Text(stringResource(R.string.browser_next_cancel)) } }
                },
            )
        }
        is BrowserPendingDialog.ExternalProtocol -> AlertDialog(
            onDismissRequest = BrowserTabManager::dismissDialog,
            title = { Text(stringResource(R.string.browser_next_external_app_title)) },
            text = {
                Text(stringResource(R.string.browser_next_external_app_message, dialog.scheme))
            },
            confirmButton = {
                TextButton(onClick = { BrowserTabManager.acceptDialog() }) {
                    Text(stringResource(R.string.browser_next_external_app_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = BrowserTabManager::dismissDialog) {
                    Text(stringResource(R.string.browser_next_cancel))
                }
            },
        )
        null -> Unit
    }
}
