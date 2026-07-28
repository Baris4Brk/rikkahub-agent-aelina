package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.PetDialogueRevisionEntity
import me.rerere.rikkahub.data.db.entity.PetDialogueSessionEntity
import me.rerere.rikkahub.data.db.entity.PetDialogueTurnEntity
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetDialogueSessionStatus
import me.rerere.rikkahub.pet.PetSummaryScheduler
import me.rerere.rikkahub.pet.PetSummaryState
import org.koin.compose.koinInject

@Composable
fun PetDiaryDialog(
    assistantId: String,
    onDismiss: () -> Unit,
) {
    val repository: PetDialogueRepository = koinInject()
    val summaryScheduler: PetSummaryScheduler = koinInject()
    val scope = rememberCoroutineScope()
    val index by remember(assistantId) { repository.observeDiaryIndex(assistantId) }
        .collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = index.firstOrNull { it.sessionId == selectedId }
    var turns by remember { mutableStateOf<List<PetDialogueTurnEntity>>(emptyList()) }
    var revisions by remember { mutableStateOf<List<PetDialogueRevisionEntity>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var tagsJson by remember { mutableStateOf("[]") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedId, selected?.stateVersion) {
        val session = selected ?: return@LaunchedEffect
        turns = repository.getTurns(session.sessionId)
        revisions = repository.getRevisions(session.sessionId)
        title = session.title
        summary = session.summary
        notes = session.notes
        tagsJson = session.tagsJson
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selected == null) "桌宠日记" else selected.title.ifBlank { selected.localDate }) },
        text = {
            if (selected == null) {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(index, key = { it.sessionId }) { diary ->
                        DiaryIndexCard(diary = diary, onClick = { selectedId = diary.sessionId })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "${selected.localDate} · ${selected.archiveReason} · ${turns.size} 轮",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    items(turns, key = { it.turnId }) { turn ->
                        Column {
                            Text("你：${turn.userText ?: "[${turn.inputKind}] ${turn.interactionJson.orEmpty()}"}")
                            Text("桌宠：${turn.assistantText.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        Text("以下元数据可以修改；上面的原始对白不可改写。", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("标题") })
                        OutlinedTextField(summary, { summary = it.take(4_000) }, Modifier.fillMaxWidth(), label = { Text("摘要") }, minLines = 2)
                        OutlinedTextField(tagsJson, { tagsJson = it.take(4_000) }, Modifier.fillMaxWidth(), label = { Text("标签 JSON") })
                        OutlinedTextField(notes, { notes = it.take(8_000) }, Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 2)
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    message = repository.updateMetadata(
                                        selected.sessionId,
                                        selected.stateVersion,
                                        title,
                                        summary,
                                        notes,
                                        tagsJson,
                                        PetSummaryState.READY,
                                        "local-user",
                                    ).toString()
                                }
                            }) { Text("保存修改") }
                            if (selected.summaryState == PetSummaryState.FAILED.name) {
                                TextButton(onClick = { summaryScheduler.schedule(selected.sessionId) }) { Text("重试摘要") }
                            }
                        }
                        when (selected.status) {
                            PetDialogueSessionStatus.ARCHIVED.name -> TextButton(onClick = {
                                scope.launch {
                                    repository.softDelete(selected.sessionId, selected.stateVersion, "local-user")
                                    selectedId = null
                                }
                            }) { Text("移到30天回收站") }
                            PetDialogueSessionStatus.SOFT_DELETED.name -> Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        repository.restore(selected.sessionId, selected.stateVersion, "local-user")
                                        selectedId = null
                                    }
                                }) { Text("恢复") }
                                TextButton(onClick = {
                                    scope.launch {
                                        repository.deletePermanently(selected.sessionId, assistantId, selected.stateVersion)
                                        selectedId = null
                                    }
                                }) { Text("永久删除") }
                            }
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    item {
                        Text("修改记录", style = MaterialTheme.typography.titleSmall)
                        revisions.forEach { revision ->
                            Text(
                                "v${revision.revision} · ${revision.operation} · ${revision.actor}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (selected == null) onDismiss() else selectedId = null }) {
                Text(if (selected == null) "关闭" else "返回日记列表")
            }
        },
    )
}

@Composable
private fun DiaryIndexCard(diary: PetDialogueSessionEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(diary.title.ifBlank { diary.localDate }, style = MaterialTheme.typography.titleSmall)
            Text(
                diary.summary.ifBlank { if (diary.summaryState == PetSummaryState.FAILED.name) "摘要失败" else "摘要生成中" },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (diary.status == PetDialogueSessionStatus.SOFT_DELETED.name) "回收站 · 30天后清理" else diary.archiveReason.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
