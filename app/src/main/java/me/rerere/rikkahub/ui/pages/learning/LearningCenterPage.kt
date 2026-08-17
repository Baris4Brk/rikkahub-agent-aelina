package me.rerere.rikkahub.ui.pages.learning

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiLearning
import me.rerere.hugeicons.stroke.Archive01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.review.PolicyReviewGrantState
import me.rerere.rikkahub.learning.review.PolicyReviewRevision
import me.rerere.rikkahub.learning.review.PolicyReviewUnavailableReason
import me.rerere.rikkahub.learning.review.ReviewedPolicyDetail
import me.rerere.rikkahub.learning.review.ReviewedPolicyListItem
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date

@Composable
fun LearningCenterPage(id: String) {
    val vm: LearningCenterVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.listState.collectAsStateWithLifecycle()
    val assistantName by vm.assistantName.collectAsStateWithLifecycle()
    val authorityScopeEraseAvailable by vm.authorityScopeEraseAvailable
        .collectAsStateWithLifecycle()
    val policyPositiveActionsEnabled by vm.policyPositiveActionsEnabled
        .collectAsStateWithLifecycle()
    val workflowCandidateActionEnabled by vm.workflowCandidateActionEnabled
        .collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val eraseChallenge by vm.eraseChallenge.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    val feedbackMessage = feedback?.message()
    LaunchedEffect(feedback, feedbackMessage) {
        val message = feedbackMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearFeedback()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.learning_center_title))
                        if (assistantName.isNotBlank()) {
                            Text(
                                assistantName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::refreshList) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.learning_policy_refresh),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PolicyAuthorityWarning() }
            item {
                WorkflowReviewEntryCard(
                    onClick = { navigator.navigate(Screen.LearningWorkflowReview(id)) },
                )
            }
            item {
                CuratorReviewEntryCard(
                    onClick = { navigator.navigate(Screen.LearningCuratorReview(id)) },
                )
            }
            item {
                AssistantScopeEraseCard(onErase = vm::requestAssistantScopeErase)
            }
            if (authorityScopeEraseAvailable) {
                item {
                    AuthorityScopeEraseCard(onErase = vm::requestAuthorityScopeErase)
                }
            }
            when (val current = state) {
                LearningCenterLoadState.Loading -> item {
                    CenterLoading()
                }
                LearningCenterLoadState.NotFound -> item {
                    EmptyPolicyCard()
                }
                is LearningCenterLoadState.Unavailable -> item {
                    UnavailablePolicyCard(current.reason)
                }
                is LearningCenterLoadState.Ready -> {
                    if (current.value.isEmpty()) {
                        item { EmptyPolicyCard() }
                    } else {
                        items(
                            items = current.value,
                            key = { it.policy.fence.policyId },
                        ) { item ->
                            PolicyListCard(
                                item = item,
                                onClick = {
                                    navigator.navigate(
                                        Screen.LearningPolicyDetail(
                                            assistantId = id,
                                            policyId = item.policy.fence.policyId,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    eraseChallenge?.let {
        AlertDialog(
            onDismissRequest = vm::cancelErase,
            icon = { Icon(HugeIcons.Delete02, null) },
            title = { Text(stringResource(R.string.learning_policy_delete_scope_title)) },
            text = { Text(stringResource(R.string.learning_policy_delete_scope_warning)) },
            confirmButton = {
                TextButton(onClick = { vm.confirmErase() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelErase) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AssistantScopeEraseCard(onErase: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.learning_policy_delete_scope),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.learning_policy_delete_scope_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onErase) {
                Icon(HugeIcons.Delete02, null)
                Text(stringResource(R.string.learning_policy_delete_scope))
            }
        }
    }
}

@Composable
private fun AuthorityScopeEraseCard(onErase: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.learning_authority_scope_delete_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.learning_authority_scope_delete_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onErase) {
                Icon(HugeIcons.Delete02, null)
                Text(stringResource(R.string.learning_authority_scope_delete_action))
            }
        }
    }
}

@Composable
private fun WorkflowReviewEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.learning_workflow_review_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.learning_workflow_review_entry_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(HugeIcons.ArrowRight01, null)
        }
    }
}

@Composable
private fun CuratorReviewEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.learning_curator_review_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.learning_curator_review_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(HugeIcons.ArrowRight01, null)
        }
    }
}

