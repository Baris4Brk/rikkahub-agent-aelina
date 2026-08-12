package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.memory.dreaming.diagnostics.DreamObserverRunDiagnostic
import me.rerere.rikkahub.memory.dreaming.diagnostics.DreamObserverScopeDiagnostic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MemoryObserverDiagnosticsTab(
    diagnostic: DreamObserverScopeDiagnostic?,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.memory_v2_observer_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.memory_v2_observer_developer_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onRefresh) {
                    Text(stringResource(R.string.memory_v2_observer_refresh))
                }
            }
        }

        if (diagnostic == null) {
            item {
                Text(
                    text = stringResource(R.string.memory_v2_observer_no_state),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            item { ObserverScopeCard(diagnostic) }
            item {
                Text(
                    text = stringResource(R.string.memory_v2_observer_recent_runs),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (diagnostic.recentRuns.isEmpty()) {
                item { Text(stringResource(R.string.memory_v2_observer_no_runs)) }
            } else {
                items(
                    count = diagnostic.recentRuns.size,
                    key = { index ->
                        val run = diagnostic.recentRuns[index]
                        "$index:${run.createdAtMs}:${run.mode}:${run.attempt}"
                    },
                ) { index ->
                    ObserverRunCard(diagnostic.recentRuns[index])
                }
            }
        }
    }
}

@Composable
private fun ObserverScopeCard(diagnostic: DreamObserverScopeDiagnostic) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_status),
                diagnostic.status.name,
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_memory_epoch),
                diagnostic.memoryEpoch.toString(),
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_checkpoint),
                diagnostic.observerCheckpointEpoch.toString(),
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_pending_epochs),
                diagnostic.pendingEpochCount.toString(),
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_last_reason),
                diagnostic.lastReasonCode?.name ?: EM_DASH,
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_active_lease),
                diagnostic.activeRunLeaseUntilMs?.let(::formatDiagnosticTime) ?: EM_DASH,
            )
        }
    }
}

@Composable
private fun ObserverRunCard(run: DreamObserverRunDiagnostic) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${run.mode.name} · ${run.status.name}",
                style = MaterialTheme.typography.titleSmall,
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_memory_epoch),
                run.baseMemoryEpoch.toString(),
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_checkpoint),
                "${run.baseObserverCheckpointEpoch} → ${run.checkpointEpoch}",
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_last_reason),
                run.failureCode?.name ?: EM_DASH,
            )
            DiagnosticValue(
                stringResource(R.string.memory_v2_observer_active_lease),
                run.leaseUntilMs?.let(::formatDiagnosticTime) ?: EM_DASH,
            )
        }
    }
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatDiagnosticTime(epochMs: Long): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )

private const val EM_DASH = "—"
