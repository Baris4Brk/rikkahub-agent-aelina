package me.rerere.rikkahub.ui.pages.setting

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.quickcapture.DEFAULT_QUICK_CAPTURE_PROMPT
import me.rerere.rikkahub.quickcapture.QuickCaptureAreaMode
import me.rerere.rikkahub.quickcapture.QuickCaptureBackendPreference
import me.rerere.rikkahub.quickcapture.QuickCaptureBubbleEdge
import me.rerere.rikkahub.quickcapture.QuickCaptureProjectionState
import me.rerere.rikkahub.quickcapture.QuickCaptureStartEligibility
import me.rerere.rikkahub.quickcapture.QuickCaptureTargetMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingQuickCapturePage(
    vm: QuickCaptureSettingsViewModel = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val eligibility by vm.eligibility.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val projectionState by vm.projectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val quick = settings.quickCaptureSettings.normalized()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var prompt by remember(quick.prompt) { mutableStateOf(quick.prompt) }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            vm.installProjection(result.resultCode, result.data!!)
        }
    }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshEligibility() }

    DisposableEffect(vm) {
        onDispose(vm::clearPreview)
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.quick_capture_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("summary") {
                Text(
                    stringResource(R.string.quick_capture_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item("enabled") {
                QuickCaptureCard(stringResource(R.string.quick_capture_section_floating_button)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.quick_capture_enable),
                        description = stringResource(R.string.quick_capture_enable_description),
                        checked = quick.enabled,
                        onChange = vm::setEnabled,
                    )
                    notice?.let { noticeText ->
                        Text(
                            text = stringResource(R.string.quick_capture_status, noticeText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(onClick = vm::clearNotice) {
                            Text(stringResource(R.string.quick_capture_dismiss))
                        }
                    }
                }
            }
            item("target") {
                QuickCaptureCard(stringResource(R.string.quick_capture_section_target)) {
                    Text(stringResource(R.string.quick_capture_target_description))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = quick.targetMode == QuickCaptureTargetMode.FOLLOW_SYSTEM_ASSISTANT,
                            onClick = {
                                vm.update {
                                    it.copy(targetMode = QuickCaptureTargetMode.FOLLOW_SYSTEM_ASSISTANT)
                                }
                            },
                            label = { Text(stringResource(R.string.quick_capture_follow_system_assistant)) },
                        )
                        FilterChip(
                            selected = quick.targetMode == QuickCaptureTargetMode.FIXED_ASSISTANT,
                            onClick = {
                                vm.update { it.copy(targetMode = QuickCaptureTargetMode.FIXED_ASSISTANT) }
                            },
                            label = { Text(stringResource(R.string.quick_capture_fixed_assistant)) },
                        )
                    }
                    if (quick.targetMode == QuickCaptureTargetMode.FIXED_ASSISTANT) {
                        val candidates = settings.assistants.filter { it.privilegedConversationId != null }
                        if (candidates.isEmpty()) {
                            Text(stringResource(R.string.quick_capture_no_second_user_assistant))
                        } else {
                            candidates.forEach { assistant ->
                                FilterChip(
                                    selected = quick.fixedAssistantId == assistant.id,
                                    onClick = { vm.update { it.copy(fixedAssistantId = assistant.id) } },
                                    label = { Text(assistant.name) },
                                )
                            }
                        }
                    }
                    TargetStatus(eligibility)
                    TextButton(onClick = vm::refreshEligibility) {
                        Text(stringResource(R.string.quick_capture_refresh_target))
                    }
                }
            }
            item("prompt") {
                QuickCaptureCard(stringResource(R.string.quick_capture_section_prompt)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.quick_capture_default_prompt)) },
                        minLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.update { it.copy(prompt = prompt) } }) {
                            Text(stringResource(R.string.quick_capture_save_prompt))
                        }
                        OutlinedButton(onClick = {
                            prompt = DEFAULT_QUICK_CAPTURE_PROMPT
                            vm.update { it.copy(prompt = DEFAULT_QUICK_CAPTURE_PROMPT) }
                        }) { Text(stringResource(R.string.quick_capture_restore_default)) }
                    }
                    SettingsSwitchRow(
                        title = stringResource(R.string.quick_capture_auto_send),
                        description = stringResource(R.string.quick_capture_auto_send_description),
                        checked = quick.autoSend,
                        onChange = { enabled -> vm.update { it.copy(autoSend = enabled) } },
                    )
                }
            }
            item("capture") {
                QuickCaptureCard(stringResource(R.string.quick_capture_section_capture)) {
                    Text(stringResource(R.string.quick_capture_backend))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickCaptureBackendPreference.entries.forEach { backend ->
                            FilterChip(
                                selected = quick.backend == backend,
                                onClick = { vm.update { it.copy(backend = backend) } },
                                label = { Text(backend.quickCaptureLabel()) },
                            )
                        }
                    }
                    Text(stringResource(R.string.quick_capture_area))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickCaptureAreaMode.entries.forEach { area ->
                            FilterChip(
                                selected = quick.areaMode == area,
                                onClick = { vm.update { it.copy(areaMode = area) } },
                                label = { Text(area.quickCaptureLabel()) },
                            )
                        }
                    }
                    Text(
                        stringResource(
                            if (me.rerere.rikkahub.service.RikkaAccessibilityService.instance != null) {
                                R.string.quick_capture_accessibility_online
                            } else {
                                R.string.quick_capture_accessibility_offline
                            },
                        ),
                    )
                    Text(
                        stringResource(
                            if (Settings.canDrawOverlays(context)) {
                                R.string.quick_capture_overlay_granted
                            } else {
                                R.string.quick_capture_overlay_required
                            },
                        ),
                    )
                    Text(projectionState.quickCaptureProjectionText())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            overlayLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }) { Text(stringResource(R.string.quick_capture_grant_overlay)) }
                        OutlinedButton(onClick = {
                            projectionLauncher.launch(
                                me.rerere.rikkahub.quickcapture.QuickCaptureMediaProjectionService
                                    .capturePermissionIntent(context),
                            )
                        }) { Text(stringResource(R.string.quick_capture_authorize_projection)) }
                        Button(onClick = vm::testCapture) {
                            Text(stringResource(R.string.quick_capture_test_capture))
                        }
                    }
                    preview?.let { captured ->
                        Text(
                            stringResource(R.string.quick_capture_preview_memory_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Image(
                            bitmap = captured.bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.quick_capture_preview_description),
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            contentScale = ContentScale.Fit,
                        )
                        TextButton(onClick = vm::clearPreview) {
                            Text(stringResource(R.string.quick_capture_discard_preview))
                        }
                    }
                }
            }
            item("appearance") {
                QuickCaptureCard(stringResource(R.string.quick_capture_section_appearance)) {
                    Text(stringResource(R.string.quick_capture_size, quick.bubbleSizeDp))
                    Slider(
                        value = quick.bubbleSizeDp.toFloat(),
                        onValueChange = { value -> vm.update { it.copy(bubbleSizeDp = value.roundToInt()) } },
                        valueRange = 40f..80f,
                        steps = 7,
                    )
                    Text(
                        stringResource(
                            R.string.quick_capture_opacity,
                            (quick.bubbleOpacity * 100).roundToInt(),
                        ),
                    )
                    Slider(
                        value = quick.bubbleOpacity,
                        onValueChange = { value -> vm.update { it.copy(bubbleOpacity = value) } },
                        valueRange = 0.35f..1f,
                        steps = 12,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickCaptureBubbleEdge.entries.forEach { edge ->
                            FilterChip(
                                selected = quick.bubbleEdge == edge,
                                onClick = { vm.update { it.copy(bubbleEdge = edge) } },
                                label = { Text(edge.quickCaptureLabel()) },
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.quick_capture_vertical_position,
                            (quick.bubbleYFraction * 100).roundToInt(),
                        ),
                    )
                    Slider(
                        value = quick.bubbleYFraction,
                        onValueChange = { value -> vm.update { it.copy(bubbleYFraction = value) } },
                    )
                }
            }
            item("diagnostics") {
                QuickCaptureCard(stringResource(R.string.quick_capture_section_diagnostics)) {
                    Text(stringResource(R.string.quick_capture_diagnostics_description))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("QuickCapture diagnostics", vm.diagnostics()))
                        },
                    ) { Text(stringResource(R.string.quick_capture_copy_diagnostics)) }
                }
            }
            item("bottom") {
                Text(
                    stringResource(R.string.quick_capture_gesture_help),
                    modifier = Modifier.padding(bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickCaptureCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TargetStatus(eligibility: QuickCaptureStartEligibility?) {
    when (eligibility) {
        is QuickCaptureStartEligibility.Ready -> {
            val target = eligibility.target
            val conversationTitle = if (target.conversationTitle.isBlank()) {
                stringResource(R.string.quick_capture_untitled)
            } else {
                target.conversationTitle
            }
            Text(
                stringResource(
                    R.string.quick_capture_current_assistant,
                    target.assistantName,
                    target.assistantId.toString().take(8),
                ),
            )
            Text(
                stringResource(
                    R.string.quick_capture_second_user_conversation,
                    conversationTitle,
                    target.conversationId.toString().take(8),
                ),
            )
        }
        is QuickCaptureStartEligibility.Blocked -> Text(
            stringResource(R.string.quick_capture_unavailable, eligibility.code),
            color = MaterialTheme.colorScheme.error,
        )
        null -> Text(stringResource(R.string.quick_capture_checking_target))
    }
}

@Composable
private fun QuickCaptureBackendPreference.quickCaptureLabel(): String = when (this) {
    QuickCaptureBackendPreference.AUTO -> stringResource(R.string.quick_capture_backend_auto)
    QuickCaptureBackendPreference.ACCESSIBILITY -> stringResource(R.string.quick_capture_backend_accessibility)
    QuickCaptureBackendPreference.MEDIA_PROJECTION ->
        stringResource(R.string.quick_capture_backend_media_projection)
}

@Composable
private fun QuickCaptureAreaMode.quickCaptureLabel(): String = when (this) {
    QuickCaptureAreaMode.FULL_SCREEN -> stringResource(R.string.quick_capture_area_full_screen)
    QuickCaptureAreaMode.SELECT_REGION -> stringResource(R.string.quick_capture_area_select_region)
}

@Composable
private fun QuickCaptureBubbleEdge.quickCaptureLabel(): String = when (this) {
    QuickCaptureBubbleEdge.LEFT -> stringResource(R.string.quick_capture_edge_left)
    QuickCaptureBubbleEdge.RIGHT -> stringResource(R.string.quick_capture_edge_right)
}

@Composable
private fun QuickCaptureProjectionState.quickCaptureProjectionText(): String = when (this) {
    QuickCaptureProjectionState.NeedsConsent ->
        stringResource(R.string.quick_capture_projection_needs_consent)
    is QuickCaptureProjectionState.Ready ->
        stringResource(R.string.quick_capture_projection_ready, width, height)
    is QuickCaptureProjectionState.Failed ->
        stringResource(R.string.quick_capture_projection_failed, detail)
}
