package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.navigateToChatPage

@Composable
fun MemoryReviewTab(
    candidates: LazyPagingItems<MemoryCandidateEntity>,
    narrativeNamesForOrigin: (String?) -> MemoryNarrativeNames,
    onLoadMemories: suspend (List<Int>) -> List<MemoryEntity>,
    onResolveSource: suspend (MemoryCandidateEntity) -> MemorySourceLocation?,
    onAccept: (MemoryCandidateEntity, String?, String?) -> Unit,
    onReject: (String) -> Unit,
    onAcceptSafeNew: () -> Unit,
    onRejectAllPending: () -> Unit,
) {
    var editing by remember { mutableStateOf<MemoryCandidateEntity?>(null) }
    when {
        candidates.loadState.refresh is LoadState.Loading -> {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator() }
        }

        candidates.loadState.refresh is LoadState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.memory_v2_load_failed))
                OutlinedButton(onClick = candidates::retry) {
                    Text(stringResource(R.string.local_llm_retry))
                }
            }
        }

        candidates.itemCount == 0 -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.memory_v2_review_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAcceptSafeNew,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.memory_v2_accept_safe_new))
                    }
                    OutlinedButton(
                        onClick = onRejectAllPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.memory_v2_reject_all_pending))
                    }
                }
            }
            items(
                count = candidates.itemCount,
                key = candidates.itemKey { it.id },
            ) { index ->
                candidates[index]?.let { candidate ->
                    MemoryCandidateCard(
                        candidate = candidate,
                        narrativeNames = narrativeNamesForOrigin(candidate.assistantId),
                        narrativeNamesForOrigin = narrativeNamesForOrigin,
                        onLoadMemories = onLoadMemories,
                        onResolveSource = onResolveSource,
                        onAccept = { onAccept(candidate, null, null) },
                        onEditAccept = { editing = candidate },
                        onReject = { onReject(candidate.id) },
                    )
                }
            }
        }
    }

    editing?.let { candidate ->
        CandidateEditDialog(
            candidate = candidate,
            narrativeNames = narrativeNamesForOrigin(candidate.assistantId),
            onDismiss = { editing = null },
            onAccept = { title, content ->
                onAccept(candidate, title, content)
                editing = null
            },
        )
    }
}

@Composable
private fun MemoryCandidateCard(
    candidate: MemoryCandidateEntity,
    narrativeNames: MemoryNarrativeNames,
    narrativeNamesForOrigin: (String?) -> MemoryNarrativeNames,
    onLoadMemories: suspend (List<Int>) -> List<MemoryEntity>,
    onResolveSource: suspend (MemoryCandidateEntity) -> MemorySourceLocation?,
    onAccept: () -> Unit,
    onEditAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val navController = LocalNavController.current
    val coroutineScope = rememberCoroutineScope()
    val targetIds = remember(candidate.targetMemoryIdsJson) {
        runCatching { JsonInstant.decodeFromString<List<Int>>(candidate.targetMemoryIdsJson) }
            .getOrDefault(emptyList())
    }
    var oldMemories by remember(candidate.id) { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    LaunchedEffect(candidate.id, targetIds) {
        oldMemories = onLoadMemories(targetIds)
    }
    val risks = remember(candidate.riskFlagsJson) {
        runCatching { JsonInstant.decodeFromString<List<String>>(candidate.riskFlagsJson) }
            .getOrDefault(emptyList())
    }
    val nearDuplicateLabel = stringResource(R.string.memory_v2_near_duplicate)
    val riskText = remember(risks, nearDuplicateLabel) {
        risks.joinToString { flag ->
            if (flag == "NEAR_DUPLICATE") nearDuplicateLabel else flag
        }
    }
    val isConflict = candidate.status == "CONFLICT"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(candidate.action, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = stringResource(R.string.memory_v2_confidence, candidate.confidence),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (oldMemories.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.memory_v2_existing_memory),
                    style = MaterialTheme.typography.titleSmall,
                )
                oldMemories.forEach { memory ->
                    val oldNarrativeNames = narrativeNamesForOrigin(memory.originAssistantId)
                    Text(
                        text = oldNarrativeNames.readableText(
                            listOfNotNull(memory.title, memory.content).joinToString("\n"),
                        ),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            Text(
                text = stringResource(R.string.memory_v2_proposed_memory),
                style = MaterialTheme.typography.titleSmall,
            )
            if (candidate.title.isNotBlank()) {
                Text(narrativeNames.readableText(candidate.title), style = MaterialTheme.typography.titleMedium)
            }
            Text(narrativeNames.readableText(candidate.content))
            narrativeNames.attributionName(candidate.attribution)?.let { name ->
                Text(
                    text = stringResource(R.string.memory_v2_memory_about, name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            narrativeNames.participantsName(candidate.participantsJson)?.let { names ->
                Text(
                    text = stringResource(R.string.memory_v2_memory_participants, names),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                    text = stringResource(
                        R.string.memory_v2_reason,
                        narrativeNames.readableText(candidate.reason),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (risks.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.memory_v2_risk,
                        riskText,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isConflict) {
                Text(
                    text = stringResource(R.string.memory_v2_conflict_notice),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onAccept, enabled = !isConflict) {
                    Text(stringResource(R.string.memory_v2_accept))
                }
                OutlinedButton(onClick = onEditAccept) {
                    Text(stringResource(R.string.memory_v2_edit_accept))
                }
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.memory_v2_reject))
                }
            }
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        onResolveSource(candidate)?.let { source ->
                            navigateToChatPage(navController, source.conversationId, nodeId = source.nodeId)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.memory_v2_open_source))
            }
        }
    }
}

@Composable
private fun CandidateEditDialog(
    candidate: MemoryCandidateEntity,
    narrativeNames: MemoryNarrativeNames,
    onDismiss: () -> Unit,
    onAccept: (String, String) -> Unit,
) {
    var title by remember(candidate.id) { mutableStateOf(narrativeNames.readableText(candidate.title)) }
    var content by remember(candidate.id) { mutableStateOf(narrativeNames.readableText(candidate.content)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_v2_edit_accept)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text(stringResource(R.string.memory_v2_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(2_000) },
                    label = { Text(stringResource(R.string.memory_v2_content_label)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.trim().length >= 8,
                onClick = { onAccept(title.trim(), content.trim()) },
            ) { Text(stringResource(R.string.memory_v2_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
