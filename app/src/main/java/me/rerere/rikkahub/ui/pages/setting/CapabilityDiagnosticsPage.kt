package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.AndroidGnssObservationSource
import me.rerere.rikkahub.data.ai.tools.local.GnssObservationResult
import me.rerere.rikkahub.data.ai.tools.local.GnssPreflightSnapshot
import me.rerere.rikkahub.data.ai.tools.local.PermissionPrecision
import me.rerere.rikkahub.data.ai.tools.local.toJson
import me.rerere.rikkahub.data.capability.ApprovalPolicy
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityDescriptor
import me.rerere.rikkahub.data.capability.CapabilityRequirement
import me.rerere.rikkahub.data.capability.ImplementationState
import me.rerere.rikkahub.data.capability.RiskLevel
import me.rerere.rikkahub.data.permissions.PermissionInventory
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticFix
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticItem
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticStatus
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticsProvider
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticsSnapshot
import me.rerere.rikkahub.diagnostics.GnssDiagnosticUiState
import me.rerere.rikkahub.diagnostics.GnssDiagnosticsController
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

@Composable
fun CapabilityDiagnosticsPage(conversationId: String? = null) {
    val context = LocalContext.current
    val runtimeProvider = koinInject<RuntimeDiagnosticsProvider>()
    val gnssSource = koinInject<AndroidGnssObservationSource>()
    val scope = rememberCoroutineScope()
    val gnssController = remember(gnssSource, scope) {
        GnssDiagnosticsController(gnssSource, scope)
    }
    val gnssState by gnssController.state.collectAsState()
    var gnssPreflight by remember(gnssSource) { mutableStateOf(gnssSource.preflight()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val capabilities = CapabilityCatalog.allCapabilities().sortedBy { it.id.ordinal }
    val permissionRows = PermissionInventory.capabilityStatusRows(context)
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var runtimeSnapshot by remember(conversationId) {
        mutableStateOf<RuntimeDiagnosticsSnapshot?>(null)
    }
    var runtimeError by remember(conversationId) { mutableStateOf<String?>(null) }

    LaunchedEffect(conversationId, refreshGeneration) {
        runtimeError = null
        runtimeSnapshot = runCatching { runtimeProvider.refresh(conversationId) }
            .onFailure { runtimeError = it.message ?: it.javaClass.simpleName }
            .getOrNull()
    }
    DisposableEffect(gnssController) {
        onDispose(gnssController::close)
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Capability Diagnostics") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { refreshGeneration++ }) {
                        Icon(HugeIcons.Refresh, contentDescription = "刷新运行状态")
                    }
                    IconButton(
                        enabled = runtimeSnapshot != null,
                        onClick = {
                            runtimeSnapshot?.let { shareRuntimeDiagnostics(context, it) }
                        },
                    ) {
                        Icon(HugeIcons.Share01, contentDescription = "导出脱敏 JSON")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Runtime Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            when {
                runtimeError != null -> item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = "读取运行状态失败：$runtimeError",
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                runtimeSnapshot == null -> item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                else -> {
                    items(runtimeSnapshot!!.items, key = { "runtime:${it.id}" }) { item ->
                        RuntimeDiagnosticCard(item)
                    }
                }
            }

            item {
                GnssDiagnosticCard(
                    state = gnssState,
                    preflight = gnssPreflight,
                    onStart = {
                        gnssPreflight = gnssSource.preflight()
                        gnssController.start()
                    },
                    onCancel = gnssController::cancel,
                    onCopy = { result -> copyGnssDiagnostic(context, result) },
                    onShare = { result -> shareGnssDiagnostic(context, result) },
                )
            }

            // Summary header
            item {
                val implemented = capabilities.count { it.implementationState == ImplementationState.Implemented }
                val reserved = capabilities.count { it.implementationState == ImplementationState.Reserved }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Capability Catalog Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "$implemented implemented · $reserved reserved · ${capabilities.size} total",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Capabilities grouped by implementation state
            val groups = capabilities.groupBy { it.implementationState }
            for ((state, caps) in groups.entries.sortedBy { it.key.ordinal }) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (state) {
                            ImplementationState.Implemented -> "✅ Implemented"
                            ImplementationState.Reserved -> "🔒 Reserved (not yet implemented)"
                            ImplementationState.SystemRestricted -> "⚠️ System Restricted"
                            ImplementationState.ExternalBridgeRequired -> "🔗 External Bridge Required"
                            ImplementationState.ManualOnly -> "👤 Manual Only"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                items(caps, key = { it.id }) { cap ->
                    CapabilityCard(
                        cap = cap,
                        permissionRow = permissionRows.firstOrNull { it.id == "capability:${cap.id.name}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun GnssDiagnosticCard(
    state: GnssDiagnosticUiState,
    preflight: GnssPreflightSnapshot,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onCopy: (GnssObservationResult) -> Unit,
    onShare: (GnssObservationResult) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.gnss_diagnostic_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gnss_diagnostic_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.gnss_diagnostic_preflight,
                    stringResource(
                        when (preflight.permissionPrecision) {
                            PermissionPrecision.NONE -> R.string.gnss_diagnostic_precision_none
                            PermissionPrecision.COARSE -> R.string.gnss_diagnostic_precision_coarse
                            PermissionPrecision.FINE -> R.string.gnss_diagnostic_precision_fine
                        },
                    ),
                    stringResource(
                        if (preflight.locationEnabled) {
                            R.string.gnss_diagnostic_state_on
                        } else {
                            R.string.gnss_diagnostic_state_off
                        },
                    ),
                    when {
                        !preflight.gpsProviderExists ->
                            stringResource(R.string.gnss_diagnostic_state_unavailable)
                        preflight.gpsProviderEnabled ->
                            stringResource(R.string.gnss_diagnostic_state_enabled)
                        else -> stringResource(R.string.gnss_diagnostic_state_disabled)
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                GnssDiagnosticUiState.Idle -> Text(stringResource(R.string.gnss_diagnostic_idle))
                GnssDiagnosticUiState.Cancelled -> Text(stringResource(R.string.gnss_diagnostic_cancelled))
                is GnssDiagnosticUiState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(
                            R.string.gnss_diagnostic_running,
                            (state.remainingMs + 999L) / 1_000L,
                        ),
                    )
                }
                is GnssDiagnosticUiState.Completed -> GnssDiagnosticResult(state.result)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.gnss_diagnostic_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state is GnssDiagnosticUiState.Running) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.gnss_diagnostic_cancel))
                    }
                } else {
                    TextButton(onClick = onStart) {
                        Text(stringResource(R.string.gnss_diagnostic_start))
                    }
                }
                val result = (state as? GnssDiagnosticUiState.Completed)?.result
                if (result != null) {
                    TextButton(onClick = { onCopy(result) }) {
                        Text(stringResource(R.string.gnss_diagnostic_copy_json))
                    }
                    TextButton(onClick = { onShare(result) }) {
                        Text(stringResource(R.string.gnss_diagnostic_share_json))
                    }
                }
            }
        }
    }
}

