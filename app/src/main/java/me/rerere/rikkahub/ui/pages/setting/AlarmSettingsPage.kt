package me.rerere.rikkahub.ui.pages.setting

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AlarmSettingsPage() {
    val ctx = LocalContext.current
    val repository: AlarmRepository = koinInject()
    val scheduler: AlarmScheduler = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var alarms by remember { mutableStateOf<List<AlarmEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        alarms = repository.getAllOnce()
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Alarm") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (!scheduler.canScheduleExactAlarms() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Exact alarm permission not granted. Alarms may not fire on time.", color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { scheduler.openExactAlarmSettings() }) {
                            Text("Grant permission")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (alarms.isEmpty()) {
                Text("No alarms set. Ask the AI to create one!", style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    if (enabled) {
                                        val next = scheduler.calculateNextFireAt(alarm)
                                        repository.markFired(alarm.id, alarm.lastFiredAtMs ?: System.currentTimeMillis(), next)
                                        scheduler.schedule(alarm.copy(nextFireAtMs = next, enabled = true))
                                    } else {
                                        scheduler.cancel(alarm.id)
                                        repository.setEnabled(alarm.id, false)
                                    }
                                    alarms = repository.getAllOnce()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    scheduler.cancel(alarm.id)
                                    repository.deleteById(alarm.id)
                                    alarms = repository.getAllOnce()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val timeStr = when (alarm.scheduleType) {
        "once" -> alarm.time?.let {
            try {
                Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            } catch (_: Exception) { it }
        } ?: "—"
        "weekly" -> {
            val h = alarm.hour ?: 0
            val m = alarm.minute ?: 0
            val days = alarm.daysOfWeek?.split(",")?.mapNotNull { it.toIntOrNull() }
                ?.map { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").getOrElse(it - 1) { "?" } }
                ?.joinToString(", ") ?: "—"
            "${"%02d".format(h)}:%02d".format(m) + " ($days)"
        }
        else -> "—"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(HugeIcons.Alert01, null, modifier = Modifier.size(32.dp), tint = if (alarm.enabled) Color.Unspecified else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(alarm.label, style = MaterialTheme.typography.titleSmall)
                Text(timeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggleEnabled)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
