package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.permissions.PermissionInventory
import me.rerere.rikkahub.toolcatalog.ToolExperienceEntity
import me.rerere.rikkahub.toolcatalog.ToolExperienceMutationResult
import me.rerere.rikkahub.toolcatalog.ToolExperienceRepository
import me.rerere.rikkahub.toolcatalog.ToolExperienceState
import me.rerere.rikkahub.toolcatalog.ToolSurfaceBuilder
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * Private library for the active second user. It deliberately lists capability metadata and
 * redacted host-authored tutorials only; no argument, output, command, path, or secret reaches
 * this page.
 */
@Composable
fun SecondUserToolLibraryPage(
    repository: ToolExperienceRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val subjectId = SecondUserAuthorityRegistry.current()?.subjectId
    val experienceFlow = remember(subjectId) {
        subjectId?.let { repository.observeLibrary(it) } ?: flowOf(emptyList())
    }
    val experiences by experienceFlow.collectAsState(initial = emptyList())
    val permissionRows = remember(context) {
        PermissionInventory.capabilityStatusRows(context).associateBy { it.id.removePrefix("capability:") }
    }
    val toolEntries = remember(permissionRows) {
        ToolSurfaceBuilder.staticCapabilityBaseline().snapshot.entries
            .map { entry ->
                ToolLibraryEntry(
                    toolName = entry.toolName,
                    category = entry.categoryPath,
                    source = entry.source.name,
                    risk = entry.risk?.name ?: "UNKNOWN",
                    approval = entry.approval.name,
                    requirements = entry.requirements,
                    availability = entry.capabilityId?.let { capabilityId ->
                        permissionRows[capabilityId]?.statusLabel
                            ?: permissionRows[capabilityId]?.status?.name
                            ?: "UNKNOWN"
                    } ?: "HOST_CONTROLLED",
                )
            }
            .sortedWith(compareBy(ToolLibraryEntry::category, ToolLibraryEntry::toolName))
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<ToolExperienceEntity?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editBody by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var editResult by remember { mutableStateOf<String?>(null) }

    fun beginEdit(experience: ToolExperienceEntity) {
        editing = experience
        editTitle = experience.title
        editBody = experience.body
        editTags = experience.tagsJson
            .removePrefix("[")
            .removeSuffix("]")
            .split(',')
            .joinToString(", ") { it.trim().removeSurrounding("\"") }
        editResult = null
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.second_user_tool_library_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.second_user_tool_library_tools)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.second_user_tool_library_experiences)) },
                )
            }
            if (selectedTab == 0) {
                ToolCatalogContent(toolEntries)
            } else {
                ExperienceContent(
                    subjectId = subjectId,
                    experiences = experiences,
                    onEdit = ::beginEdit,
                    onSetState = { experience, state ->
                        scope.launch {
                            repository.setState(experience.experienceId, experience.stateVersion, state)
                        }
                    },
                )
            }
        }
    }

    editing?.let { experience ->
        val revisions by remember(experience.experienceId) {
            repository.observeRevisions(experience.experienceId)
        }.collectAsState(initial = emptyList())
        val evidence by remember(experience.experienceId) {
            repository.observeEvidence(experience.experienceId)
        }.collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(experience.primaryToolName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${experience.confidence} · ${experience.state}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editBody,
                        onValueChange = { editBody = it.take(1_200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Redacted procedure") },
                        minLines = 4,
                    )
                    OutlinedTextField(
                        value = editTags,
                        onValueChange = { editTags = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tags (comma separated)") },
                        singleLine = true,
                    )
                    Text(
                        "Bindings, schema fingerprint, authority ownership, and evidence cannot be edited.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "Evidence: ${evidence.size} host-confirmed event(s); " +
                            evidence.take(3).joinToString { it.outcomeKind },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (revisions.isNotEmpty()) {
                        Text("Recent revisions", style = MaterialTheme.typography.labelMedium)
                        revisions.take(5).forEach { revision ->
                            Text(
                                "v${revision.revision} · ${revision.actor}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    editResult?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = repository.editByUser(
                            id = experience.experienceId,
                            expectedVersion = experience.stateVersion,
                            title = editTitle,
                            body = editBody,
                            tags = editTags.split(',').map(String::trim).filter(String::isNotBlank),
                        )
                        editResult = when (result) {
                            is ToolExperienceMutationResult.Updated -> {
                                editing = null
                                context.getString(R.string.second_user_tool_library_edited)
                            }
                            else -> result.toString()
                        }
                    }
                }) { Text(stringResource(R.string.second_user_tool_library_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ToolCatalogContent(entries: List<ToolLibraryEntry>) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "The runtime directory is authoritative. This view is the source/configuration baseline; " +
                    "a tool is injected only when its current permission, bridge, and entry checks pass.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        entries.groupBy(ToolLibraryEntry::category).forEach { (category, grouped) ->
            item(key = "header:$category") {
                Text(category, style = MaterialTheme.typography.titleMedium)
            }
            items(grouped, key = { "tool:${it.toolName}" }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(entry.toolName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${entry.availability} · ${entry.source} · ${entry.risk} · ${entry.approval}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            entry.requirements.joinToString().ifBlank { "No additional declared requirement." },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    stringResource(R.string.second_user_tool_library_dynamic),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ExperienceContent(
    subjectId: String?,
    experiences: List<ToolExperienceEntity>,
    onEdit: (ToolExperienceEntity) -> Unit,
    onSetState: (ToolExperienceEntity, ToolExperienceState) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (subjectId == null) {
            item {
                Text("This library is available only while the configured second user is active.")
            }
        } else if (experiences.isEmpty()) {
            item { Text(stringResource(R.string.second_user_tool_library_empty)) }
        } else {
            items(experiences, key = ToolExperienceEntity::experienceId) { experience ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(experience.title, style = MaterialTheme.typography.titleSmall)
                        Text(experience.primaryToolName, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${experience.confidence} · ${experience.state}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (experience.tagsJson != "[]") {
                            Text(experience.tagsJson, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(experience.body, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                        Row {
                            TextButton(onClick = { onEdit(experience) }) { Text("Edit") }
                            when (experience.state) {
                                ToolExperienceState.ACTIVE.name -> {
                                    TextButton(onClick = {
                                        onSetState(experience, ToolExperienceState.DISABLED)
                                    }) { Text(stringResource(R.string.second_user_tool_library_disable)) }
                                    TextButton(onClick = {
                                        onSetState(experience, ToolExperienceState.SOFT_DELETED)
                                    }) { Text(stringResource(R.string.second_user_tool_library_delete)) }
                                }
                                ToolExperienceState.DISABLED.name,
                                ToolExperienceState.SOFT_DELETED.name,
                                -> TextButton(onClick = {
                                    onSetState(experience, ToolExperienceState.ACTIVE)
                                }) { Text(stringResource(R.string.second_user_tool_library_restore)) }
                                else -> TextButton(onClick = {
                                    onSetState(experience, ToolExperienceState.SOFT_DELETED)
                                }) { Text(stringResource(R.string.second_user_tool_library_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ToolLibraryEntry(
    val toolName: String,
    val category: String,
    val source: String,
    val risk: String,
    val approval: String,
    val requirements: List<String>,
    val availability: String,
)
