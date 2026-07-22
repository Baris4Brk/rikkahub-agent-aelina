package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.pages.memory.MemoryCenterPage

/** Keeps the existing navigation contract while the old flat editor is replaced by Memory V2. */
@Composable
fun AssistantMemoryPage(id: String) {
    MemoryCenterPage(id)
}
