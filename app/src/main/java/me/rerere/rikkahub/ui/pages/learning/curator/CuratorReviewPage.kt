package me.rerere.rikkahub.ui.pages.learning.curator

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.learning.curator.CuratorReviewDetail
import me.rerere.rikkahub.learning.curator.CuratorReviewListItem
import me.rerere.rikkahub.learning.curator.CuratorDeltaOperation
import me.rerere.rikkahub.learning.curator.CuratorProductionSourceProjection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CuratorReviewListPage(assistantId: String) {
    val vm: CuratorReviewVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val state by vm.listState.collectAsStateWithLifecycle()
    val sourceState by vm.proposalSources.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    val anyOperationEnabled = CuratorDeltaOperation.entries.any(vm::operationEnabled)
    LaunchedEffect(feedback) {
        feedback?.let { snackbar.showSnackbar(it.message()) }
        vm.clearFeedback()
    }
    CuratorScaffold(
        title = stringResource(R.string.learning_curator_review_title),
        refresh = {
            vm.refreshList()
            vm.refreshProposalSources()
        },
        snackbar = snackbar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CuratorSafetyCard() }
            item {
                OutlinedButton(
                    onClick = { showCreate = !showCreate },
                    enabled = !busy && anyOperationEnabled,
                ) {
                    Text(stringResource(R.string.learning_curator_create))
                }
                if (!anyOperationEnabled) {
                    Text(stringResource(R.string.learning_positive_actions_disabled))
                }
            }
            if (showCreate) {
                item {
                    CuratorProposalForm(
                        sourceState = sourceState,
                        busy = busy,
                        operationEnabled = vm::operationEnabled,
                        submit = vm::proposeCandidate,
                    )
                }
            }
            when (val current = state) {
                CuratorReviewLoadState.Loading -> item {
                    Text(stringResource(R.string.workspace_detail_loading))
                }
                CuratorReviewLoadState.NotFound -> item { Text(stringResource(R.string.learning_curator_empty)) }
                CuratorReviewLoadState.Unavailable -> item { Text(stringResource(R.string.learning_curator_unavailable)) }
                is CuratorReviewLoadState.Ready -> if (current.value.isEmpty()) {
                    item { Text(stringResource(R.string.learning_curator_empty)) }
                } else {
                    items(current.value, key = CuratorReviewListItem::candidateId) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                navigator.navigate(Screen.LearningCuratorDetail(assistantId, item.candidateId))
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = CustomColors.listItemColors.containerColor,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.operation.name, fontWeight = FontWeight.SemiBold)
                                    Text("${item.state} · r${item.stateVersion}")
                                    Text(
                                        stringResource(
                                            R.string.learning_curator_counts,
                                            item.sourceCount,
                                            item.evidenceCount,
                                            item.diffTargetCount,
                                        ),
                                    )
                                }
                                Icon(HugeIcons.ArrowRight01, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CuratorReviewDetailPage(assistantId: String, candidateId: String) {
    val vm: CuratorReviewVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val state by vm.detailState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<CuratorAction?>(null) }
    LaunchedEffect(candidateId) { vm.loadDetail(candidateId) }
    LaunchedEffect(feedback) {
        feedback?.let { snackbar.showSnackbar(it.message()) }
        vm.clearFeedback()
    }
    CuratorScaffold(
        title = stringResource(R.string.learning_curator_detail_title),
        refresh = { vm.loadDetail(candidateId) },
        snackbar = snackbar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CuratorSafetyCard() }
            when (val current = state) {
                CuratorReviewLoadState.Loading -> item {
                    Text(stringResource(R.string.workspace_detail_loading))
                }
                CuratorReviewLoadState.NotFound -> item { Text(stringResource(R.string.learning_curator_empty)) }
                CuratorReviewLoadState.Unavailable -> item { Text(stringResource(R.string.learning_curator_unavailable)) }
                is CuratorReviewLoadState.Ready -> {
                    val detail = current.value
                    item { CuratorIdentityCard(detail) }
                    item { CuratorSourceFenceCard(detail) }
                    item { CuratorEvidenceFenceCard(detail) }
                    item { CuratorFieldDiffCard(detail) }
                    item { CuratorRuntimeStateCard(detail) }
                    item { CuratorRevisionReceiptCard(detail) }
                    item { CuratorLineageReceiptCard(detail) }
                    item {
                        CuratorActionCard(
                            detail = detail,
                            busy = busy,
                            positiveActionsEnabled = vm.operationEnabled(detail.summary.operation),
                        ) { pending = it }
                    }
                }
            }
        }
    }
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.learning_curator_confirm_title)) },
            text = { Text(action.confirmText()) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    when (action) {
                        is CuratorAction.Approve -> vm.approve(action.detail)
                        is CuratorAction.Reject -> vm.reject(action.detail)
                        is CuratorAction.Apply -> vm.apply(action.detail)
                        is CuratorAction.Rollback -> vm.rollback(action.detail)
                        is CuratorAction.Archive -> vm.archive(action.detail)
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CuratorProposalForm(
    sourceState: CuratorReviewLoadState<List<CuratorProductionSourceProjection>>,
    busy: Boolean,
    operationEnabled: (CuratorDeltaOperation) -> Boolean,
    submit: (
        CuratorDeltaOperation,
        Set<String>,
        String,
        String,
        List<String>,
        List<String>?,
    ) -> Unit,
) = Card {
    var operation by remember { mutableStateOf(CuratorDeltaOperation.UPDATE_CANDIDATE) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var outputId by remember { mutableStateOf("") }
    var secondOutputId by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("") }
    var procedure by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf("") }
    var boundary by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var toolSchemas by remember { mutableStateOf("") }
    var trigger2 by remember { mutableStateOf("") }
    var procedure2 by remember { mutableStateOf("") }
    var verification2 by remember { mutableStateOf("") }
    var boundary2 by remember { mutableStateOf("") }
    var failure2 by remember { mutableStateOf("") }
    var toolSchemas2 by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val sources = (sourceState as? CuratorReviewLoadState.Ready)?.value.orEmpty()
    val selectedSources = sources.filter { it.exact.source.policyId in selected }
    val compatible = selectedSources.firstOrNull()?.let { first ->
        selectedSources.all {
            it.exact.source.scope == first.exact.source.scope &&
                it.policyType == first.policyType && it.taskSignature == first.taskSignature
        }
    } ?: false
    val sourceCountValid = when (operation) {
        CuratorDeltaOperation.MERGE_CANDIDATE -> selected.size in 2..8
        else -> selected.size == 1
    }
    val outputValid = operation == CuratorDeltaOperation.UPDATE_CANDIDATE || outputId.isNotBlank()
    val secondValid = operation != CuratorDeltaOperation.SPLIT_CANDIDATE ||
        secondOutputId.isNotBlank() && secondOutputId != outputId
    val firstValues = listOf(trigger, procedure, verification, boundary, failure, toolSchemas)
    val secondValues = listOf(trigger2, procedure2, verification2, boundary2, failure2, toolSchemas2)
    val contentValid = firstValues.take(5).all(String::isNotBlank) &&
        (operation != CuratorDeltaOperation.SPLIT_CANDIDATE ||
            secondValues.take(5).all(String::isNotBlank))

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.learning_curator_create), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.learning_curator_create_desc))
        Text(stringResource(R.string.learning_curator_operation), fontWeight = FontWeight.SemiBold)
        CuratorDeltaOperation.entries.forEach { candidateOperation ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = operation == candidateOperation,
                    enabled = operationEnabled(candidateOperation),
                    onClick = {
                        operation = candidateOperation
                        selected = emptySet()
                    },
                )
                Text(candidateOperation.name)
            }
        }
        Text(stringResource(R.string.learning_curator_select_sources), fontWeight = FontWeight.SemiBold)
        when (sourceState) {
            CuratorReviewLoadState.Loading -> Text(stringResource(R.string.workspace_detail_loading))
            CuratorReviewLoadState.NotFound,
            CuratorReviewLoadState.Unavailable,
            -> Text(stringResource(R.string.learning_curator_unavailable))
            is CuratorReviewLoadState.Ready -> sourceState.value.forEach { source ->
                val id = source.exact.source.policyId
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = id in selected,
                        onCheckedChange = { checked ->
                            val wasEmpty = selected.isEmpty()
                            selected = if (checked) selected + id else selected - id
                            if (checked && wasEmpty) {
                                val document = source.document
                                trigger = document.trigger
                                procedure = document.procedure
                                verification = document.verification
                                boundary = document.boundary
                                failure = document.failureMode
                                toolSchemas = document.applicableToolSchemaSha256.joinToString(",")
                                trigger2 = document.trigger
                                procedure2 = document.procedure
                                verification2 = document.verification
                                boundary2 = document.boundary
                                failure2 = document.failureMode
                                toolSchemas2 = document.applicableToolSchemaSha256.joinToString(",")
                            }
                        },
                    )
                    Column {
                        Text(id)
                        Text(
                            "${source.exact.expectedStorageState} · " +
                                "r${source.exact.source.expectedRevision} · " +
                                source.exact.source.baseHash,
                        )
                        Text("contentRevision=${source.exact.expectedContentRevision}")
                        Text("updatedAtMs=${source.exact.expectedUpdatedAtMs}")
                        source.evidence.forEach { evidence ->
                            Text(
                                "evidence=${evidence.evidenceId} r${evidence.sourceRevision} " +
                                    evidence.integritySha256,
                            )
                        }
                        Text("trigger=${source.document.trigger}")
                        Text("procedure=${source.document.procedure}")
                        Text("verification=${source.document.verification}")
                        Text("boundary=${source.document.boundary}")
                        Text("failureMode=${source.document.failureMode}")
                        Text("toolSchemas=${source.document.applicableToolSchemaSha256.joinToString(",")}")
                    }
                }
            }
        }
        if (operation != CuratorDeltaOperation.UPDATE_CANDIDATE) {
            OutlinedTextField(
                value = outputId,
                onValueChange = { outputId = it },
                label = { Text(stringResource(R.string.learning_curator_output_policy_id)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (operation == CuratorDeltaOperation.SPLIT_CANDIDATE) {
            OutlinedTextField(
                value = secondOutputId,
                onValueChange = { secondOutputId = it },
                label = { Text(stringResource(R.string.learning_curator_second_output_policy_id)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CuratorDocumentFields(
            trigger, { trigger = it }, procedure, { procedure = it },
            verification, { verification = it }, boundary, { boundary = it },
            failure, { failure = it },
            toolSchemas, { toolSchemas = it },
        )
        if (operation == CuratorDeltaOperation.SPLIT_CANDIDATE) {
            Text(stringResource(R.string.learning_curator_second_output_policy_id), fontWeight = FontWeight.SemiBold)
            CuratorDocumentFields(
                trigger2, { trigger2 = it }, procedure2, { procedure2 = it },
                verification2, { verification2 = it }, boundary2, { boundary2 = it },
                failure2, { failure2 = it },
                toolSchemas2, { toolSchemas2 = it },
            )
        }
        OutlinedButton(
            onClick = { confirm = true },
            enabled = operationEnabled(operation) && !busy && sourceCountValid && compatible &&
                outputValid && secondValid && contentValid,
        ) { Text(stringResource(R.string.learning_curator_submit_proposal)) }
        if (!operationEnabled(operation)) {
            Text(stringResource(R.string.learning_positive_actions_disabled))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.learning_curator_confirm_title)) },
            text = { Text(stringResource(R.string.learning_curator_confirm_proposal)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    submit(
                        operation,
                        selected,
                        outputId,
                        secondOutputId,
                        firstValues,
                        secondValues.takeIf { operation == CuratorDeltaOperation.SPLIT_CANDIDATE },
                    )
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CuratorDocumentFields(
    trigger: String,
    onTrigger: (String) -> Unit,
    procedure: String,
    onProcedure: (String) -> Unit,
    verification: String,
    onVerification: (String) -> Unit,
    boundary: String,
    onBoundary: (String) -> Unit,
    failure: String,
    onFailure: (String) -> Unit,
    toolSchemas: String,
    onToolSchemas: (String) -> Unit,
) {
    listOf(
        Triple(trigger, onTrigger, R.string.learning_curator_trigger_field),
        Triple(procedure, onProcedure, R.string.learning_curator_procedure_field),
        Triple(verification, onVerification, R.string.learning_curator_verification_field),
        Triple(boundary, onBoundary, R.string.learning_curator_boundary_field),
        Triple(failure, onFailure, R.string.learning_curator_failure_field),
        Triple(toolSchemas, onToolSchemas, R.string.learning_curator_tool_schemas_field),
    ).forEach { (value, update, label) ->
        OutlinedTextField(
            value = value,
            onValueChange = update,
            label = { Text(stringResource(label)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CuratorScaffold(
    title: String,
    refresh: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = refresh) { Icon(HugeIcons.Refresh01, null) }
                },
                scrollBehavior = scroll,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        content = content,
    )
}

@Composable
private fun CuratorSafetyCard() = Card {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(HugeIcons.Shield01, null)
        Column {
            Text(stringResource(R.string.learning_curator_safety_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.learning_curator_safety_desc))
        }
    }
}

@Composable
private fun CuratorIdentityCard(detail: CuratorReviewDetail) = Card {
    val item = detail.summary
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.learning_curator_candidate_identity), fontWeight = FontWeight.SemiBold)
        Text(item.candidateId)
        Text(item.candidateSha256)
        Text(item.operation.name, style = MaterialTheme.typography.titleMedium)
        Text("${item.state} · r${item.stateVersion}")
        Text(stringResource(R.string.learning_curator_counts, item.sourceCount, item.evidenceCount, item.diffTargetCount))
        Text("updatedAtMs=${item.updatedAtMs}")
    }
}

@Composable
private fun CuratorSourceFenceCard(detail: CuratorReviewDetail) = ReceiptCard(
    stringResource(R.string.learning_curator_exact_sources),
) {
    detail.candidate?.sources?.forEachIndexed { index, source ->
        if (index > 0) Text("—")
        Text("policyId=${source.policyId}")
        Text("scope=${source.scope.kind.name}:${source.scope.storageId}")
        Text("expectedRevision=${source.expectedRevision}")
        Text("baseHash=${source.baseHash}")
    }
}

@Composable
private fun CuratorEvidenceFenceCard(detail: CuratorReviewDetail) = ReceiptCard(
    stringResource(R.string.learning_curator_evidence_receipts),
) {
    detail.candidate?.evidence?.forEachIndexed { index, evidence ->
        if (index > 0) Text("—")
        Text("evidenceId=${evidence.evidenceId}")
        Text("scope=${evidence.scope.kind.name}:${evidence.scope.storageId}")
        Text("sourceRevision=${evidence.sourceRevision}")
        Text("integritySha256=${evidence.integritySha256}")
    }
}

@Composable
private fun CuratorFieldDiffCard(detail: CuratorReviewDetail) = ReceiptCard(
    stringResource(R.string.learning_curator_field_diffs),
) {
    detail.candidate?.diffs?.forEachIndexed { targetIndex, target ->
        if (targetIndex > 0) Text("——")
        Text("targetPolicyId=${target.targetPolicyId}", fontWeight = FontWeight.SemiBold)
        target.fields.forEach { diff ->
            Text(diff.field.name)
            Text("${stringResource(R.string.learning_curator_before_hash)}: ${diff.beforeSha256}")
            Text("${stringResource(R.string.learning_curator_after_value)}:")
            Text(diff.afterValue)
            Text("${stringResource(R.string.learning_curator_after_hash)}: ${diff.afterSha256}")
        }
    }
}

@Composable
private fun CuratorRuntimeStateCard(detail: CuratorReviewDetail) = ReceiptCard(
    stringResource(R.string.learning_curator_runtime_state),
) {
    val summary = detail.summary
    Text("state=${summary.state}")
    Text("stateVersion=${summary.stateVersion}")
    Text("hasApplyPlan=${summary.hasApplyPlan}")
    Text("${stringResource(R.string.learning_curator_conflict_code)}=${summary.conflictCode ?: "NONE"}")
    val plan = detail.applyPlan
    if (plan == null) {
        Text(stringResource(R.string.learning_curator_no_apply_plan))
    } else {
        Text(stringResource(R.string.learning_curator_apply_plan), fontWeight = FontWeight.SemiBold)
        Text("planId=${plan.planId}")
        Text("operation=${plan.operation.name}")
        Text("mutations=${plan.mutations.size}")
        plan.mutations.forEachIndexed { index, mutation ->
            Text(
                "mutation[$index]=${mutation.kind.name} " +
                    "${mutation.before?.policyId ?: "NONE"}->${mutation.after?.policyId ?: "NONE"}",
            )
        }
        plan.lineage.forEach { edge ->
            Text("plannedLineage=${edge.parentPolicyId}->${edge.childPolicyId} ${edge.relation.name}")
        }
        Text("rollback.applyPlanId=${plan.rollback.applyPlanId}")
        Text("rollback.expectedHeads=${plan.rollback.expectedAppliedHeads.size}")
        plan.rollback.expectedAppliedHeads.forEach { head ->
            Text("rollbackHead=${head.policyId} r${head.expectedRevision} ${head.baseHash}")
        }
        Text("rollback.mutations=${plan.rollback.mutations.size}")
        Text("rollback.lineageToRemove=${plan.rollback.lineageToRemove.size}")
        plan.rollback.lineageToRemove.forEach { edge ->
            Text("rollbackLineage=${edge.parentPolicyId}->${edge.childPolicyId} ${edge.relation.name}")
        }
    }
}

@Composable
private fun CuratorRevisionReceiptCard(detail: CuratorReviewDetail) = ReceiptCard(
    stringResource(R.string.learning_curator_revision_receipts),
) {
    Text(stringResource(R.string.learning_curator_revisions, detail.revisions.size))
    detail.revisions.forEachIndexed { index, revision ->
        if (index > 0) Text("—")
        Text("candidateId=${revision.candidateId}")
        Text("stateVersion=${revision.stateVersion} state=${revision.state}")
        Text("previousStateVersion=${revision.previousStateVersion ?: "NONE"}")
        Text("candidateSha256=${revision.candidateSha256}")
        Text("applyPlanId=${revision.applyPlanId ?: "NONE"}")
        Text("reason=${revision.reasonCode} actor=${revision.actor}")
        Text("createdAtMs=${revision.createdAtMs}")
    }
}

@Composable
private fun CuratorLineageReceiptCard(detail: CuratorReviewDetail) = ReceiptCard(
    stringResource(R.string.learning_curator_lineage_edges),
) {
    Text(stringResource(R.string.learning_curator_lineage, detail.lineage.size))
    detail.lineage.forEachIndexed { index, edge ->
        if (index > 0) Text("—")
        Text("candidateId=${edge.candidateId}")
        Text("applyPlanId=${edge.applyPlanId}")
        Text("relation=${edge.relationType} active=${edge.active}")
        Text("parent=${edge.parentPolicyId} r${edge.parentRevision}")
        Text("parentArtifact=${edge.parentArtifactSha256}")
        Text("child=${edge.childPolicyId} r${edge.childRevision}")
        Text("childArtifact=${edge.childArtifactSha256}")
        Text("stateVersion=${edge.stateVersion}")
        Text("createdAtMs=${edge.createdAtMs} updatedAtMs=${edge.updatedAtMs}")
    }
}

@Composable
private fun ReceiptCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) = Card {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun CuratorActionCard(
    detail: CuratorReviewDetail,
    busy: Boolean,
    positiveActionsEnabled: Boolean,
    onAction: (CuratorAction) -> Unit,
) = Card {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detail.summary.state == "PROPOSED") {
            OutlinedButton(
                onClick = { onAction(CuratorAction.Approve(detail)) },
                enabled = positiveActionsEnabled && !busy,
            ) {
                Text(stringResource(R.string.learning_curator_approve))
            }
        }
        if (detail.summary.state in setOf("PROPOSED", "APPROVED")) {
            OutlinedButton(onClick = { onAction(CuratorAction.Reject(detail)) }, enabled = !busy) {
                Text(stringResource(R.string.learning_curator_reject))
            }
        }
        if (detail.summary.state == "APPROVED") {
            OutlinedButton(
                onClick = { onAction(CuratorAction.Apply(detail)) },
                enabled = positiveActionsEnabled && !busy,
            ) {
                Text(stringResource(R.string.learning_curator_apply))
            }
        }
        if (detail.summary.state == "APPLIED" && detail.applyPlan != null) {
            OutlinedButton(onClick = { onAction(CuratorAction.Rollback(detail)) }, enabled = !busy) {
                Text(stringResource(R.string.learning_curator_rollback))
            }
        }
        if (detail.summary.state in setOf("PROPOSED", "APPROVED", "REJECTED", "APPLY_CONFLICT", "ROLLBACK_CONFLICT", "ROLLED_BACK")) {
            OutlinedButton(onClick = { onAction(CuratorAction.Archive(detail)) }, enabled = !busy) {
                Text(stringResource(R.string.learning_curator_archive))
            }
        }
        if (!positiveActionsEnabled && detail.summary.state in setOf("PROPOSED", "APPROVED")) {
            Text(stringResource(R.string.learning_positive_actions_disabled))
        }
    }
}

private sealed interface CuratorAction {
    val detail: CuratorReviewDetail
    data class Approve(override val detail: CuratorReviewDetail) : CuratorAction
    data class Reject(override val detail: CuratorReviewDetail) : CuratorAction
    data class Apply(override val detail: CuratorReviewDetail) : CuratorAction
    data class Rollback(override val detail: CuratorReviewDetail) : CuratorAction
    data class Archive(override val detail: CuratorReviewDetail) : CuratorAction
}

@Composable
private fun CuratorAction.confirmText(): String = when (this) {
    is CuratorAction.Approve -> stringResource(R.string.learning_curator_confirm_approve)
    is CuratorAction.Reject -> stringResource(R.string.learning_curator_confirm_reject)
    is CuratorAction.Apply -> stringResource(R.string.learning_curator_confirm_apply)
    is CuratorAction.Rollback -> stringResource(R.string.learning_curator_confirm_rollback)
    is CuratorAction.Archive -> stringResource(R.string.learning_curator_confirm_archive)
}

private fun CuratorReviewFeedback.message(): String = when (this) {
    is CuratorReviewFeedback.Applied -> state
    is CuratorReviewFeedback.Proposed -> "PROPOSED: $candidateId"
    CuratorReviewFeedback.Duplicate -> "DUPLICATE"
    is CuratorReviewFeedback.Conflict -> reason
}
