package me.rerere.rikkahub.ui.pages.learning.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewAction
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewDetail
import me.rerere.rikkahub.learning.workflow.review.WorkflowEnableImpact
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewListItem
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewUnavailableReason
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkflowReviewListPage(assistantId: String) {
    val vm: WorkflowReviewVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val state by vm.listState.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.learning_workflow_review_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::refreshList) {
                        Icon(HugeIcons.Refresh01, stringResource(R.string.learning_workflow_refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ReviewSafetyCard() }
            when (val current = state) {
                WorkflowReviewLoadState.Loading -> item { ReviewLoading() }
                WorkflowReviewLoadState.NotFound -> item { ReviewEmptyCard() }
                is WorkflowReviewLoadState.Unavailable -> item {
                    ReviewUnavailableCard(current.reason)
                }
                is WorkflowReviewLoadState.Ready -> if (current.value.isEmpty()) {
                    item { ReviewEmptyCard() }
                } else {
                    items(current.value, key = { it.fence.candidateId }) { item ->
                        CandidateCard(item) {
                            navigator.navigate(
                                Screen.LearningWorkflowDetail(
                                    assistantId = assistantId,
                                    candidateId = item.fence.candidateId,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkflowReviewDetailPage(assistantId: String, candidateId: String) {
    val vm: WorkflowReviewVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val state by vm.detailState.collectAsStateWithLifecycle()
    val busy by vm.busyCandidateId.collectAsStateWithLifecycle()
    val positiveActionsEnabled by vm.positiveActionsEnabled.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmation by remember { mutableStateOf<ReviewConfirmation?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(candidateId) { vm.loadDetail(candidateId) }
    val feedbackText = feedback?.message()
    LaunchedEffect(feedback, feedbackText) {
        val text = feedbackText ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.clearFeedback()
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.learning_workflow_detail_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.loadDetail(candidateId) }, enabled = busy == null) {
                        Icon(HugeIcons.Refresh01, stringResource(R.string.learning_workflow_refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ReviewSafetyCard() }
            when (val current = state) {
                WorkflowReviewLoadState.Loading -> item { ReviewLoading() }
                WorkflowReviewLoadState.NotFound -> item { ReviewEmptyCard() }
                is WorkflowReviewLoadState.Unavailable -> item {
                    ReviewUnavailableCard(current.reason)
                }
                is WorkflowReviewLoadState.Ready -> {
                    val detail = current.value
                    item { CandidateIdentityCard(detail) }
                    item { SourceEvidenceCard(detail) }
                    item { TriggerConditionsCard(detail) }
                    item { SlotsCard(detail) }
                    items(detail.actions, key = WorkflowReviewAction::index) { action ->
                        ActionCard(action)
                    }
                    item { FakeReportCard(detail) }
                    item { RevisionsCard(detail) }
                    item {
                        PromotionCard(
                            detail = detail,
                            busy = busy != null,
                            positiveActionsEnabled = positiveActionsEnabled,
                            onPromote = { confirmation = ReviewConfirmation.Promote(detail) },
                            onEnable = { confirmation = ReviewConfirmation.Enable(detail) },
                        )
                    }
                }
            }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (busy == null) confirmation = null },
            icon = { Icon(HugeIcons.Shield01, null) },
            title = {
                Text(
                    stringResource(
                        if (pending is ReviewConfirmation.Promote) {
                            R.string.learning_workflow_promote_confirm_title
                        } else {
                            R.string.learning_workflow_enable_confirm_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (pending is ReviewConfirmation.Promote) {
                            R.string.learning_workflow_promote_confirm_desc
                        } else {
                            R.string.learning_workflow_enable_confirm_desc
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = busy == null,
                    onClick = {
                        confirmation = null
                        when (pending) {
                            is ReviewConfirmation.Promote -> vm.promoteDisabled(pending.detail)
                            is ReviewConfirmation.Enable -> vm.enable(pending.detail)
                        }
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }, enabled = busy == null) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CandidateCard(item: WorkflowReviewListItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onClick, label = { Text(item.state.name) })
                    AssistChip(
                        onClick = onClick,
                        label = {
                            Text(
                                stringResource(
                                    R.string.learning_workflow_evidence_count,
                                    item.evidenceCount,
                                ),
                            )
                        },
                    )
                }
                Icon(HugeIcons.ArrowRight01, null)
            }
            Text(
                item.triggerSummary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.learning_workflow_list_meta,
                    item.actionCount,
                    item.fence.candidateVersion,
                    item.fence.artifactSha256.take(12),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CandidateIdentityCard(detail: WorkflowReviewDetail) = ReviewSection(
    stringResource(R.string.learning_workflow_identity_section),
) {
    val item = detail.item
    ReviewLine(stringResource(R.string.learning_workflow_state), item.state.name)
    ReviewLine(stringResource(R.string.learning_workflow_candidate_version), item.fence.candidateVersion.toString())
    ReviewLine(stringResource(R.string.learning_workflow_state_version), item.fence.stateVersion.toString())
    ReviewLine(stringResource(R.string.learning_workflow_artifact_hash), item.fence.artifactSha256)
    ReviewLine(stringResource(R.string.learning_workflow_capabilities), detail.capabilitySnapshot.joinToString("\n"))
    ReviewLine(stringResource(R.string.learning_workflow_compiler), detail.compilerVersion)
    ReviewLine(stringResource(R.string.learning_workflow_template), detail.templateVersion)
    ReviewLine(stringResource(R.string.learning_workflow_validator), detail.validatorVersion)
}

@Composable
private fun SourceEvidenceCard(detail: WorkflowReviewDetail) = ReviewSection(
    stringResource(R.string.learning_workflow_source_section),
) {
    val item = detail.item
    ReviewLine(stringResource(R.string.learning_workflow_source_policy), item.sourcePolicyId)
    ReviewLine(stringResource(R.string.learning_workflow_source_revision), item.sourcePolicyRevision.toString())
    ReviewLine(stringResource(R.string.learning_workflow_source_artifact), detail.sourcePolicyArtifactSha256)
    ReviewLine(stringResource(R.string.learning_workflow_grant_digest), detail.sourceGrantDigest)
    ReviewLine(stringResource(R.string.learning_workflow_positive_anchor), detail.positiveAnchorEvidenceId)
    ReviewLine(stringResource(R.string.learning_workflow_evidence), item.evidenceCount.toString())
    detail.evidenceIds.forEachIndexed { index, evidenceId ->
        ReviewLine(
            stringResource(R.string.learning_workflow_evidence_number, index + 1),
            evidenceId,
        )
    }
    ReviewLine(stringResource(R.string.learning_workflow_provider), detail.producerProviderIdentity)
    ReviewLine(stringResource(R.string.learning_workflow_model), detail.producerModelIdentity)
}

@Composable
private fun TriggerConditionsCard(detail: WorkflowReviewDetail) = ReviewSection(
    stringResource(R.string.learning_workflow_trigger_conditions_section),
) {
    ReviewCode(stringResource(R.string.learning_workflow_trigger), detail.trigger)
    if (detail.conditions.isEmpty()) {
        Text(stringResource(R.string.learning_workflow_no_conditions))
    } else {
        detail.conditions.forEachIndexed { index, condition ->
            ReviewCode(stringResource(R.string.learning_workflow_condition_number, index + 1), condition)
        }
    }
}

@Composable
private fun SlotsCard(detail: WorkflowReviewDetail) = ReviewSection(
    stringResource(R.string.learning_workflow_slots_section),
) {
    if (detail.slots.isEmpty()) Text(stringResource(R.string.learning_workflow_no_slots))
    detail.slots.forEach { slot ->
        ReviewLine(slot.name, "${slot.type} · ${slot.displayValue}")
    }
}

@Composable
private fun ActionCard(action: WorkflowReviewAction) = ReviewSection(
    stringResource(R.string.learning_workflow_action_number, action.index + 1),
) {
    ReviewLine(stringResource(R.string.learning_workflow_tool), action.toolName)
    ReviewLine(stringResource(R.string.learning_workflow_risk), action.risk)
    ReviewLine(stringResource(R.string.learning_workflow_origin), action.origin)
    ReviewLine(stringResource(R.string.learning_workflow_schema_hash), action.schemaSha256)
    ReviewLine(stringResource(R.string.learning_workflow_capabilities), action.capabilities.joinToString("\n"))
    ReviewLine(
        stringResource(R.string.learning_workflow_secret_masking),
        stringResource(
            if (action.secretReferenceMasked) R.string.learning_workflow_secret_masked
            else R.string.learning_workflow_secret_none,
        ),
    )
    ReviewCode(stringResource(R.string.learning_workflow_normalized_parameters), action.normalizedParameters)
}

@Composable
private fun FakeReportCard(detail: WorkflowReviewDetail) = ReviewSection(
    stringResource(R.string.learning_workflow_fake_report_section),
) {
    val report = detail.fakeReport
    if (report == null) {
        Text(stringResource(R.string.learning_workflow_fake_report_missing))
    } else {
        ReviewLine(stringResource(R.string.learning_workflow_fake_status), report.status)
        ReviewLine(stringResource(R.string.learning_workflow_verifier), report.verifierVersion)
        ReviewLine(stringResource(R.string.learning_workflow_fixture_hash), report.fixtureSetSha256)
        ReviewLine(stringResource(R.string.learning_workflow_checks), "${report.passedChecks} / ${report.failedChecks}")
        ReviewLine(stringResource(R.string.learning_workflow_failure_codes), report.failureCodes.joinToString("\n").ifBlank { "—" })
    }
}

@Composable
private fun RevisionsCard(detail: WorkflowReviewDetail) = ReviewSection(
    stringResource(R.string.learning_workflow_revisions_section),
) {
    detail.revisions.forEachIndexed { index, revision ->
        if (index > 0) HorizontalDivider()
        Text(
            "v${revision.candidateVersion} · state ${revision.stateVersion} · ${revision.state}",
            fontWeight = FontWeight.SemiBold,
        )
        ReviewLine(stringResource(R.string.learning_workflow_revision_reason), revision.reasonCode)
        ReviewLine(stringResource(R.string.learning_workflow_revision_actor), revision.actor)
        ReviewLine(stringResource(R.string.learning_workflow_artifact_hash), revision.artifactSha256)
        Text(
            DateFormat.getDateTimeInstance().format(Date(revision.createdAtMs)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PromotionCard(
    detail: WorkflowReviewDetail,
    busy: Boolean,
    positiveActionsEnabled: Boolean,
    onPromote: () -> Unit,
    onEnable: () -> Unit,
) = ReviewSection(stringResource(R.string.learning_workflow_activation_section)) {
    Text(
        when (detail.enableImpact) {
            WorkflowEnableImpact.MANUAL_TRIGGER_GATED_ACTIONS ->
                stringResource(R.string.learning_workflow_enable_impact_manual)
        },
    )
    Text(
        stringResource(R.string.learning_workflow_two_step_notice),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = positiveActionsEnabled && detail.canPromoteDisabled && !busy,
        onClick = onPromote,
    ) { Text(stringResource(R.string.learning_workflow_promote_disabled)) }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = positiveActionsEnabled && detail.canEnable && !busy,
        onClick = onEnable,
    ) { Text(stringResource(R.string.learning_workflow_enable_explicitly)) }
    if (!positiveActionsEnabled) {
        Text(
            stringResource(R.string.learning_positive_actions_disabled),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReviewSafetyCard() = ReviewSection(
    stringResource(R.string.learning_workflow_safety_title),
) {
    Text(stringResource(R.string.learning_workflow_safety_desc))
}

@Composable
private fun ReviewEmptyCard() = ReviewSection(stringResource(R.string.learning_workflow_empty_title)) {
    Text(stringResource(R.string.learning_workflow_empty_desc))
}

@Composable
private fun ReviewUnavailableCard(reason: WorkflowReviewUnavailableReason) = ReviewSection(
    stringResource(R.string.learning_workflow_unavailable_title),
) { Text(reason.name) }

@Composable
private fun ReviewLoading() {
    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReviewSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ReviewCode(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private sealed interface ReviewConfirmation {
    val detail: WorkflowReviewDetail
    data class Promote(override val detail: WorkflowReviewDetail) : ReviewConfirmation
    data class Enable(override val detail: WorkflowReviewDetail) : ReviewConfirmation
}

@Composable
private fun WorkflowReviewFeedback.message(): String = when (this) {
    WorkflowReviewFeedback.PromotedDisabled -> stringResource(R.string.learning_workflow_promoted_feedback)
    WorkflowReviewFeedback.PromotionReplayed -> stringResource(R.string.learning_workflow_replayed_feedback)
    WorkflowReviewFeedback.Enabled -> stringResource(R.string.learning_workflow_enabled_feedback)
    WorkflowReviewFeedback.ConflictRefreshed -> stringResource(R.string.learning_workflow_conflict_feedback)
    is WorkflowReviewFeedback.Rejected -> stringResource(R.string.learning_workflow_rejected_feedback, reasonCode)
    is WorkflowReviewFeedback.Unavailable -> stringResource(R.string.learning_workflow_unavailable_feedback, reason.name)
}