@Composable
fun LearningPolicyDetailPage(assistantId: String, policyId: String) {
    val vm: LearningCenterVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val state by vm.detailState.collectAsStateWithLifecycle()
    val busy by vm.busyPolicyId.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val eraseChallenge by vm.eraseChallenge.collectAsStateWithLifecycle()
    val exportedReport by vm.exportedReport.collectAsStateWithLifecycle()
    val policyPositiveActionsEnabled by vm.policyPositiveActionsEnabled
        .collectAsStateWithLifecycle()
    val workflowCandidateActionEnabled by vm.workflowCandidateActionEnabled
        .collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingAction by remember { mutableStateOf<ReviewConfirmation?>(null) }

    LaunchedEffect(policyId) { vm.loadDetail(policyId) }
    val feedbackMessage = feedback?.message()
    LaunchedEffect(feedback, feedbackMessage) {
        val message = feedbackMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearFeedback()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.learning_policy_detail_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.loadDetail(policyId) }) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.learning_policy_refresh),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PolicyAuthorityWarning() }
            when (val current = state) {
                LearningCenterLoadState.Loading -> item { CenterLoading() }
                LearningCenterLoadState.NotFound -> item { EmptyPolicyCard() }
                is LearningCenterLoadState.Unavailable -> item {
                    UnavailablePolicyCard(current.reason)
                }
                is LearningCenterLoadState.Ready -> {
                    val detail = current.value
                    item { PolicyIdentityCard(detail) }
                    item { PolicyAdviceCard(detail) }
                    item { PolicyEvidenceCard(detail) }
                    item { PolicyRuntimeCard(detail) }
                    item { PolicyProducerCard(detail) }
                    item {
                        PolicyRevisionCard(
                            detail = detail,
                            busy = busy != null,
                            positiveActionsEnabled = policyPositiveActionsEnabled,
                            onRestore = { revision ->
                                pendingAction = ReviewConfirmation.Restore(detail, revision)
                            },
                        )
                    }
                    item {
                        PolicyActionsCard(
                            detail = detail,
                            busy = busy != null,
                            positiveActionsEnabled = policyPositiveActionsEnabled,
                            workflowCandidateEnabled = workflowCandidateActionEnabled,
                            onApprove = { pendingAction = ReviewConfirmation.Approve(detail) },
                            onRevoke = { pendingAction = ReviewConfirmation.Revoke(detail) },
                            onSuspend = { pendingAction = ReviewConfirmation.Suspend(detail) },
                            onArchive = { pendingAction = ReviewConfirmation.Archive(detail) },
                            onCreateWorkflow = {
                                pendingAction = ReviewConfirmation.CreateWorkflow(detail)
                            },
                            onExport = { vm.exportRedacted(detail) },
                            onDelete = { vm.requestErase(detail) },
                        )
                    }
                }
            }
        }
    }

    pendingAction?.let { confirmation ->
        ReviewConfirmationDialog(
            confirmation = confirmation,
            onDismiss = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                when (confirmation) {
                    is ReviewConfirmation.Approve -> vm.approve(confirmation.detail)
                    is ReviewConfirmation.Revoke -> vm.revoke(confirmation.detail)
                    is ReviewConfirmation.Suspend -> vm.suspendPolicy(confirmation.detail)
                    is ReviewConfirmation.Archive -> vm.archive(confirmation.detail)
                    is ReviewConfirmation.Restore -> vm.restoreRevision(
                        confirmation.detail,
                        confirmation.revision,
                    )
                    is ReviewConfirmation.CreateWorkflow ->
                        vm.submitWorkflowCandidate(confirmation.detail)
                }
            },
        )
    }
    eraseChallenge?.let {
        AlertDialog(
            onDismissRequest = vm::cancelErase,
            icon = { Icon(HugeIcons.Delete02, null) },
            title = { Text(stringResource(R.string.learning_policy_delete_scope_title)) },
            text = { Text(stringResource(R.string.learning_policy_delete_scope_warning)) },
            confirmButton = {
                TextButton(onClick = { vm.confirmErase(policyId) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelErase) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    exportedReport?.let { report ->
        RedactedReportDialog(report = report, onDismiss = vm::clearExport)
    }
}

@Composable
private fun PolicyAuthorityWarning() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(HugeIcons.Shield01, null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.learning_policy_safety_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.learning_policy_safety_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PolicyListCard(item: ReviewedPolicyListItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onClick, label = { Text(item.policy.status.name) })
                    AssistChip(onClick = onClick, label = { Text(item.grant.state.label()) })
                    if (item.policy.observedUtilityReviewRecommended) {
                        AssistChip(
                            onClick = onClick,
                            label = {
                                Text(stringResource(R.string.learning_policy_utility_review_recommended))
                            },
                        )
                    }
                }
                Icon(HugeIcons.ArrowRight01, null)
            }
            Text(
                item.policy.triggerSummary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.policy.fence.scope.displayScope(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.learning_policy_list_metrics,
                    item.policy.distinctEpisodeSupport,
                    item.policy.positiveEpisodeCount,
                    item.policy.negativeEpisodeCount,
                    item.policy.exposure.shadowRecallCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.learning_policy_revision_artifact,
                    item.policy.fence.stateVersion,
                    item.policy.fence.contentRevision,
                    item.policy.fence.artifactSha256.take(12),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PolicyIdentityCard(detail: ReviewedPolicyDetail) = SectionCard(
    title = stringResource(R.string.learning_policy_identity_section),
) {
    val item = detail.policy.item
    DetailLine(stringResource(R.string.learning_policy_scope), item.fence.scope.displayScope())
    DetailLine(stringResource(R.string.learning_policy_status), item.status.name)
    DetailLine(stringResource(R.string.learning_policy_grant), detail.grant.state.label())
    DetailLine(stringResource(R.string.learning_policy_state_revision), item.fence.stateVersion.toString())
    DetailLine(stringResource(R.string.learning_policy_content_revision), item.fence.contentRevision.toString())
    DetailLine(stringResource(R.string.learning_policy_artifact), item.fence.artifactSha256.take(12))
    DetailLine(
        stringResource(R.string.learning_policy_expiry_reason),
        item.staleReason ?: stringResource(R.string.learning_policy_none),
    )
    DetailLine(
        stringResource(R.string.learning_policy_updated),
        DateFormat.getDateTimeInstance().format(Date(item.updatedAtMs)),
    )
}

@Composable
private fun PolicyAdviceCard(detail: ReviewedPolicyDetail) = SectionCard(
    title = stringResource(R.string.learning_policy_advice_section),
) {
    AdviceBlock(stringResource(R.string.learning_policy_trigger), detail.policy.item.triggerSummary)
    AdviceBlock(stringResource(R.string.learning_policy_procedure), detail.policy.procedureSummary)
    AdviceBlock(stringResource(R.string.learning_policy_verification), detail.policy.verificationSummary)
    AdviceBlock(stringResource(R.string.learning_policy_boundary), detail.policy.boundarySummary)
    AdviceBlock(stringResource(R.string.learning_policy_failure_modes), detail.policy.failureModeSummary)
}

@Composable
private fun PolicyEvidenceCard(detail: ReviewedPolicyDetail) = SectionCard(
    title = stringResource(R.string.learning_policy_evidence_section),
) {
    val item = detail.policy.item
    DetailLine(stringResource(R.string.learning_policy_source_episodes), item.distinctEpisodeSupport.toString())
    DetailLine(stringResource(R.string.learning_policy_positive_evidence), item.positiveEpisodeCount.toString())
    DetailLine(stringResource(R.string.learning_policy_negative_evidence), item.negativeEpisodeCount.toString())
    DetailLine(stringResource(R.string.learning_policy_confidence), "%.3f".format(item.confidence))
}

@Composable
private fun PolicyRuntimeCard(detail: ReviewedPolicyDetail) = SectionCard(
    title = stringResource(R.string.learning_policy_runtime_section),
) {
    val item = detail.policy.item
    DetailLine(
        stringResource(R.string.learning_policy_shadow_hits),
        item.exposure.shadowRecallCount.toString(),
    )
    DetailLine(
        stringResource(R.string.learning_policy_shadow_exact_hits),
        item.exposure.shadowExactTaskRecallCount.toString(),
    )
    DetailLine(
        stringResource(R.string.learning_policy_shadow_token_cost),
        item.exposure.shadowEstimatedTokenCost.toString(),
    )
    DetailLine(
        stringResource(R.string.learning_policy_actual_retrieved_hits),
        item.exposure.actualRetrievedCount.toString(),
    )
    DetailLine(stringResource(R.string.learning_policy_injected_hits), item.exposure.injectedHitCount.toString())
    DetailLine(
        stringResource(R.string.learning_policy_dispatched_hits),
        item.exposure.hostDispatchedHitCount.toString(),
    )
    DetailLine(
        stringResource(R.string.learning_policy_dropped_items),
        item.exposure.droppedItemCount.toString(),
    )
    DetailLine(
        stringResource(R.string.learning_policy_drop_reasons),
        item.exposure.dropReasons.joinToString().ifBlank {
            stringResource(R.string.learning_policy_none)
        },
    )
    DetailLine(stringResource(R.string.learning_policy_token_cost), item.exposure.estimatedTokenCost.toString())
    DetailLine(
        stringResource(R.string.learning_policy_observed_utility_delta),
        item.observedUtilityDelta?.let { "%+.3f".format(it) }
            ?: stringResource(R.string.learning_policy_unknown),
    )
    DetailLine(
        stringResource(R.string.learning_policy_uncertainty),
        item.utilityUncertainty?.let { "%.3f".format(it) }
            ?: stringResource(R.string.learning_policy_unknown),
    )
}

@Composable
private fun PolicyProducerCard(detail: ReviewedPolicyDetail) = SectionCard(
    title = stringResource(R.string.learning_policy_producer_section),
) {
    val policy = detail.policy
    DetailLine(stringResource(R.string.learning_policy_policy_type), policy.policyType)
    DetailLine(stringResource(R.string.learning_policy_provider_kind), policy.producerProviderKind)
    DetailLine(stringResource(R.string.learning_policy_model_identity), policy.producerModelIdentity.take(16))
    DetailLine(stringResource(R.string.learning_policy_provider_identity), policy.producerProviderIdentity.take(16))
    DetailLine(stringResource(R.string.learning_policy_prompt_identity), policy.producerPromptIdentity.take(16))
    DetailLine(stringResource(R.string.learning_policy_template_identity), policy.producerTemplateIdentity.take(16))
    DetailLine(stringResource(R.string.learning_policy_schema_identity), policy.producerSchemaIdentity.take(16))
}

@Composable
private fun PolicyRevisionCard(
    detail: ReviewedPolicyDetail,
    busy: Boolean,
    positiveActionsEnabled: Boolean,
    onRestore: (Long) -> Unit,
) = SectionCard(title = stringResource(R.string.learning_policy_revision_history)) {
    if (detail.policy.revisions.isEmpty()) {
        Text(stringResource(R.string.learning_policy_no_revisions))
    } else {
        detail.policy.revisions.forEachIndexed { index, revision ->
            if (index > 0) HorizontalDivider()
            RevisionRow(
                revision = revision,
                archived = detail.policy.item.status == LearningPolicyStatus.ARCHIVED,
                busy = busy,
                positiveActionsEnabled = positiveActionsEnabled,
                onRestore = onRestore,
            )
        }
    }
}

@Composable
private fun RevisionRow(
    revision: PolicyReviewRevision,
    archived: Boolean,
    busy: Boolean,
    positiveActionsEnabled: Boolean,
    onRestore: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                R.string.learning_policy_revision_row,
                revision.revision,
                revision.reasonCode,
                revision.actor,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            revision.artifactSha256.take(12),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (revision.changedFields.isNotEmpty()) {
            Text(
                revision.changedFields.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (archived && revision.isCurrent) {
            OutlinedButton(
                onClick = { onRestore(revision.revision) },
                enabled = positiveActionsEnabled && !busy,
            ) {
                Icon(HugeIcons.DatabaseRestore, null)
                Text(stringResource(R.string.learning_policy_restore_revision))
            }
        } else if (archived && revision.historicContentRestorable) {
            OutlinedButton(
                onClick = { onRestore(revision.revision) },
                enabled = positiveActionsEnabled && !busy,
            ) {
                Icon(HugeIcons.DatabaseRestore, null)
                Text(stringResource(R.string.learning_policy_restore_revision))
            }
        } else if (archived && !revision.isCurrent) {
            Text(
                stringResource(R.string.learning_policy_historic_restore_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PolicyActionsCard(
    detail: ReviewedPolicyDetail,
    busy: Boolean,
    positiveActionsEnabled: Boolean,
    workflowCandidateEnabled: Boolean,
    onApprove: () -> Unit,
    onRevoke: () -> Unit,
    onSuspend: () -> Unit,
    onArchive: () -> Unit,
    onCreateWorkflow: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) = SectionCard(title = stringResource(R.string.learning_policy_actions_section)) {
    val item = detail.policy.item
    // Technical Policy state is shared, while the durable grant is per consuming Assistant.
    // A second Assistant may approve an already ACTIVE authority-subject Policy independently.
    val canApprove = when (item.status) {
        LearningPolicyStatus.SUSPENDED ->
            detail.grant.state == PolicyReviewGrantState.EXACT_GRANTED
        LearningPolicyStatus.SHADOW,
        LearningPolicyStatus.PROBATION,
        LearningPolicyStatus.ACTIVE,
        -> detail.grant.state !in setOf(
            PolicyReviewGrantState.EXACT_GRANTED,
            PolicyReviewGrantState.STREAM_UNAVAILABLE,
        )
        else -> false
    }
    val canRevoke = detail.grant.state == PolicyReviewGrantState.EXACT_GRANTED
    val canSuspend = item.status == LearningPolicyStatus.ACTIVE
    val canArchive = item.status != LearningPolicyStatus.ARCHIVED &&
        detail.grant.state !in setOf(
            PolicyReviewGrantState.EXACT_GRANTED,
            PolicyReviewGrantState.STALE_GRANTED,
        )

    Button(
        onClick = onApprove,
        enabled = positiveActionsEnabled && canApprove && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.AiLearning, null)
        Text(
            stringResource(
                if (item.status == LearningPolicyStatus.SUSPENDED) {
                    R.string.learning_policy_resume
                } else {
                    R.string.learning_policy_approve_contextual
                },
            ),
        )
    }
    OutlinedButton(
        onClick = onRevoke,
        enabled = canRevoke && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.learning_policy_revoke_grant))
    }
    OutlinedButton(
        onClick = onSuspend,
        enabled = canSuspend && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.learning_policy_suspend_scope))
    }
    OutlinedButton(
        onClick = onArchive,
        enabled = canArchive && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.Archive01, null)
        Text(stringResource(R.string.learning_policy_archive))
    }
    OutlinedButton(
        onClick = onCreateWorkflow,
        enabled = workflowCandidateEnabled && item.status == LearningPolicyStatus.ACTIVE &&
            detail.grant.state == PolicyReviewGrantState.EXACT_GRANTED && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.AiLearning, null)
        Text(stringResource(R.string.learning_policy_create_workflow_candidate))
    }
    if (!positiveActionsEnabled || !workflowCandidateEnabled) {
        Text(
            stringResource(R.string.learning_positive_actions_disabled),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!canArchive && detail.grant.state in setOf(
            PolicyReviewGrantState.EXACT_GRANTED,
            PolicyReviewGrantState.STALE_GRANTED,
        )
    ) {
        Text(
            stringResource(R.string.learning_policy_revoke_before_archive),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(
        onClick = onExport,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.FileExport, null)
        Text(stringResource(R.string.learning_policy_export_redacted))
    }
    TextButton(
        onClick = onDelete,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.Delete02, null, tint = MaterialTheme.colorScheme.error)
        Text(
            stringResource(R.string.learning_policy_delete_scope),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdviceBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private sealed interface ReviewConfirmation {
    val detail: ReviewedPolicyDetail

    data class Approve(override val detail: ReviewedPolicyDetail) : ReviewConfirmation
    data class Revoke(override val detail: ReviewedPolicyDetail) : ReviewConfirmation
    data class Suspend(override val detail: ReviewedPolicyDetail) : ReviewConfirmation
    data class Archive(override val detail: ReviewedPolicyDetail) : ReviewConfirmation
    data class Restore(
        override val detail: ReviewedPolicyDetail,
        val revision: Long,
    ) : ReviewConfirmation
    data class CreateWorkflow(override val detail: ReviewedPolicyDetail) : ReviewConfirmation
}

@Composable
private fun ReviewConfirmationDialog(
    confirmation: ReviewConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val message = when (confirmation) {
        is ReviewConfirmation.Approve -> stringResource(R.string.learning_policy_confirm_approve)
        is ReviewConfirmation.Revoke -> stringResource(R.string.learning_policy_confirm_revoke)
        is ReviewConfirmation.Suspend -> stringResource(R.string.learning_policy_confirm_suspend)
        is ReviewConfirmation.Archive -> stringResource(R.string.learning_policy_confirm_archive)
        is ReviewConfirmation.Restore -> stringResource(R.string.learning_policy_confirm_restore)
        is ReviewConfirmation.CreateWorkflow ->
            stringResource(R.string.learning_policy_create_workflow_confirm)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.learning_policy_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RedactedReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.learning_policy_redacted_report)) },
        text = {
            SelectionContainer {
                Text(report, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                            },
                            context.getString(R.string.learning_policy_redacted_report),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun CenterLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}

@Composable
private fun EmptyPolicyCard() = SectionCard(
    title = stringResource(R.string.learning_policy_empty_title),
) {
    Text(stringResource(R.string.learning_policy_empty_desc))
}

@Composable
private fun UnavailablePolicyCard(reason: PolicyReviewUnavailableReason) = SectionCard(
    title = stringResource(R.string.learning_policy_unavailable_title),
) {
    Text(reason.message())
}

@Composable
private fun PolicyReviewGrantState.label(): String = when (this) {
    PolicyReviewGrantState.NONE -> stringResource(R.string.learning_policy_grant_none)
    PolicyReviewGrantState.EXACT_GRANTED -> stringResource(R.string.learning_policy_grant_active)
    PolicyReviewGrantState.STALE_GRANTED -> stringResource(R.string.learning_policy_grant_stale)
    PolicyReviewGrantState.REVOKED -> stringResource(R.string.learning_policy_grant_revoked)
    PolicyReviewGrantState.STREAM_UNAVAILABLE -> stringResource(R.string.learning_policy_grant_unavailable)
}

@Composable
private fun LearningScope.displayScope(): String = when (this) {
    is LearningScope.Assistant -> stringResource(
        R.string.learning_policy_scope_assistant,
        assistantId.toString().take(8),
    )
    is LearningScope.AuthoritySubject -> stringResource(
        R.string.learning_policy_scope_authority_subject,
        authoritySubjectId.take(24),
    )
}

@Composable
private fun PolicyReviewUnavailableReason.message(): String = when (this) {
    PolicyReviewUnavailableReason.FEATURE_DISABLED -> stringResource(R.string.learning_policy_unavailable_disabled)
    PolicyReviewUnavailableReason.WRONG_PROCESS -> stringResource(R.string.learning_policy_unavailable_process)
    PolicyReviewUnavailableReason.RUNTIME_NOT_READY -> stringResource(R.string.learning_policy_unavailable_runtime)
    PolicyReviewUnavailableReason.RESTORE_IN_PROGRESS -> stringResource(R.string.learning_policy_unavailable_restore)
    PolicyReviewUnavailableReason.STORAGE_FAILURE -> stringResource(R.string.learning_policy_unavailable_storage)
    PolicyReviewUnavailableReason.STREAM_NOT_READY -> stringResource(R.string.learning_policy_unavailable_stream)
    PolicyReviewUnavailableReason.HISTORIC_CONTENT_RESTORE_NOT_SUPPORTED ->
        stringResource(R.string.learning_policy_historic_restore_unavailable)
    PolicyReviewUnavailableReason.GRANT_MUST_BE_REVOKED ->
        stringResource(R.string.learning_policy_revoke_before_archive)
    PolicyReviewUnavailableReason.ACTION_NOT_ALLOWED ->
        stringResource(R.string.learning_policy_unavailable_action)
}

@Composable
private fun LearningCenterFeedback.message(): String = when (this) {
    LearningCenterFeedback.Applied -> stringResource(R.string.learning_policy_feedback_applied)
    LearningCenterFeedback.Duplicate -> stringResource(R.string.learning_policy_feedback_duplicate)
    LearningCenterFeedback.AuthorityCommittedDerivedPending ->
        stringResource(R.string.learning_policy_feedback_authority_pending)
    LearningCenterFeedback.Conflict -> stringResource(R.string.learning_policy_feedback_conflict)
    is LearningCenterFeedback.Unavailable -> reason.message()
    LearningCenterFeedback.Erased -> stringResource(R.string.learning_policy_feedback_erased)
    LearningCenterFeedback.ExportReady -> stringResource(R.string.learning_policy_feedback_export_ready)
    LearningCenterFeedback.WorkflowCandidateVerified ->
        stringResource(R.string.learning_policy_workflow_candidate_verified)
    is LearningCenterFeedback.WorkflowCandidateRejected ->
        stringResource(R.string.learning_policy_workflow_candidate_rejected, reason)
    is LearningCenterFeedback.WorkflowCandidateUnavailable ->
        stringResource(R.string.learning_policy_workflow_candidate_unavailable, reason)
}
