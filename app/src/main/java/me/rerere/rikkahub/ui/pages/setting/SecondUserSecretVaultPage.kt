package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.security.SecretLeaseResult
import me.rerere.rikkahub.security.SecondUserLegacySecretMigration
import me.rerere.rikkahub.security.SecondUserLegacySecretMigrationResult
import me.rerere.rikkahub.security.SecretSlotMetadata
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.security.StrongBiometricAuthenticator
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * User-facing vault surface. Secret values are intentionally unavailable to the model-facing
 * management tools; only this BIOMETRIC_STRONG-gated page ever turns a value into visible text.
 */
@Composable
fun SecondUserSecretVaultPage(
    vault: SecondUserSecretVault = koinInject(),
    legacyMigration: SecondUserLegacySecretMigration = koinInject(),
    biometric: StrongBiometricAuthenticator = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var slots by remember { mutableStateOf(emptyList<SecretSlotMetadata>()) }
    var busy by remember { mutableStateOf(false) }
    var slotIdInput by remember { mutableStateOf("") }
    var secretInput by remember { mutableStateOf("") }
    var editingSlotId by remember { mutableStateOf<String?>(null) }
    var migrationResult by remember { mutableStateOf<SecondUserLegacySecretMigrationResult?>(null) }

    fun withUserAuthorization(action: suspend (me.rerere.rikkahub.security.SecretVaultUserAuthorization) -> Unit) {
        scope.launch {
            if (busy) return@launch
            busy = true
            try {
                biometric.authorizeSecretVault(
                    title = context.getString(R.string.second_user_vault_title),
                    subtitle = context.getString(R.string.second_user_vault_desc),
                )?.let { authorization ->
                    action(authorization)
                    slots = vault.listMetadataForUser(authorization)
                }
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.second_user_vault_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.second_user_vault_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(
                    enabled = !busy,
                    onClick = { withUserAuthorization { } },
                ) {
                    Text(stringResource(R.string.second_user_vault_open))
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                withUserAuthorization { authorization ->
                                    migrationResult = legacyMigration.migrateForUser(authorization)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.second_user_vault_migrate_legacy))
                        }
                        migrationResult?.let { result ->
                            Text(
                                stringResource(
                                    R.string.second_user_vault_migration_result,
                                    result.migratedTotal,
                                    result.pendingEntries,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = slotIdInput,
                            onValueChange = { slotIdInput = it.take(96) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.second_user_vault_slot_label)) },
                            singleLine = true,
                        )
                        Button(
                            enabled = !busy && slotIdInput.isNotBlank(),
                            onClick = {
                                val requestedSlotId = slotIdInput.trim()
                                withUserAuthorization { authorization ->
                                    val active = SecondUserAuthorityRegistry.current() ?: return@withUserAuthorization
                                    if (vault.createEmptySlot(
                                            metadata = SecretSlotMetadata(
                                                slotId = requestedSlotId,
                                                label = requestedSlotId,
                                                purpose = "user-created",
                                                authoritySubjectId = active.subjectId,
                                                createdAtMs = System.currentTimeMillis(),
                                                updatedAtMs = System.currentTimeMillis(),
                                            ),
                                            subjectId = active.subjectId,
                                        )
                                    ) {
                                        slotIdInput = ""
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.second_user_vault_add))
                        }
                    }
                }
            }
            if (slots.isEmpty()) {
                item { Text(stringResource(R.string.second_user_vault_empty)) }
            }
            items(slots, key = { it.slotId }) { slot ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    ListItem(
                        headlineContent = { Text(slot.label.ifBlank { slot.slotId }) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(slot.purpose)
                                    if (slot.bindings.isNotEmpty()) {
                                        append(" • ")
                                        append(slot.bindings.joinToString { it.kind.name })
                                    }
                                },
                            )
                        },
                        trailingContent = {
                            Column {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        withUserAuthorization { authorization ->
                                            when (val result = vault.withUserSecret(authorization, slot.slotId) {
                                                it.concatToString()
                                            }) {
                                                is SecretLeaseResult.Success -> {
                                                    editingSlotId = slot.slotId
                                                    secretInput = result.value
                                                }
                                                else -> Unit
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.second_user_vault_secret)) }
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        withUserAuthorization { authorization ->
                                            vault.deleteForUser(authorization, slot.slotId)
                                            if (editingSlotId == slot.slotId) {
                                                editingSlotId = null
                                                secretInput = ""
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.second_user_vault_delete)) }
                            }
                        },
                    )
                }
            }
            editingSlotId?.let { slotId ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = secretInput,
                                onValueChange = { secretInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.second_user_vault_secret)) },
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Button(
                                enabled = !busy,
                                onClick = {
                                    withUserAuthorization { authorization ->
                                        // Do not materialize a mutable secret before the user has
                                        // completed BIOMETRIC_STRONG. The vault clears this buffer
                                        // in all success and failure paths.
                                        val replacement = secretInput.toCharArray()
                                        if (vault.storeForUser(authorization, slotId, replacement)) {
                                            secretInput = ""
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.second_user_vault_save)) }
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    withUserAuthorization { authorization ->
                                        val active = SecondUserAuthorityRegistry.current() ?: return@withUserAuthorization
                                        val slot = slots.firstOrNull { it.slotId == slotId }
                                            ?: return@withUserAuthorization
                                        vault.rebindForUser(
                                            authorization = authorization,
                                            slotId = slotId,
                                            newSubjectId = active.subjectId,
                                            bindings = slot.bindings,
                                        )
                                    }
                                },
                            ) { Text(stringResource(R.string.second_user_vault_rebind)) }
                        }
                    }
                }
            }
        }
    }
}