@Composable
private fun GnssDiagnosticResult(result: GnssObservationResult) {
    when (result) {
        is GnssObservationResult.Failure -> {
            Text(
                text = stringResource(R.string.gnss_diagnostic_error, result.code, result.message),
                color = MaterialTheme.colorScheme.error,
            )
            Text(result.recovery, style = MaterialTheme.typography.bodySmall)
        }
        is GnssObservationResult.Success -> {
            Text(
                stringResource(
                    R.string.gnss_diagnostic_counts,
                    result.satellitesVisible,
                    result.satellitesUsedInFix,
                ),
                fontWeight = FontWeight.SemiBold,
            )
            result.constellations.forEach { (name, counts) ->
                Text(
                    text = stringResource(
                        R.string.gnss_diagnostic_constellation_counts,
                        name,
                        counts.visible,
                        counts.usedInFix,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result.warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

private fun copyGnssDiagnostic(context: Context, result: GnssObservationResult) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText("RikkaHub GNSS diagnostics", result.toJson().toString()),
    )
    Toast.makeText(context, context.getString(R.string.gnss_diagnostic_copied), Toast.LENGTH_SHORT).show()
}

private fun shareGnssDiagnostic(context: Context, result: GnssObservationResult) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.gnss_diagnostic_title))
        putExtra(Intent.EXTRA_TEXT, result.toJson().toString())
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.gnss_diagnostic_share_title)),
        )
    }.onFailure {
        Toast.makeText(context, R.string.gnss_diagnostic_share_failed, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun RuntimeDiagnosticCard(item: RuntimeDiagnosticItem) {
    val context = LocalContext.current
    val statusColor = when (item.status) {
        RuntimeDiagnosticStatus.READY -> Color(0xFF2E7D32)
        RuntimeDiagnosticStatus.SERVICE_OFFLINE -> MaterialTheme.colorScheme.error
        RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED -> Color(0xFFF57C00)
        RuntimeDiagnosticStatus.OEM_RESTRICTED -> Color(0xFFF57C00)
        RuntimeDiagnosticStatus.NOT_SUPPORTED -> MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = runtimeStatusLabel(item.status),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.fix?.let { fix ->
                TextButton(
                    onClick = {
                        if (!openRuntimeDiagnosticFix(context, fix)) {
                            Toast.makeText(context, "对应应用或系统页面不可用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("打开设置")
                }
            }
        }
    }
}

private fun runtimeStatusLabel(status: RuntimeDiagnosticStatus): String = when (status) {
    RuntimeDiagnosticStatus.READY -> "READY"
    RuntimeDiagnosticStatus.SERVICE_OFFLINE -> "OFFLINE"
    RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED -> "NEEDS AUTH"
    RuntimeDiagnosticStatus.OEM_RESTRICTED -> "OEM LIMITED"
    RuntimeDiagnosticStatus.NOT_SUPPORTED -> "NOT SUPPORTED"
}

private fun shareRuntimeDiagnostics(context: Context, snapshot: RuntimeDiagnosticsSnapshot) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "RikkaHub runtime diagnostics")
        putExtra(Intent.EXTRA_TEXT, snapshot.toRedactedJson())
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "导出脱敏诊断 JSON"))
    }.onFailure {
        Toast.makeText(context, "没有可用的分享目标", Toast.LENGTH_SHORT).show()
    }
}

