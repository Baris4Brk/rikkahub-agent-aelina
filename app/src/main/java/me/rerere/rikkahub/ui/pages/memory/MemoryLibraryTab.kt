package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryWriteInput
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import java.time.ZoneId

@Composable
fun MemoryLibraryTab(
    memories: LazyPagingItems<MemoryEntity>,
    narrativeNamesForOrigin: (String?) -> MemoryNarrativeNames,
    filter: MemoryLibraryFilter,
    onFilterChange: ((MemoryLibraryFilter) -> MemoryLibraryFilter) -> Unit,
    onCreate: (MemoryWriteInput) -> Unit,
    onUpdate: (MemoryEntity, MemoryWriteInput) -> Unit,
    onArchive: (Int) -> Unit,
    onRestore: (Int) -> Unit,
    revisions: (Int) -> Flow<List<MemoryRevisionEntity>>,
    onRestoreRevision: (Int, Int) -> Unit,
) {
    var query by remember(filter.query) { mutableStateOf(filter.query) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MemoryEntity?>(null) }
    var showingFilters by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(300)
        if (query != filter.query) onFilterChange { it.copy(query = query) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.memory_v2_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { creating = true }) {
                Text(stringResource(R.string.memory_v2_add_memory))
            }
            FilterChip(
                selected = filter.includeArchived,
                onClick = { onFilterChange { it.copy(includeArchived = !it.includeArchived) } },
                label = { Text(stringResource(R.string.memory_v2_include_archived)) },
            )
            FilterChip(
                selected = filter.hasAdvancedFilters(),
                onClick = { showingFilters = true },
                label = { Text(stringResource(R.string.memory_v2_filters)) },
            )
        }

        when {
            memories.loadState.refresh is LoadState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            memories.loadState.refresh is LoadState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.memory_v2_load_failed))
                    OutlinedButton(onClick = memories::retry) {
                        Text(stringResource(R.string.local_llm_retry))
                    }
                }
            }

            memories.itemCount == 0 -> {
                Text(
                    text = stringResource(R.string.memory_v2_library_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = memories.itemCount,
                    key = memories.itemKey { it.id },
                ) { index ->
                    memories[index]?.let { memory ->
                        MemoryCard(
                            memory = memory,
                            narrativeNames = narrativeNamesForOrigin(memory.originAssistantId),
                            onClick = { detail = memory },
                        )
                    }
                }
                if (memories.loadState.append is LoadState.Loading) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }

    if (creating) {
        MemoryEditorDialog(
            initial = null,
            narrativeNames = narrativeNamesForOrigin(null),
            onDismiss = { creating = false },
            onSave = {
                onCreate(it)
                creating = false
            },
        )
    }
    editing?.let { memory ->
        MemoryEditorDialog(
            initial = memory,
            narrativeNames = narrativeNamesForOrigin(memory.originAssistantId),
            onDismiss = { editing = null },
            onSave = {
                onUpdate(memory, it)
                editing = null
                detail = null
            },
        )
    }
    detail?.let { memory ->
        MemoryDetailDialog(
            memory = memory,
            narrativeNames = narrativeNamesForOrigin(memory.originAssistantId),
            revisions = revisions(memory.id),
            onDismiss = { detail = null },
            onEdit = { editing = memory },
            onArchive = {
                onArchive(memory.id)
                detail = null
            },
            onRestore = {
                onRestore(memory.id)
                detail = null
            },
            onRestoreRevision = { revision -> onRestoreRevision(memory.id, revision) },
        )
    }
    if (showingFilters) {
        MemoryFilterDialog(
            initial = filter,
            onDismiss = { showingFilters = false },
            onApply = { updated ->
                onFilterChange { updated }
                showingFilters = false
            },
            onClear = {
                onFilterChange {
                    it.copy(
                        includeArchived = false,
                        kind = null,
                        sourceType = null,
                        tag = "",
                        sort = MemoryLibrarySort.UPDATED,
                    )
                }
                showingFilters = false
            },
        )
    }
}

