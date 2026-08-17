package me.rerere.rikkahub.learning.handoff

/**
 * Fail-closed production placeholder. Returning empty coverage here would falsely certify that an
 * authoritative command/execution scan completed, so bootstrap must remain unavailable instead.
 */
object UnavailableLearningReconciliationScanner : LearningReconciliationScanner {
    override suspend fun scanAndRepairProvableTerminalEvents(
        stream: LearningOutboxDescriptor,
        cursorAccess: LearningReconciliationCursorAccess,
        frozenNowMs: Long,
        limits: LearningBootstrapScanLimits,
    ): LearningBootstrapCoverage = throw IllegalStateException(
        "learning_reconciliation_scanner_not_configured",
    )
}
