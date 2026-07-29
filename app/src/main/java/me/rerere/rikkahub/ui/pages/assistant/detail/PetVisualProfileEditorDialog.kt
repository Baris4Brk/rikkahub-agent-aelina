package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.overlay.DesktopPetService
import me.rerere.rikkahub.pet.profile.PetProfileIdlePoolDocument
import me.rerere.rikkahub.pet.profile.PetProfileRepository
import me.rerere.rikkahub.pet.profile.PetVisualProfileOverride

/** Safe visual editor: it exposes semantic choices only, never paths, JSON, scripts or classes. */
@Composable
fun PetVisualProfileEditorDialog(
    packageId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(packageId) {
        PetProfileRepository(
            petsRoot = appContext.filesDir.resolve("pets"),
            overridesRoot = appContext.filesDir.resolve("pet_profile_overrides"),
        )
    }
    val existing = remember(packageId) { runCatching { repository.loadOverride(packageId) }.getOrNull() }
    var headAction by remember { mutableStateOf(existing?.touchMappings?.get("head") ?: CorePetActions.WAVE.value) }
    var bodyAction by remember { mutableStateOf(existing?.touchMappings?.get("body") ?: CorePetActions.REVIEW.value) }
    var feetAction by remember { mutableStateOf(existing?.touchMappings?.get("feet") ?: CorePetActions.JUMP.value) }
    var greetingAction by remember {
        mutableStateOf(existing?.aliases?.get(CorePetActions.DIALOGUE_GREETING.value) ?: CorePetActions.WAVE.value)
    }
    var speakingFallback by remember {
        mutableStateOf(existing?.fallbacks?.get(CorePetActions.SPEAKING.value)?.firstOrNull() ?: CorePetActions.WAVE.value)
    }
    var idlePoolEnabled by remember { mutableStateOf(existing?.idlePool != null) }
    var status by remember { mutableStateOf<String?>(null) }

    @Composable
    fun optionRow(title: String, selected: String, onSelect: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                semanticOptions.forEach { option ->
                    TextButton(onClick = { onSelect(option.id) }) {
                        Text((if (option.id == selected) "● " else "○ ") + option.label)
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("桌宠视觉编排") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "这里只能选择本地视觉语义；不会写入 ZIP，也不能指定路径、脚本、网络资源或任意动作 ID。",
                    style = MaterialTheme.typography.bodySmall,
                )
                optionRow("摸头动作", headAction) { headAction = it }
                optionRow("摸身体动作", bodyAction) { bodyAction = it }
                optionRow("摸脚动作", feetAction) { feetAction = it }
                optionRow("问候语义", greetingAction) { greetingAction = it }
                optionRow("说话缺失动作时的回退", speakingFallback) { speakingFallback = it }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Idle Pool")
                        Text("仅在真正空闲、亮屏且非省电/低电量时生效", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = idlePoolEnabled, onCheckedChange = { idlePoolEnabled = it })
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val override = PetVisualProfileOverride(
                    touchMappings = mapOf("head" to headAction, "body" to bodyAction, "feet" to feetAction),
                    aliases = mapOf(CorePetActions.DIALOGUE_GREETING.value to greetingAction),
                    fallbacks = mapOf(
                        CorePetActions.SPEAKING.value to listOf(
                            speakingFallback,
                            CorePetActions.REVIEW.value,
                            CorePetActions.IDLE.value,
                        ).distinct(),
                    ),
                    idlePool = if (idlePoolEnabled) {
                        PetProfileIdlePoolDocument(
                            weights = mapOf(
                                CorePetActions.IDLE.value to 70,
                                CorePetActions.REVIEW.value to 20,
                                CorePetActions.WAVE.value to 10,
                            ),
                        )
                    } else {
                        null
                    },
                )
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { repository.saveOverride(packageId, override) }
                    }
                    result.onSuccess {
                        DesktopPetService.reload(appContext)
                        onDismiss()
                    }.onFailure { status = "无法保存安全视觉覆盖" }
                }
            }) { Text("保存视觉设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private data class SemanticOption(val id: String, val label: String)

private val semanticOptions = listOf(
    SemanticOption(CorePetActions.WAVE.value, "挥手"),
    SemanticOption(CorePetActions.JUMP.value, "跳跃"),
    SemanticOption(CorePetActions.REVIEW.value, "思考"),
)
