package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.pet.PetHandoffMode
import me.rerere.rikkahub.pet.assets.AndroidPetImageProbe
import me.rerere.rikkahub.pet.assets.CodexPetPackageImporter
import me.rerere.rikkahub.pet.assets.PetPackageException
import me.rerere.rikkahub.pet.overlay.DesktopPetService

@Composable
fun PetSettingsDialog(
    assistant: Assistant,
    onDismiss: () -> Unit,
    onUpdate: (Assistant, afterUpdate: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(assistant) { mutableStateOf(assistant) }
    var status by remember { mutableStateOf<String?>(null) }
    var replacementUri by remember { mutableStateOf<Uri?>(null) }
    var showProfileEditor by remember { mutableStateOf(false) }

    fun importPackage(uri: Uri, replace: Boolean) {
        scope.launch {
            status = "正在验证资源包…"
            val result = runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    CodexPetPackageImporter(
                        petsRoot = context.filesDir.resolve("pets"),
                        imageProbe = AndroidPetImageProbe,
                    ).import(input, replaceExisting = replace)
                }
            }
            result.onSuccess { installed ->
                draft = draft.copy(petPackageId = installed.manifest.id)
                status = "已导入 ${installed.manifest.displayName}"
                replacementUri = null
            }.onFailure { error ->
                if (error is PetPackageException && error.code == "pet_id_exists" && !replace) {
                    replacementUri = uri
                    status = "同 ID 桌宠已存在，是否替换？"
                } else {
                    status = (error as? PetPackageException)?.code ?: "导入失败"
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPackage(it, false) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第二用户桌宠") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draft.privilegedConversationId == null) {
                    Text("必须先为这个助手配置固定的第二用户会话。", color = MaterialTheme.colorScheme.error)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("启用桌宠")
                        Text("锁屏和熄屏时自动隐藏", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = draft.petEnabled,
                        enabled = draft.privilegedConversationId != null,
                        onCheckedChange = { draft = draft.copy(petEnabled = it) },
                    )
                }
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(if (draft.petPackageId == null) "导入 .codex-pet.zip" else "更换桌宠资源")
                }
                Text("当前：${draft.petPackageId ?: "静态应用占位图"}")
                if (draft.petPackageId != null) {
                    TextButton(onClick = { showProfileEditor = true }) {
                        Text("编辑安全视觉动作")
                    }
                }
                Text("桌宠大小 ${(draft.petScale.coerceIn(0.05f, 2.0f) * 100).roundToInt()}%")
                Slider(
                    value = draft.petScale.coerceIn(0.05f, 2.0f),
                    onValueChange = { draft = draft.copy(petScale = it) },
                    valueRange = 0.05f..2.0f,
                    steps = 38,
                )
                Text("超出屏幕可用范围时会等比例缩小", style = MaterialTheme.typography.bodySmall)
                Text("动画速度 ${draft.petAnimationFps.coerceIn(4, 12)} 帧/秒")
                Slider(
                    value = draft.petAnimationFps.coerceIn(4, 12).toFloat(),
                    onValueChange = { draft = draft.copy(petAnimationFps = it.roundToInt()) },
                    valueRange = 4f..12f,
                    steps = 7,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("空闲动作池")
                        Text("默认关闭；仅在亮屏、真正空闲且非省电/低电量时随机播放", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = draft.petIdlePoolEnabled,
                        onCheckedChange = { draft = draft.copy(petIdlePoolEnabled = it) },
                    )
                }
                OutlinedTextField(
                    value = draft.petSupplement.orEmpty(),
                    onValueChange = { draft = draft.copy(petSupplement = it.take(2_000)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("桌宠补充人物设定") },
                    maxLines = 4,
                )
                Text("触摸区域校准：头部 ${(draft.petHeadBoundary * 100).toInt()}%")
                Slider(
                    value = draft.petHeadBoundary,
                    onValueChange = { value ->
                        draft = draft.copy(
                            petHeadBoundary = value,
                            petBodyBoundary = draft.petBodyBoundary.coerceAtLeast(value + 0.1f),
                        )
                    },
                    valueRange = 0.15f..0.55f,
                )
                Text("身体结束 ${(draft.petBodyBoundary * 100).toInt()}%，以下为脚部")
                Slider(
                    value = draft.petBodyBoundary,
                    onValueChange = { draft = draft.copy(petBodyBoundary = it) },
                    valueRange = (draft.petHeadBoundary + 0.1f)..0.95f,
                )
                Text("转交模式", style = MaterialTheme.typography.labelLarge)
                PetHandoffMode.entries.forEach { mode ->
                    val label = when (mode) {
                        PetHandoffMode.CONFIRM -> "转交前确认（默认）"
                        PetHandoffMode.AUTO -> "自动低优先级转交（每30分钟最多一次，所有工具重新审批）"
                        PetHandoffMode.SUGGEST_ONLY -> "仅提示、不转交"
                    }
                    TextButton(onClick = { draft = draft.copy(petHandoffMode = mode.name) }) {
                        Text((if (draft.petHandoffMode == mode.name) "● " else "○ ") + label)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("开机后尝试恢复（默认关闭）")
                    Switch(
                        checked = draft.petBootRestoreEnabled,
                        onCheckedChange = { draft = draft.copy(petBootRestoreEnabled = it) },
                    )
                }
                if (!Settings.canDrawOverlays(context)) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text("授予悬浮窗权限") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                replacementUri?.let { uri ->
                    Row {
                        TextButton(onClick = { importPackage(uri, true) }) { Text("确认替换") }
                        TextButton(onClick = { replacementUri = null; status = null }) { Text("取消") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = draft.copy(
                    petScale = draft.petScale.coerceIn(0.05f, 2.0f),
                    petAnimationFps = draft.petAnimationFps.coerceIn(4, 12),
                )
                val appContext = context.applicationContext
                onUpdate(saved) {
                    if (saved.petEnabled) {
                        DesktopPetService.start(appContext)
                    } else {
                        DesktopPetService.stop(appContext)
                    }
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    draft.petPackageId?.takeIf { showProfileEditor }?.let { packageId ->
        PetVisualProfileEditorDialog(
            packageId = packageId,
            onDismiss = { showProfileEditor = false },
        )
    }
}
