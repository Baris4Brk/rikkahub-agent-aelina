package me.rerere.rikkahub.learning.runtime

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.policyApplicableCapabilityDigest
import me.rerere.rikkahub.learning.policy.runtime.ActivePolicyApplicabilitySnapshot
import me.rerere.rikkahub.learning.review.MAX_POLICY_REDACTED_REPORT_CHARS
import me.rerere.rikkahub.learning.review.PolicyReviewDetail
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire

internal fun changedPolicySnapshotFields(before: String?, after: String): List<String> {
    fun parse(snapshot: String?): Map<String, String> = snapshot?.lineSequence()
        ?.drop(1)
        ?.mapNotNull { line ->
            val split = line.indexOf('=')
            line.takeIf { split > 0 }?.let {
                it.substring(0, split) to it.substring(split + 1)
            }
        }
        ?.toMap()
        .orEmpty()
    val left = parse(before)
    val right = parse(after)
    return (left.keys + right.keys)
        .filter { key -> left[key] != right[key] }
        .filter { key -> key in REVIEWABLE_POLICY_DIFF_FIELDS }
        .distinct()
        .sorted()
        .take(24)
}

private val REVIEWABLE_POLICY_DIFF_FIELDS = setOf(
    "type",
    "task",
    "trigger",
    "procedure",
    "verification",
    "boundary",
    "failure",
    "applicable_tools",
    "applicable_model",
    "applicable_provider",
    "applicable_template",
    "applicable_configuration",
    "applicable_configuration_generation",
    "applicable_capability",
    "applicable_authority",
    "status",
    "source_valid",
    "schema_valid",
    "stale_reason",
    "support",
    "positive",
    "negative",
    "confidence",
)

internal fun PolicyReviewDetail.toRedactedPolicyReviewReport(): String {
    val report = buildString {
        appendLine("RikkaHub Learning Policy Review (redacted)")
        appendLine("scope_kind=${item.fence.scope.kind}")
        appendLine("status=${item.status}")
        appendLine("state_revision=${item.fence.stateVersion}")
        appendLine("content_revision=${item.fence.contentRevision}")
        appendLine("artifact=${item.fence.artifactSha256.shortReviewIdentity()}")
        appendLine("policy_type=${policyType.reviewReportValue(128)}")
        appendLine("task_signature=${taskSignature.shortReviewIdentity()}")
        appendLine("episode_support=${item.distinctEpisodeSupport}")
        appendLine("positive_evidence=${item.positiveEpisodeCount}")
        appendLine("negative_evidence=${item.negativeEpisodeCount}")
        appendLine("confidence=${item.confidence}")
        appendLine("observed_utility_delta=${item.observedUtilityDelta ?: "UNKNOWN"}")
        appendLine("utility_uncertainty=${item.utilityUncertainty ?: "UNKNOWN"}")
        appendLine("shadow_recall_count=${item.exposure.shadowRecallCount}")
        appendLine("shadow_exact_task_recall_count=${item.exposure.shadowExactTaskRecallCount}")
        appendLine("shadow_estimated_token_cost=${item.exposure.shadowEstimatedTokenCost}")
        appendLine("shadow_last_observed_at_ms=${item.exposure.shadowLastObservedAtMs ?: "UNKNOWN"}")
        appendLine("actual_retrieved_count=${item.exposure.actualRetrievedCount}")
        appendLine("injected_hits=${item.exposure.injectedHitCount}")
        appendLine("host_dispatched_hits=${item.exposure.hostDispatchedHitCount}")
        appendLine("dropped_items=${item.exposure.droppedItemCount}")
        appendLine("drop_reasons=${item.exposure.dropReasons.joinToString(",")}")
        appendLine("estimated_token_cost=${item.exposure.estimatedTokenCost}")
        appendLine("expiry_reason=${item.staleReason ?: "NONE"}")
        appendLine("producer_kind=${producerProviderKind.reviewReportValue(64)}")
        appendLine("producer_model=${producerModelIdentity.shortReviewIdentity()}")
        appendLine("producer_provider=${producerProviderIdentity.shortReviewIdentity()}")
        appendLine("producer_prompt=${producerPromptIdentity.shortReviewIdentity()}")
        appendLine("producer_template=${producerTemplateIdentity.shortReviewIdentity()}")
        appendLine("producer_schema=${producerSchemaIdentity.shortReviewIdentity()}")
        appendLine("trigger=${item.triggerSummary.reviewReportValue(1_000)}")
        appendLine("procedure=${procedureSummary.reviewReportValue(1_000)}")
        appendLine("verification=${verificationSummary.reviewReportValue(1_000)}")
        appendLine("boundary=${boundarySummary.reviewReportValue(1_000)}")
        appendLine("failure_modes=${failureModeSummary.reviewReportValue(1_000)}")
        appendLine("revision_count=${revisions.size}")
        revisions.forEach { revision ->
            appendLine(
                "revision=${revision.revision};reason=${revision.reasonCode};" +
                    "actor=${revision.actor};artifact=" +
                    revision.artifactSha256.shortReviewIdentity(),
            )
        }
        appendLine("authority=CONTEXTUAL_ADVICE_ONLY")
        appendLine("system_instruction=false")
        appendLine("standing_instruction=false")
        appendLine("tool_authorization=false")
    }
    return report.take(MAX_POLICY_REDACTED_REPORT_CHARS)
}