@Composable
private fun MemoryFilterDialog(
    initial: MemoryLibraryFilter,
    onDismiss: () -> Unit,
    onApply: (MemoryLibraryFilter) -> Unit,
    onClear: () -> Unit,
) {
    var includeArchived by remember(initial) { mutableStateOf(initial.includeArchived) }
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var sourceType by remember(initial) { mutableStateOf(initial.sourceType.orEmpty()) }
    var tag by remember(initial) { mutableStateOf(initial.tag) }
    var sort by remember(initial) { mutableStateOf(initial.sort) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_v2_filters)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.memory_v2_kind_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = kind == null,
                        onClick = { kind = null },
                        label = { Text(stringResource(R.string.memory_v2_filter_all)) },
                    )
                    MemoryKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.displayName()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it.take(32) },
                    label = { Text(stringResource(R.string.memory_v2_tags_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sourceType,
                    onValueChange = { sourceType = it.take(64) },
                    label = { Text(stringResource(R.string.memory_v2_capture_sources)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                FilterChip(
                    selected = includeArchived,
                    onClick = { includeArchived = !includeArchived },
                    label = { Text(stringResource(R.string.memory_v2_include_archived)) },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = sort == MemoryLibrarySort.UPDATED,
                        onClick = { sort = MemoryLibrarySort.UPDATED },
                        label = { Text(stringResource(R.string.memory_v2_sort_updated)) },
                    )
                    FilterChip(
                        selected = sort == MemoryLibrarySort.IMPORTANCE,
                        onClick = { sort = MemoryLibrarySort.IMPORTANCE },
                        label = { Text(stringResource(R.string.memory_v2_sort_importance)) },
                    )
                    FilterChip(
                        selected = sort == MemoryLibrarySort.RECENT_ACCESS,
                        onClick = { sort = MemoryLibrarySort.RECENT_ACCESS },
                        label = { Text(stringResource(R.string.memory_v2_sort_recent_access)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        initial.copy(
                            includeArchived = includeArchived,
                            kind = kind,
                            sourceType = sourceType.trim().ifBlank { null },
                            tag = tag.trim(),
                            sort = sort,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.assistant_page_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.memory_v2_clear_filters))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    narrativeNames: MemoryNarrativeNames,
    onClick: () -> Unit,
) {
    val tags = remember(memory.tagsJson) { memory.tags() }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = memory.title?.ifBlank { null }
                        ?.let(narrativeNames::readableText) ?: "#${memory.id}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = memory.memoryKind.toMemoryKind().displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = narrativeNames.readableText(memory.content),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            narrativeNames.attributionName(memory.attribution)?.let { name ->
                Text(
                    text = stringResource(R.string.memory_v2_memory_about, name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            narrativeNames.participantsName(memory.participantsJson)?.let { names ->
                Text(
                    text = stringResource(R.string.memory_v2_memory_participants, names),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(8).forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(narrativeNames.readableText(tag)) },
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    R.string.memory_v2_card_meta,
                    memory.sourceType.toNarrativeSourceLabel(narrativeNames),
                    memory.importance,
                    memory.updatedAtMs.asDate(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memory.lifecycleStatus == "ARCHIVED") {
                Text(
                    text = stringResource(R.string.memory_v2_archived),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Keeps legacy source metadata from exposing an internal role label on a memory card. */
@Composable
private fun String.toNarrativeSourceLabel(narrativeNames: MemoryNarrativeNames): String = when (this) {
    "SYSTEM_ASSISTANT" -> stringResource(R.string.memory_v2_origin_system_entry)
    else -> narrativeNames.readableText(this)
}

@Composable
private fun MemoryEditorDialog(
    initial: MemoryEntity?,
    narrativeNames: MemoryNarrativeNames,
    onDismiss: () -> Unit,
    onSave: (MemoryWriteInput) -> Unit,
) {
    var title by remember(initial?.id) {
        mutableStateOf(initial?.title?.let(narrativeNames::readableText).orEmpty())
    }
    var content by remember(initial?.id) {
        mutableStateOf(initial?.content?.let(narrativeNames::readableText).orEmpty())
    }
    var kind by remember(initial?.id) {
        mutableStateOf(
            initial?.memoryKind?.let { raw ->
                runCatching { MemoryKind.valueOf(raw) }.getOrDefault(MemoryKind.OTHER)
            } ?: MemoryKind.OTHER,
        )
    }
    var tags by remember(initial?.id) {
        mutableStateOf(
            initial?.tags()?.map(narrativeNames::readableText)?.joinToString(", ").orEmpty(),
        )
    }
    var importance by remember(initial?.id) { mutableStateOf((initial?.importance ?: 0.5f).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.memory_v2_add_memory else R.string.memory_v2_edit_memory,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text(stringResource(R.string.memory_v2_title_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(2_000) },
                    label = { Text(stringResource(R.string.memory_v2_content_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                Text(
                    text = stringResource(R.string.memory_v2_kind_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MemoryKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.displayName()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.memory_v2_tags_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = importance,
                    onValueChange = { importance = it },
                    label = { Text(stringResource(R.string.memory_v2_importance_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.trim().length >= 1,
                onClick = {
                    onSave(
                        MemoryWriteInput(
                            title = title.trim().ifBlank { null },
                            content = content.trim(),
                            kind = kind,
                            tags = tags.split(',', '，').map(String::trim)
                                .filter(String::isNotEmpty).distinct().take(8),
                            importance = importance.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.assistant_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_page_cancel)) }
        },
    )
}

@Composable
private fun MemoryDetailDialog(
    memory: MemoryEntity,
    narrativeNames: MemoryNarrativeNames,
    revisions: Flow<List<MemoryRevisionEntity>>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onRestoreRevision: (Int) -> Unit,
) {
    val history by revisions.collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(memory.title?.let(narrativeNames::readableText) ?: "#${memory.id}") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                item { Text(narrativeNames.readableText(memory.content)) }
                item {
                    Text(
                        text = stringResource(R.string.memory_v2_revision_current, memory.revision),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                item { HorizontalDivider() }
                item {
                    Text(
                        text = stringResource(R.string.memory_v2_revision_history),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (history.isEmpty()) {
                    item { Text(stringResource(R.string.memory_v2_revision_empty)) }
                } else {
                    items(history, key = { it.id }) { revision ->
                        Column {
                            Text(
                                stringResource(
                                    R.string.memory_v2_revision_item,
                                    revision.revision,
                                    revision.operation,
                                ),
                            )
                            if (revision.revision != memory.revision) {
                                TextButton(onClick = { onRestoreRevision(revision.revision) }) {
                                    Text(stringResource(R.string.memory_v2_restore_revision))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.memory_v2_edit_memory)) }
                if (memory.lifecycleStatus == "ARCHIVED") {
                    TextButton(onClick = onRestore) { Text(stringResource(R.string.memory_v2_restore)) }
                } else {
                    TextButton(onClick = onArchive) { Text(stringResource(R.string.memory_v2_archive)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun MemoryEntity.tags(): List<String> = runCatching {
    JsonInstant.decodeFromString<List<String>>(tagsJson)
}.getOrDefault(emptyList())

@Composable
private fun MemoryKind.displayName(): String = when (this) {
    MemoryKind.USER_PROFILE -> stringResource(R.string.memory_v2_kind_user_profile)
    MemoryKind.PREFERENCE -> stringResource(R.string.memory_v2_kind_preference)
    MemoryKind.LONG_TERM_GOAL -> stringResource(R.string.memory_v2_kind_long_term_goal)
    MemoryKind.PROJECT_FACT -> stringResource(R.string.memory_v2_kind_project_fact)
    MemoryKind.WORKING_CONSTRAINT -> stringResource(R.string.memory_v2_kind_working_constraint)
    MemoryKind.RELATIONSHIP -> stringResource(R.string.memory_v2_kind_relationship)
    MemoryKind.OTHER -> stringResource(R.string.memory_v2_kind_other)
    MemoryKind.EPISODE -> stringResource(R.string.memory_v2_kind_episode)
    MemoryKind.DECISION -> stringResource(R.string.memory_v2_kind_decision)
    MemoryKind.INSIGHT -> stringResource(R.string.memory_v2_kind_insight)
    MemoryKind.THEORY -> stringResource(R.string.memory_v2_kind_theory)
}

private fun String.toMemoryKind(): MemoryKind =
    runCatching { MemoryKind.valueOf(this) }.getOrDefault(MemoryKind.OTHER)

private fun MemoryLibraryFilter.hasAdvancedFilters(): Boolean =
    kind != null || sourceType != null || tag.isNotBlank() || sort != MemoryLibrarySort.UPDATED

private fun Long.asDate(): String = runCatching {
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}.getOrDefault("-")
