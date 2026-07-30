package me.rerere.rikkahub.toolcatalog

/**
 * A host-maintained quick package is a small, audited set of current schemas for a well-known
 * task. It is not a Skill body and cannot grant a permission or execute anything by itself.
 */
data class ToolFastLaneBundle(
    val id: String,
    val toolNames: List<String>,
    val guidance: String,
)

object ToolFastLaneBundles {
    val PHONE_STATUS_FULL = ToolFastLaneBundle(
        id = "phone_status_full",
        toolNames = listOf(
            "get_battery_status",
            "get_wifi_info",
            "get_telephony_info",
            "get_storage_info",
            "get_audio_info",
            "list_paired_bluetooth_devices",
            "get_brightness",
            "get_volume",
            "get_step_count",
            "get_screen_time",
            "read_sensor",
        ),
        guidance = "A complete device-status request has its bounded reader schemas ready. " +
            "Collect available readings once, state unavailable permissions clearly, then summarise them.",
    )

    val VOICE_CLONE = ToolFastLaneBundle(
        id = "voice_clone",
        toolNames = listOf(
            "workspace_shell",
            "list_files",
            "copy_file",
            "open_file",
            "play_media",
            "get_media_status",
            "get_audio_info",
        ),
        guidance = "The voice workflow helper schemas are ready. Use only the schemas needed for " +
            "the requested step; a shell schema being visible never authorises arbitrary commands.",
    )

    fun match(userText: String): ToolFastLaneBundle? {
        val normalized = userText.lowercase()
        return when {
            normalized.containsAny(
                "手机状态", "状态总结", "状态全览", "设备状态", "phone status", "device status",
            ) -> PHONE_STATUS_FULL

            normalized.containsAny(
                "voice-clone", "voice clone", "音色克隆", "克隆声音", "情感tts", "emotiontts",
            ) -> VOICE_CLONE

            else -> null
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}

/** Local relevance only; it receives tool metadata but never model text beyond the current input. */
object ToolShortcutRelevance {
    fun select(
        query: String,
        shortcuts: List<ToolShortcutSummary>,
        max: Int,
    ): List<ToolShortcutSummary> {
        if (max <= 0 || shortcuts.isEmpty()) return emptyList()
        val terms = query.lowercase()
            .split(Regex("[^a-z0-9_\u4e00-\u9fff]+"))
            .filter { it.length >= 2 }
            .toMutableSet()
            .apply { addAll(expandedTerms(query.lowercase())) }
        return shortcuts.asSequence()
            .filter { it.state == ToolShortcutState.ACTIVE.name }
            .map { shortcut -> shortcut to score(shortcut, terms) }
            // Never pull arbitrary old shortcuts into unrelated prompts merely because their
            // use-count is high. An empty lexical score only wins when the library has six or
            // fewer rows and a model deliberately confirmed them as its compact working set.
            .filter { (_, score) -> score > 0 || shortcuts.size <= max }
            .sortedWith(
                compareByDescending<Pair<ToolShortcutSummary, Int>> { it.second }
                    .thenByDescending { it.first.lastUsedAtMs ?: 0L }
                    .thenByDescending { it.first.useCount }
                    .thenBy { it.first.toolName },
            )
            .map { it.first }
            .take(max)
            .toList()
    }

    private fun score(shortcut: ToolShortcutSummary, terms: Set<String>): Int {
        val searchable = "${shortcut.toolName} ${shortcut.categoryPath}".lowercase()
        val exact = terms.count { term -> searchable.contains(term) }
        val categoryBoost = when {
            shortcut.categoryPath.contains("Command line", ignoreCase = true) &&
                terms.any { it in setOf("shell", "终端", "命令", "linux", "termux", "ssh") } -> 6
            shortcut.categoryPath.contains("Files", ignoreCase = true) &&
                terms.any { it in setOf("file", "文件", "写入", "删除", "保存") } -> 6
            shortcut.categoryPath.contains("Media", ignoreCase = true) &&
                terms.any { it in setOf("tts", "语音", "音频", "朗读", "播放") } -> 6
            else -> 0
        }
        return exact * 10 + categoryBoost + shortcut.useCount.coerceAtMost(5).toInt()
    }

    private fun expandedTerms(query: String): Set<String> = buildSet {
        if (query.contains("写") || query.contains("保存") || query.contains("编辑")) {
            addAll(listOf("write", "edit", "copy", "file"))
        }
        if (query.contains("删") || query.contains("清理")) {
            addAll(listOf("delete", "remove", "file"))
        }
        if (query.contains("终端") || query.contains("命令") || query.contains("shell")) {
            addAll(listOf("shell", "workspace", "termux", "linux", "ssh"))
        }
        if (query.contains("语音") || query.contains("朗读") || query.contains("播放")) {
            addAll(listOf("tts", "audio", "media", "speech"))
        }
        if (query.contains("文件")) add("file")
    }
}