private fun String.shortReviewIdentity(): String = take(12)

private fun String.reviewReportValue(maxChars: Int): String =
    replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\u0000', ' ')
        .take(maxChars)

internal val POLICY_DISPATCH_SHA256 = Regex("[0-9a-f]{64}")

internal fun LearningPolicyEntity.toActiveApplicabilitySnapshotOrNull():
    ActivePolicyApplicabilitySnapshot? {
    val scope = LearningScope.parseOrNull(scopeKind, scopeId) ?: return null
    if (status != LearningPolicyStatus.ACTIVE.name || !sourceValid || !schemaValid ||
        staleReason != null
    ) return null
    val toolSchemas = PolicyApplicabilityWire.decodeToolSchemasOrNull(applicableToolSchemasWire)
        ?: return null
    if (applicableCapabilityDigest != policyApplicableCapabilityDigest(toolSchemas)) return null
    return runCatching {
        ActivePolicyApplicabilitySnapshot(
            fence = PolicyMutationFence(
                policyId = id,
                scope = scope,
                expectedRevision = stateVersion,
                expectedContentRevision = contentRevision,
                expectedArtifactHash = artifactSha256,
            ),
            status = LearningPolicyStatus.ACTIVE,
            expectedToolSchemaFingerprints = toolSchemas,
            expectedCapabilityDigest = applicableCapabilityDigest,
            producerModelIdentity = producerModelIdentity,
            producerProviderIdentity = producerProviderIdentity,
            updatedAtMs = updatedAtMs,
        )
    }.getOrNull()
}

internal fun LearningPolicyEntity.matchesExactApplicabilitySnapshot(
    snapshot: ActivePolicyApplicabilitySnapshot,
): Boolean = id == snapshot.fence.policyId &&
    scopeKind == snapshot.fence.scope.kind.name &&
    scopeId == snapshot.fence.scope.storageId &&
    stateVersion == snapshot.fence.expectedRevision &&
    contentRevision == snapshot.fence.expectedContentRevision &&
    artifactSha256 == snapshot.fence.expectedArtifactHash &&
    status == LearningPolicyStatus.ACTIVE.name && sourceValid && schemaValid && staleReason == null &&
    PolicyApplicabilityWire.decodeToolSchemasOrNull(applicableToolSchemasWire) ==
        snapshot.expectedToolSchemaFingerprints &&
    applicableCapabilityDigest == snapshot.expectedCapabilityDigest &&
    producerModelIdentity == snapshot.producerModelIdentity &&
    producerProviderIdentity == snapshot.producerProviderIdentity &&
    updatedAtMs == snapshot.updatedAtMs
