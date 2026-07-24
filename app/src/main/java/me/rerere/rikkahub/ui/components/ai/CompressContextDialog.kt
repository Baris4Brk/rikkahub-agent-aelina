package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.DEFAULT_MANUAL_COMPRESSION_TARGET_TOKENS
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

internal data class CompressContextInput(
    val targetTokens: Int,
    val keepRecentMessages: Int,
)

internal fun parseCompressContextInput(
    targetTokens: String,
    keepRecentMessages: String,
): CompressContextInput? {
    val parsedTargetTokens = targetTokens.trim().toIntOrNull()
        ?.takeIf { it in 100..32_000 }
        ?: return null
    val parsedKeepRecentMessages = keepRecentMessages.trim().toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: return null
    return CompressContextInput(
        targetTokens = parsedTargetTokens,
        keepRecentMessages = parsedKeepRecentMessages,
    )
}

@Composable
fun CompressContextDialog(
    initialKeepRecentMessages: Int,
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var targetTokensText by remember { mutableStateOf(DEFAULT_MANUAL_COMPRESSION_TARGET_TOKENS.toString()) }
    var keepRecentMessagesText by remember(initialKeepRecentMessages) {
        mutableStateOf(initialKeepRecentMessages.toString())
    }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true
    val parsedInput = parseCompressContextInput(
        targetTokens = targetTokensText,
        keepRecentMessages = keepRecentMessagesText,
    )
    val targetTokensValid = targetTokensText.trim().toIntOrNull() in 100..32_000
    val keepRecentMessagesValid = keepRecentMessagesText.trim().toIntOrNull()?.let { it >= 0 } == true

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    OutlinedTextField(
                        value = targetTokensText,
                        onValueChange = { targetTokensText = it },
                        label = { Text(stringResource(R.string.chat_page_compress_target_tokens)) },
                        supportingText = {
                            Text(stringResource(R.string.chat_page_compress_target_tokens_supporting))
                        },
                        isError = !targetTokensValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = keepRecentMessagesText,
                        onValueChange = { keepRecentMessagesText = it },
                        label = { Text(stringResource(R.string.chat_page_compress_keep_recent)) },
                        supportingText = {
                            Text(stringResource(R.string.chat_page_compress_keep_recent_supporting))
                        },
                        isError = !keepRecentMessagesValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    enabled = parsedInput != null,
                    onClick = {
                        val input = parsedInput ?: return@TextButton
                        currentJob = onConfirm(
                            additionalPrompt,
                            input.targetTokens,
                            input.keepRecentMessages,
                        )
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
