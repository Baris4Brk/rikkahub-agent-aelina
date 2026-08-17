package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.display.DisplayAutomationRuntime
import me.rerere.rikkahub.display.DisplaySessionLifecycle
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionRequest
import me.rerere.rikkahub.data.ai.background.BackgroundAuthorizationCandidate
import me.rerere.rikkahub.plugin.InstalledPluginRecord
import me.rerere.rikkahub.plugin.PluginReviewStatus
import me.rerere.rikkahub.learning.model.LearningRolloutPolicy
import me.rerere.rikkahub.learning.model.LearningRolloutStage
import me.rerere.rikkahub.learning.model.LearningCuratorOperation
import me.rerere.rikkahub.learning.model.LearningRetentionPresetV1
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingAgentRuntimePage(
    vm: AgentRuntimeSettingsViewModel = koinViewModel(),
) {
    val settings by vm.settings.collectAsState()
    val plugins by vm.plugins.collectAsState()
    val busy by vm.busy.collectAsState()
    val notice by vm.notice.collectAsState()
    val learningNotice by vm.learningNotice.collectAsState()
    val learningModels by vm.learningModelCandidates.collectAsState()
    val managedCoordinator = koinInject<ManagedExecutionCoordinator>()
    val displayRuntime = koinInject<DisplayAutomationRuntime>()
    val database = koinInject<me.rerere.rikkahub.data.db.AppDatabase>()
    val plaintextSessions = koinInject<me.rerere.rikkahub.security.SecretPlaintextSessionManager>()
    val managedState by managedCoordinator.state.collectAsState()
    val displayState by displayRuntime.state.collectAsState()
    val ownerOperations by database.hostOperationDao().observeRecent(10).collectAsState(initial = emptyList())
    val ownerServices by database.hostLocalServiceDao().observeEnabled().collectAsState(initial = emptyList())
    val plaintextState by plaintextSessions.state.collectAsState()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmStopManaged by remember { mutableStateOf(false) }
    var selectedLearningModel by remember {
        mutableStateOf<BackgroundAuthorizationCandidate?>(null)
    }
    var pendingRemoteLearningStage by remember {
        mutableStateOf<Pair<LearningRolloutStage, BackgroundAuthorizationCandidate>?>(null)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::install) }
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
    val currentLearningPreferences = settings.learningPreferences.failClosed()
    val currentLearningStage = LearningRolloutPolicy.stageOf(currentLearningPreferences)
        ?: LearningRolloutStage.OFF

    LaunchedEffect(
        learningModels,
        currentLearningPreferences.authorizedModelIdentityDigests,
        currentLearningPreferences.remoteReflectionProviderIdentityDigest,
    ) {
        val persisted = currentLearningPreferences.authorizedModelIdentityDigests.singleOrNull()
        selectedLearningModel = learningModels.singleOrNull { candidate ->
            candidate.modelIdentityDigest == persisted &&
                (!candidate.isRemote || candidate.providerIdentityDigest ==
                    currentLearningPreferences.remoteReflectionProviderIdentityDigest)
        } ?: selectedLearningModel?.takeIf { selected ->
            learningModels.any { candidate ->
                candidate.kind == selected.kind &&
                    candidate.providerIdentityDigest == selected.providerIdentityDigest &&
                    candidate.modelIdentityDigest == selected.modelIdentityDigest
            }
        } ?: learningModels.singleOrNull()
    }

    if (confirmStopManaged) {
        AlertDialog(
            onDismissRequest = { confirmStopManaged = false },
            title = { Text(stringResource(R.string.agent_runtime_stop_managed_title)) },
            text = { Text(stringResource(R.string.agent_runtime_stop_managed_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStopManaged = false
                        scope.launch { managedCoordinator.dispatch(ManagedExecutionRequest.EmergencyStop) }
                    },
                ) { Text(stringResource(R.string.agent_runtime_stop_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopManaged = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingRemoteLearningStage?.let { (stage, target) ->
        AlertDialog(
            onDismissRequest = { pendingRemoteLearningStage = null },
            title = {
                Text(stringResource(R.string.agent_learning_remote_reflection_disclosure_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.agent_learning_remote_reflection_disclosure_summary))
                    Text(
                        stringResource(
                            R.string.agent_learning_remote_reflection_disclosure_provider,
                            target.providerLabel,
                            target.providerIdentityDigest,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.agent_learning_remote_reflection_disclosure_model,
                            target.modelLabel,
                            target.modelIdentityDigest,
                        ),
                    )
                    Text(stringResource(R.string.agent_learning_remote_reflection_disclosure_fields))
                    Text(stringResource(R.string.agent_learning_remote_reflection_disclosure_limits))
                    Text(stringResource(R.string.agent_learning_remote_reflection_disclosure_scope))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoteLearningStage = null
                        vm.setLearningStage(stage, target)
                    },
                ) {
                    Text(stringResource(R.string.agent_learning_remote_reflection_disclosure_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoteLearningStage = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.agent_runtime_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.agent_runtime_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item("owner-runtime") {
                RuntimeSectionCard(
                    title = stringResource(R.string.owner_runtime_section_title),
                    description = stringResource(R.string.owner_runtime_section_desc),
                ) {
                    val authority = me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry.current()
                    Text(
                        text = "Authority: ${if (authority == null) "NOT_ACTIVE" else "ACTIVE (epoch ${authority.authorityEpoch})"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Direct Owner tools: ${me.rerere.rikkahub.owner.OwnerToolFamily.entries.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Plaintext session: ${if (plaintextState is me.rerere.rikkahub.security.SecretPlaintextSessionState.Open) "OPEN" else "CLOSED"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Enabled local services: ${ownerServices.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ownerOperations.isEmpty()) {
                        Text("No Owner operation has been recorded.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        ownerOperations.take(5).forEachIndexed { index, operation ->
                            if (index > 0) HorizontalDivider()
                            Text("${operation.toolFamily} · ${operation.state}", fontWeight = FontWeight.Medium)
                            Text(
                                "${operation.resultCode ?: operation.recoveryCode ?: "IN_PROGRESS"} · ${operation.requestId.take(12)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.SecondUserSecretVault) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.second_user_vault_title))
                    }
                }
            }

            item("agent-learning-shadow") {
                RuntimeSectionCard(
                    title = stringResource(R.string.agent_learning_section_title),
                    description = stringResource(R.string.agent_learning_section_desc),
                ) {
                    Text(
                        text = stringResource(R.string.agent_learning_injection_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.agent_learning_model),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (learningModels.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_learning_no_eligible_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        learningModels.forEach { candidate ->
                            FilterChip(
                                selected = selectedLearningModel?.let { selected ->
                                    selected.kind == candidate.kind &&
                                        selected.providerIdentityDigest ==
                                        candidate.providerIdentityDigest &&
                                        selected.modelIdentityDigest == candidate.modelIdentityDigest
                                } == true,
                                onClick = {
                                    selectedLearningModel = candidate
                                },
                                label = { Text(candidate.displayLabel) },
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LearningRolloutStage.entries.forEach { stage ->
                            val requiresModel = stage == LearningRolloutStage.CANDIDATE_SHADOW ||
                                stage == LearningRolloutStage.RETRIEVAL_SHADOW ||
                                stage == LearningRolloutStage.REVIEWED_POLICY_OPT_IN
                            FilterChip(
                                selected = currentLearningStage == stage,
                                onClick = {
                                    val candidate = selectedLearningModel.takeIf { requiresModel }
                                    if (candidate?.isRemote == true) {
                                        pendingRemoteLearningStage = stage to candidate
                                    } else {
                                        vm.setLearningStage(stage, candidate)
                                    }
                                },
                                enabled = !requiresModel || selectedLearningModel != null,
                                label = {
                                    Text(
                                        stringResource(
                                            when (stage) {
                                                LearningRolloutStage.OFF ->
                                                    R.string.agent_learning_stage_off
                                                LearningRolloutStage.CAPTURE ->
                                                    R.string.agent_learning_stage_capture
                                                LearningRolloutStage.CANDIDATE_SHADOW ->
                                                    R.string.agent_learning_stage_candidate_shadow
                                                LearningRolloutStage.RETRIEVAL_SHADOW ->
                                                    R.string.agent_learning_stage_retrieval_shadow
                                                LearningRolloutStage.REVIEWED_POLICY_OPT_IN ->
                                                    R.string.agent_learning_stage_reviewed_policy_opt_in
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.agent_learning_workflow_candidate),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.agent_learning_workflow_candidate_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = currentLearningPreferences.workflowCandidate,
                            onCheckedChange = vm::setWorkflowCandidateEnabled,
                            enabled = currentLearningPreferences.policyInjection,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.agent_learning_workflow_promotion),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.agent_learning_workflow_promotion_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = currentLearningPreferences.workflowPromotion,
                            onCheckedChange = vm::setWorkflowPromotionEnabled,
                            enabled = currentLearningPreferences.workflowCandidate,
                        )
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.agent_learning_curator_operations),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.agent_learning_curator_operations_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(
                        Triple(
                            LearningCuratorOperation.UPDATE,
                            R.string.agent_learning_curator_update,
                            currentLearningPreferences.curatorUpdate,
                        ),
                        Triple(
                            LearningCuratorOperation.MERGE,
                            R.string.agent_learning_curator_merge,
                            currentLearningPreferences.curatorMerge,
                        ),
                        Triple(
                            LearningCuratorOperation.SPLIT,
                            R.string.agent_learning_curator_split,
                            currentLearningPreferences.curatorSplit,
                        ),
                        Triple(
                            LearningCuratorOperation.SUPERSEDE,
                            R.string.agent_learning_curator_supersede,
                            currentLearningPreferences.curatorSupersede,
                        ),
                    ).forEach { (operation, label, checked) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(label), modifier = Modifier.weight(1f))
                            Switch(
                                checked = checked,
                                onCheckedChange = {
                                    vm.setCuratorOperationEnabled(operation, it)
                                },
                                enabled = currentLearningPreferences.policyInjection,
                            )
                        }
                    }
                    HorizontalDivider()
                    RetentionPresetRow(
                        title = stringResource(R.string.agent_learning_trace_retention),
                        selected = currentLearningPreferences.retention.tracePreset,
                        onSelect = vm::setTraceRetention,
                    )
                    RetentionPresetRow(
                        title = stringResource(R.string.agent_learning_reward_retention),
                        selected = currentLearningPreferences.retention.rewardPreset,
                        onSelect = vm::setRewardRetention,
                    )
                    learningNotice?.let { resultCode ->
                        TextButton(onClick = vm::clearLearningNotice) {
                            Text(
                                stringResource(
                                    if (resultCode in LearningRolloutStage.entries.map {
                                            "learning_rollout_${it.name.lowercase()}"
                                        } + LearningCuratorOperation.entries.map {
                                            "learning_curator_${it.name.lowercase()}_updated"
                                        } + setOf(
                                            "learning_remote_reflection_updated",
                                            "learning_retention_updated",
                                            "learning_workflow_candidate_updated",
                                            "learning_workflow_promotion_updated",
                                        )) {
                                        R.string.agent_learning_change_applied
                                    } else {
                                        R.string.agent_learning_change_rejected
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            notice?.let { currentNotice ->
                item("plugin-notice") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        onClick = vm::clearNotice,
                    ) {
                        Text(
                            text = when (currentNotice) {
                                is PluginSettingsNotice.Installed -> stringResource(
                                    R.string.agent_runtime_plugin_installed,
                                    currentNotice.name,
                                    currentNotice.addedPermissionCount,
                                )
                                is PluginSettingsNotice.Failed -> stringResource(
                                    R.string.agent_runtime_plugin_failed,
                                    currentNotice.code,
                                )
                                PluginSettingsNotice.Updated -> stringResource(
                                    R.string.agent_runtime_plugin_updated,
                                )
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            item("context") {
                RuntimeSectionCard(
                    title = stringResource(R.string.agent_runtime_context_title),
                    description = stringResource(
                        R.string.agent_runtime_context_desc,
                        assistant?.name.orEmpty(),
                    ),
                ) {
                    RuntimeToggleRow(
                        title = stringResource(R.string.agent_runtime_context_enabled),
                        description = stringResource(R.string.agent_runtime_context_enabled_desc),
                        checked = assistant?.autoContextEnabled == true,
                        onCheckedChange = { checked ->
                            vm.updateCurrentAssistant { it.copy(autoContextEnabled = checked) }
                        },
                        enabled = assistant != null,
                    )
                    ContextSourceRows(
                        assistant = assistant,
                        onUpdate = vm::updateCurrentAssistant,
                    )
                    Text(
                        text = stringResource(
                            R.string.agent_runtime_context_budget,
                            assistant?.autoContextMaxChars ?: 6_000,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2_000, 6_000, 12_000).forEach { value ->
                            FilterChip(
                                selected = assistant?.autoContextMaxChars == value,
                                onClick = {
                                    vm.updateCurrentAssistant {
                                        it.copy(autoContextMaxChars = value)
                                    }
                                },
                                label = { Text(value.toString()) },
                                enabled = assistant?.autoContextEnabled == true,
                            )
                        }
                    }
                }
            }

            item("parallel") {
                RuntimeSectionCard(
                    title = stringResource(R.string.agent_runtime_parallel_title),
                    description = stringResource(R.string.agent_runtime_parallel_desc),
                ) {
                    RuntimeToggleRow(
                        title = stringResource(R.string.agent_runtime_parallel_enabled),
                        description = stringResource(R.string.agent_runtime_parallel_enabled_desc),
                        checked = settings.parallelReadOnlyToolsEnabled,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(parallelReadOnlyToolsEnabled = checked) }
                        },
                    )
                    Text(
                        text = stringResource(
                            R.string.agent_runtime_parallel_max,
                            settings.maxParallelReadOnlyTools,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4).forEach { value ->
                            FilterChip(
                                selected = settings.maxParallelReadOnlyTools == value,
                                onClick = {
                                    vm.updateSettings { it.copy(maxParallelReadOnlyTools = value) }
                                },
                                label = { Text(value.toString()) },
                                enabled = settings.parallelReadOnlyToolsEnabled,
                            )
                        }
                    }
                }
            }

            item("display") {
                val activeDisplays = displayState.sessions.count {
                    it.lifecycle == DisplaySessionLifecycle.ACTIVE
                }
                RuntimeSectionCard(
                    title = stringResource(R.string.agent_runtime_display_title),
                    description = stringResource(R.string.agent_runtime_display_desc),
                ) {
                    RuntimeToggleRow(
                        title = stringResource(R.string.agent_runtime_display_enabled),
                        description = stringResource(R.string.agent_runtime_display_enabled_desc),
                        checked = settings.managedVirtualDisplayEnabled,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(managedVirtualDisplayEnabled = checked) }
                        },
                    )
                    Text(
                        stringResource(R.string.agent_runtime_display_active, activeDisplays),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item("managed") {
                RuntimeSectionCard(
                    title = stringResource(R.string.agent_runtime_managed_title),
                    description = stringResource(R.string.agent_runtime_managed_desc),
                ) {
                    val active = managedState.executions.filter { it.alive || it.terminationUncertain }
                    if (active.isEmpty()) {
                        Text(stringResource(R.string.agent_runtime_managed_empty))
                    } else {
                        active.forEachIndexed { index, execution ->
                            if (index > 0) HorizontalDivider()
                            Text(
                                text = execution.name.ifBlank { execution.executionId },
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.agent_runtime_execution_runtime_status,
                                    execution.runtime.name,
                                    execution.status.name,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { confirmStopManaged = true }) {
                            Text(stringResource(R.string.agent_runtime_stop_all))
                        }
                    }
                }
            }

            item("plugins-header") {
                RuntimeSectionCard(
                    title = stringResource(R.string.agent_runtime_plugins_title),
                    description = stringResource(R.string.agent_runtime_plugins_desc),
                ) {
                    RuntimeToggleRow(
                        title = stringResource(R.string.agent_runtime_plugins_enabled),
                        description = stringResource(R.string.agent_runtime_plugins_enabled_desc),
                        checked = settings.pluginRuntimeEnabled,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(pluginRuntimeEnabled = checked) }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = vm::installBuiltInExample,
                            enabled = !busy,
                        ) {
                            if (busy) CircularProgressIndicator() else
                                Text(stringResource(R.string.agent_runtime_plugin_example))
                        }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.agent_runtime_plugin_import))
                        }
                    }
                    if (plugins.isEmpty()) {
                        Text(stringResource(R.string.agent_runtime_plugins_empty))
                    }
                }
            }

            items(plugins, key = InstalledPluginRecord::id) { plugin ->
                PluginRuntimeCard(
                    plugin = plugin,
                    assistantName = assistant?.name.orEmpty(),
                    assistantEnabled = plugin.id in assistant?.enabledPluginIds.orEmpty(),
                    runtimeEnabled = settings.pluginRuntimeEnabled,
                    onApprove = { vm.approve(plugin.id) },
                    onGlobalEnabled = { vm.setPluginEnabled(plugin.id, it) },
                    onAssistantEnabled = {
                        vm.setPluginEnabledForCurrentAssistant(plugin.id, it)
                    },
                )
            }

            item("diagnostics") {
                OutlinedButton(
                    onClick = { navController.navigate(Screen.SettingDiagnostics) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                ) {
                    Text(stringResource(R.string.agent_runtime_open_diagnostics))
                }
            }
        }
    }
}

@Composable
private fun RetentionPresetRow(
    title: String,
    selected: LearningRetentionPresetV1,
    onSelect: (LearningRetentionPresetV1) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LearningRetentionPresetV1.entries.forEach { preset ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { onSelect(preset) },
                    label = {
                        Text(
                            stringResource(
                                when (preset) {
                                    LearningRetentionPresetV1.MINIMAL ->
                                        R.string.agent_learning_retention_minimal
                                    LearningRetentionPresetV1.STANDARD ->
                                        R.string.agent_learning_retention_standard
                                    LearningRetentionPresetV1.EXTENDED ->
                                        R.string.agent_learning_retention_extended
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextSourceRows(
    assistant: me.rerere.rikkahub.data.model.Assistant?,
    onUpdate: ((me.rerere.rikkahub.data.model.Assistant) ->
        me.rerere.rikkahub.data.model.Assistant) -> Unit,
) {
    val enabled = assistant?.autoContextEnabled == true
    RuntimeToggleRow(
        stringResource(R.string.agent_runtime_context_foreground),
        stringResource(R.string.agent_runtime_context_foreground_desc),
        assistant?.autoContextForegroundWindow == true,
        { value -> onUpdate { it.copy(autoContextForegroundWindow = value) } },
        enabled,
    )
    RuntimeToggleRow(
        stringResource(R.string.agent_runtime_context_tree),
        stringResource(R.string.agent_runtime_context_tree_desc),
        assistant?.autoContextUiTree == true,
        { value -> onUpdate { it.copy(autoContextUiTree = value) } },
        enabled,
    )
    RuntimeToggleRow(
        stringResource(R.string.agent_runtime_context_device),
        stringResource(R.string.agent_runtime_context_device_desc),
        assistant?.autoContextDeviceStatus == true,
        { value -> onUpdate { it.copy(autoContextDeviceStatus = value) } },
        enabled,
    )
    RuntimeToggleRow(
        stringResource(R.string.agent_runtime_context_ocr),
        stringResource(R.string.agent_runtime_context_ocr_desc),
        assistant?.autoContextOcrFallback == true,
        { value -> onUpdate { it.copy(autoContextOcrFallback = value) } },
        enabled,
    )
    RuntimeToggleRow(
        stringResource(R.string.agent_runtime_context_usage),
        stringResource(R.string.agent_runtime_context_usage_desc),
        assistant?.autoContextUsageStats == true,
        { value -> onUpdate { it.copy(autoContextUsageStats = value) } },
        enabled,
    )
    RuntimeToggleRow(
        stringResource(R.string.agent_runtime_context_notifications),
        stringResource(R.string.agent_runtime_context_notifications_desc),
        assistant?.autoContextNotifications == true,
        { value -> onUpdate { it.copy(autoContextNotifications = value) } },
        enabled,
    )
}

@Composable
private fun PluginRuntimeCard(
    plugin: InstalledPluginRecord,
    assistantName: String,
    assistantEnabled: Boolean,
    runtimeEnabled: Boolean,
    onApprove: () -> Unit,
    onGlobalEnabled: (Boolean) -> Unit,
    onAssistantEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    R.string.agent_runtime_plugin_name_version,
                    plugin.name,
                    plugin.version,
                ),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (plugin.reviewStatus) {
                    PluginReviewStatus.NEEDS_REVIEW -> stringResource(
                        R.string.agent_runtime_plugin_needs_review,
                    )
                    PluginReviewStatus.APPROVED -> stringResource(
                        R.string.agent_runtime_plugin_approved,
                    )
                    PluginReviewStatus.QUARANTINED -> stringResource(
                        R.string.agent_runtime_plugin_quarantined,
                    )
                },
                color = if (plugin.reviewStatus == PluginReviewStatus.APPROVED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                stringResource(
                    R.string.agent_runtime_plugin_sha,
                    plugin.sourceSha256.take(16),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.agent_runtime_plugin_permissions,
                    plugin.permissions.sorted().joinToString().ifBlank { "—" },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (plugin.pendingAddedPermissions.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.agent_runtime_plugin_added_permissions,
                        plugin.pendingAddedPermissions.sorted().joinToString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (plugin.reviewStatus != PluginReviewStatus.APPROVED) {
                Button(onClick = onApprove) {
                    Text(stringResource(R.string.agent_runtime_plugin_approve))
                }
            }
            RuntimeToggleRow(
                title = stringResource(R.string.agent_runtime_plugin_global),
                description = stringResource(R.string.agent_runtime_plugin_global_desc),
                checked = plugin.enabled,
                onCheckedChange = onGlobalEnabled,
                enabled = plugin.reviewStatus == PluginReviewStatus.APPROVED,
            )
            RuntimeToggleRow(
                title = stringResource(
                    R.string.agent_runtime_plugin_assistant,
                    assistantName,
                ),
                description = stringResource(R.string.agent_runtime_plugin_assistant_desc),
                checked = assistantEnabled,
                onCheckedChange = onAssistantEnabled,
                enabled = runtimeEnabled && plugin.enabled &&
                    plugin.reviewStatus == PluginReviewStatus.APPROVED,
            )
            if (plugin.failureTimestampsMs.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.agent_runtime_plugin_failures,
                        plugin.failureTimestampsMs.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RuntimeSectionCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun RuntimeToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
