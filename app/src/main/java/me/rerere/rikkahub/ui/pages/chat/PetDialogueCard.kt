package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.pet.ActivePetDialogue
import me.rerere.rikkahub.pet.PetArchiveResult
import me.rerere.rikkahub.pet.PetDialogueGenerator
import me.rerere.rikkahub.pet.PetDialogueInputKind
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetDialogueTurnDraft
import me.rerere.rikkahub.pet.PetDialogueTurnEntityView
import me.rerere.rikkahub.pet.PetGenerationResult
import me.rerere.rikkahub.pet.PetHandoffCoordinator
import me.rerere.rikkahub.pet.PetHandoffMode
import me.rerere.rikkahub.pet.PetHandoffStatus
import me.rerere.rikkahub.pet.PetPersonaSource
import org.koin.compose.koinInject

@Composable
fun PetDialogueCard(
    assistant: Assistant,
    conversationId: Uuid,
    mainBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!assistant.petEnabled || assistant.privilegedConversationId != conversationId) return
    val repository: PetDialogueRepository = koinInject()
    val generator: PetDialogueGenerator = koinInject()
    val personaSource: PetPersonaSource = koinInject()
    val handoffCoordinator: PetHandoffCoordinator = koinInject()
    val scope = rememberCoroutineScope()
    val assistantId = assistant.id.toString()
    val conversationKey = conversationId.toString()
    val active by remember(assistantId, conversationKey) {
        repository.observeActive(assistantId, conversationKey)
    }.collectAsState(initial = null)
    val pending by remember(assistantId) {
        repository.observePendingHandoffs(assistantId)
    }.collectAsState(initial = emptyList())
    val archives by remember(assistantId) {
        repository.observeArchives(assistantId)
    }.collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf(false) }
    var showDiary by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(assistantId, conversationKey) {
        repository.ensureActive(assistantId, conversationKey)
    }

    Card(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = assistant.name.trim().take(1).ifBlank { "宠" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(assistant.name.ifBlank { "桌宠" }, style = MaterialTheme.typography.titleSmall)
                val latest = active?.turns?.takeLast(2).orEmpty()
                Text(
                    text = latest.lastOrNull()?.assistantText?.ifBlank { null }
                        ?: if (mainBusy) "主任务进行中，触摸只做本地反馈" else "点这里聊两句",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${active?.turns?.size ?: 0}/20 轮", style = MaterialTheme.typography.labelSmall)
                if (pending.any { it.status == PetHandoffStatus.DRAFT.name }) {
                    Text("待转交", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${assistant.name.ifBlank { "桌宠" }} · 当前短会话", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(active?.turns.orEmpty(), key = { it.turnId }) { turn ->
                        Column(modifier = Modifier.padding(vertical = 5.dp)) {
                            Text("你：${turn.userText ?: "[${turn.inputKind}]"}", style = MaterialTheme.typography.bodySmall)
                            turn.assistantText?.let { Text("${assistant.name}：$it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> if (value.codePointCount(0, value.length) <= 500) input = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最多500字") },
                    enabled = !mainBusy && !sending,
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = input.isNotBlank() && !mainBusy && !sending,
                        onClick = {
                            val submitted = input.trim()
                            input = ""
                            sending = true
                            localError = null
                            scope.launch {
                                val persona = personaSource.observe(assistant.id).first()
                                val history = active?.turns.orEmpty().map { turn ->
                                    PetDialogueTurnEntityView(
                                        userInput = turn.userText ?: turn.interactionJson.orEmpty(),
                                        assistantText = turn.assistantText,
                                    )
                                }
                                val mode = runCatching { PetHandoffMode.valueOf(assistant.petHandoffMode) }
                                    .getOrDefault(PetHandoffMode.CONFIRM)
                                when (val result = generator.generate(persona, history, submitted, mode)) {
                                    is PetGenerationResult.Success -> {
                                        val updated = repository.append(
                                            assistantId,
                                            conversationKey,
                                            PetDialogueTurnDraft(
                                                inputKind = PetDialogueInputKind.TEXT,
                                                userText = submitted,
                                                assistantText = result.text.ifBlank { null },
                                                action = result.action,
                                                handoff = result.handoff,
                                            ),
                                        )
                                        if (mode == PetHandoffMode.AUTO) {
                                            updated.turns.lastOrNull()?.handoffRequestId?.let { handoffCoordinator.submit(it, automatic = true) }
                                        }
                                    }
                                    PetGenerationResult.LocalAnimationOnly -> repository.append(
                                        assistantId,
                                        conversationKey,
                                        PetDialogueTurnDraft(PetDialogueInputKind.TEXT, userText = submitted),
                                    )
                                    is PetGenerationResult.Failure -> localError = result.code
                                }
                                sending = false
                            }
                        },
                    ) { Text(if (sending) "回应中" else "发送") }
                    TextButton(onClick = {
                        scope.launch {
                            if (repository.archiveNow(assistantId, conversationKey) is PetArchiveResult.Empty) {
                                localError = "当前没有可保存的对白"
                            }
                        }
                    }) { Text("保存今天的对白") }
                    TextButton(onClick = { showDiary = true }) { Text("查看日记") }
                }
                pending.filter { it.status == PetHandoffStatus.DRAFT.name }.forEach { request ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("待转交：${request.title}", style = MaterialTheme.typography.titleSmall)
                            Text(request.request, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Row {
                                TextButton(onClick = { scope.launch { handoffCoordinator.submit(request.requestId, false) } }) {
                                    Text("转交")
                                }
                                TextButton(onClick = { scope.launch { handoffCoordinator.dismiss(request.requestId, request.stateVersion) } }) {
                                    Text("拒绝")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDiary) {
        AlertDialog(
            onDismissRequest = { showDiary = false },
            title = { Text("桌宠日记") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(archives, key = { it.sessionId }) { diary ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(diary.title.ifBlank { diary.localDate }, style = MaterialTheme.typography.titleSmall)
                            Text(diary.summary.ifBlank { "摘要生成中或尚未填写" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${diary.archiveReason} · ${diary.localDate}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDiary = false }) { Text("关闭") } },
        )
    }
}