private fun openRuntimeDiagnosticFix(context: Context, fix: RuntimeDiagnosticFix): Boolean {
    val intent = when (fix) {
        RuntimeDiagnosticFix.ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        RuntimeDiagnosticFix.NOTIFICATION_LISTENER_SETTINGS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        RuntimeDiagnosticFix.NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        RuntimeDiagnosticFix.BATTERY_OPTIMIZATION_SETTINGS ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        RuntimeDiagnosticFix.APPLICATION_DETAILS -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        RuntimeDiagnosticFix.INPUT_METHOD_SETTINGS -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        RuntimeDiagnosticFix.SHIZUKU_APP ->
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        RuntimeDiagnosticFix.TERMUX_APP ->
            context.packageManager.getLaunchIntentForPackage("com.termux")
    } ?: return false
    return runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

@Composable
private fun CapabilityCard(
    cap: CapabilityDescriptor,
    permissionRow: me.rerere.rikkahub.data.permissions.PermissionInventory.Row?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row: name + badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = riskLevelIcon(cap.riskLevel),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = riskLevelColor(cap.riskLevel),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = cap.id.name.humanize(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                RiskBadge(cap.riskLevel)
                Spacer(Modifier.width(4.dp))
                ApprovalBadge(cap.approvalPolicy)
            }

            Spacer(Modifier.height(6.dp))

            // Requirements
            if (cap.requirements.isNotEmpty()) {
                Text(
                    text = "Requirements:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cap.requirements.forEach { req ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    ) {
                        val met = permissionRow?.status == me.rerere.rikkahub.data.permissions.PermissionInventory.Status.GRANTED
                        Icon(
                            imageVector = if (met) HugeIcons.Tick01 else HugeIcons.Alert01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (met) Color(0xFF4CAF50) else Color(0xFFFF5252),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = requirementDescription(req),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    text = "No special requirements",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Allowed origins
            Text(
                text = "Allowed from: ${cap.allowedOrigins.joinToString(", ") { it.name }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Lock/foreground constraints
            if (cap.requiresUnlockedDevice || cap.requiresForegroundApp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cap.requiresUnlockedDevice) {
                        Text(
                            text = "🔓 Device must be unlocked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cap.requiresForegroundApp) {
                        Text(
                            text = if (cap.requiresUnlockedDevice) " · " else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "📱 App must be in foreground",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: RiskLevel) {
    val (text, bg) = when (risk) {
        RiskLevel.Low -> "Low" to Color(0xFF4CAF50).copy(alpha = 0.15f)
        RiskLevel.Medium -> "Med" to Color(0xFFFFC107).copy(alpha = 0.15f)
        RiskLevel.High -> "High" to Color(0xFFFF9800).copy(alpha = 0.15f)
        RiskLevel.Critical -> "Crit" to Color(0xFFFF5252).copy(alpha = 0.15f)
    }
    val textColor = when (risk) {
        RiskLevel.Low -> Color(0xFF4CAF50)
        RiskLevel.Medium -> Color(0xFFFFC107)
        RiskLevel.High -> Color(0xFFFF9800)
        RiskLevel.Critical -> Color(0xFFFF5252)
    }
    Box(
        modifier = Modifier
            .background(bg, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
    }
}

@Composable
private fun ApprovalBadge(policy: ApprovalPolicy) {
    val (text, bg) = when (policy) {
        ApprovalPolicy.AlwaysAsk -> "Ask" to MaterialTheme.colorScheme.errorContainer
        ApprovalPolicy.AskOnRemote -> "Remote" to MaterialTheme.colorScheme.tertiaryContainer
        ApprovalPolicy.Default -> "Free" to MaterialTheme.colorScheme.primaryContainer
    }
    Box(
        modifier = Modifier
            .background(bg, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun riskLevelIcon(risk: RiskLevel) = when (risk) {
    RiskLevel.Low -> HugeIcons.Database02
    RiskLevel.Medium -> HugeIcons.Shield01
    RiskLevel.High -> HugeIcons.Alert01
    RiskLevel.Critical -> HugeIcons.Alert01
}

private fun riskLevelColor(risk: RiskLevel) = when (risk) {
    RiskLevel.Low -> Color(0xFF4CAF50)
    RiskLevel.Medium -> Color(0xFFFFC107)
    RiskLevel.High -> Color(0xFFFF9800)
    RiskLevel.Critical -> Color(0xFFFF5252)
}

private fun requirementDescription(req: CapabilityRequirement): String = when (req) {
    is CapabilityRequirement.ManifestPermission -> "Manifest: ${req.permission.substringAfterLast('.')}"
    is CapabilityRequirement.RuntimePermission -> buildString {
        append("Runtime: ${req.permission.substringAfterLast('.')}")
        if (req.minSdk > 1 || req.maxSdk < Int.MAX_VALUE) {
            append(" (SDK ${req.minSdk}..${if (req.maxSdk == Int.MAX_VALUE) "latest" else req.maxSdk})")
        }
    }
    is CapabilityRequirement.SpecialAccess -> "Special: ${req.type.name}"
    is CapabilityRequirement.EnabledService -> "Service: ${req.component.shortClassName.substringAfterLast('.')}"
    is CapabilityRequirement.Role -> "Role: ${req.roleName}"
    is CapabilityRequirement.ExternalBridge -> "Bridge: ${req.type.name}"
    is CapabilityRequirement.MediaProjectionConsent -> "MediaProjection consent"
    is CapabilityRequirement.VpnConsent -> "VPN consent"
}

private fun String.humanize(): String {
    return this.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .replace(Regex("([A-Z])([A-Z][a-z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
}
