package me.rerere.rikkahub.diagnostics

import me.rerere.rikkahub.toolcatalog.ToolExperienceLibraryDiagnostics
import me.rerere.rikkahub.toolcatalog.ToolExperienceRepository
import me.rerere.rikkahub.toolcatalog.ToolShortcutLibraryDiagnostics
import me.rerere.rikkahub.toolcatalog.ToolShortcutRepository
import me.rerere.rikkahub.toolcatalog.ToolSurfaceBuilder

/** Counts only metadata; it never inspects tool arguments, outputs, commands, paths, or secrets. */
data class ToolCatalogDiagnosticSnapshot(
    val baselineToolCount: Int,
    val coverageGapCount: Int,
    val metadataRedactionViolationCount: Int,
    val experiences: ToolExperienceLibraryDiagnostics,
    val shortcuts: ToolShortcutLibraryDiagnostics,
) {
    val healthy: Boolean
        get() = coverageGapCount == 0 && metadataRedactionViolationCount == 0 &&
            experiences.redactionViolationCount == 0
}

class ToolCatalogDiagnostics(
    private val experiences: ToolExperienceRepository,
    private val shortcuts: ToolShortcutRepository,
) {
    suspend fun inspect(): ToolCatalogDiagnosticSnapshot {
        val snapshot = ToolSurfaceBuilder.staticCapabilityBaseline().snapshot
        val expected = ToolSurfaceBuilder.staticToolNames().toSet()
        val actual = snapshot.entries.map { it.toolName }.toSet()
        val metadataRedactionViolationCount = snapshot.entries.count { entry ->
            entry.summary.contains('\n') || entry.summary.length > 180 ||
                entry.requirements.any { it.length > 160 }
        }
        return ToolCatalogDiagnosticSnapshot(
            baselineToolCount = snapshot.entries.size,
            coverageGapCount = (expected - actual).size,
            metadataRedactionViolationCount = metadataRedactionViolationCount,
            experiences = experiences.diagnostics(),
            shortcuts = shortcuts.diagnostics(),
        )
    }
}
