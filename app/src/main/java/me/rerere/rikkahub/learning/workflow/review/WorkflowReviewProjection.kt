package me.rerere.rikkahub.learning.workflow.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionEntity
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowSlotType
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus
import me.rerere.rikkahub.workflow.model.WorkflowCapabilitySnapshot
import me.rerere.rikkahub.workflow.model.WorkflowJson

fun LearnedWorkflowCandidate.toWorkflowReviewListItemOrNull(): WorkflowReviewListItem? {
    val template = runCatching { Json.parseToJsonElement(canonicalTemplateJson) as? JsonObject }
        .getOrNull() ?: return null
    val definition = WorkflowJson.parseStoredWithCompatibility(canonicalTemplateJson)
        ?.definition ?: return null
    val trigger = template["trigger"]?.toReviewJson() ?: return null
    return WorkflowReviewListItem(
        fence = WorkflowReviewFence(id, candidateVersion, stateVersion, artifactSha256),
        state = state,
        sourcePolicyId = sourcePolicyId,
        sourcePolicyRevision = sourcePolicyRevision,
        evidenceCount = evidenceIds.size,
        triggerSummary = trigger.take(MAX_REVIEW_SUMMARY_CHARS),
        actionCount = definition.actions.size,
        fakeVerificationPassed = verificationReport?.status ==
            LearnedWorkflowVerificationStatus.PASSED,
        updatedAtMs = updatedAtMs,
    )
}

fun LearnedWorkflowCandidate.toWorkflowReviewDetailOrNull(
    revisions: List<LearnedWorkflowCandidateRevisionEntity>,
): WorkflowReviewDetail? {
    val item = toWorkflowReviewListItemOrNull() ?: return null
    val template = runCatching { Json.parseToJsonElement(canonicalTemplateJson) as? JsonObject }
        .getOrNull() ?: return null
    val definition = WorkflowJson.parseStoredWithCompatibility(canonicalTemplateJson)
        ?.definition ?: return null
    val trigger = template["trigger"]?.toReviewJson() ?: return null
    val conditions = (template["conditions"] as? JsonArray).orEmpty().map { condition ->
        condition.toReviewJson()
    }
    val actions = definition.actions.mapIndexed { index, action ->
        val schema = toolSchemaFingerprints.singleOrNull { it.actionIndex == index }
            ?: return null
        if (schema.toolName != action.tool) return null
        val redacted = action.args.redactSecrets()
        WorkflowReviewAction(
            index = index,
            toolName = action.tool,
            normalizedParameters = redacted.value.toReviewJson(),
            secretReferenceMasked = redacted.masked,
            capabilities = WorkflowCapabilitySnapshot.capture(listOf(action)).toList().sorted(),
            // Current host metadata is joined by ProductionWorkflowReviewRepository.
            risk = "UNRESOLVED",
            origin = "UNRESOLVED",
            schemaSha256 = schema.schemaFingerprint,
        )
    }
    return WorkflowReviewDetail(
        item = item,
        assistantId = assistantId,
        authoritySubjectId = authoritySubjectId,
        sourcePolicyArtifactSha256 = sourcePolicyArtifactSha256,
        sourceGrantDigest = sourceGrantDigest,
        positiveAnchorEvidenceId = positiveAnchorEvidenceId,
        evidenceIds = evidenceIds,
        trigger = trigger,
        conditions = conditions,
        slots = typedSlots.map { slot ->
            WorkflowReviewSlot(
                name = slot.name,
                type = slot.type.name,
                required = slot.required,
                displayValue = if (slot.type == LearnedWorkflowSlotType.SECRET_REF) {
                    maskedSecretReference(slot.secretRef.orEmpty())
                } else {
                    slot.value?.toReviewJson() ?: if (slot.required) "<unbound>" else "<optional>"
                },
                isSecretReference = slot.type == LearnedWorkflowSlotType.SECRET_REF,
            )
        },
        actions = actions,
        capabilitySnapshot = capabilitySnapshot.sorted(),
        fakeReport = verificationReport?.let { report ->
            WorkflowReviewFakeReport(
                status = report.status.name,
                verifierVersion = report.verifierVersion,
                fixtureSetSha256 = report.fixtureSetSha256,
                passedChecks = report.passedChecks,
                failedChecks = report.failedChecks,
                failureCodes = report.failureCodes,
                completedAtMs = report.completedAtMs,
            )
        },
        producerProviderIdentity = producerProviderIdentity,
        producerModelIdentity = producerModelIdentity,
        compilerVersion = compilerVersion,
        templateVersion = templateVersion,
        validatorVersion = validatorVersion,
        enableImpact = WorkflowEnableImpact.MANUAL_TRIGGER_GATED_ACTIONS,
        installedWorkflowStateVersion = null,
        revisions = revisions.mapNotNull { revision ->
            if (revision.candidateId != id) return@mapNotNull null
            WorkflowReviewRevision(
                candidateVersion = revision.candidateVersion,
                stateVersion = revision.stateVersion,
                state = runCatching {
                    me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
                        .valueOf(revision.state)
                }.getOrNull() ?: return@mapNotNull null,
                artifactSha256 = revision.artifactSha256,
                previousArtifactSha256 = revision.previousArtifactSha256,
                reasonCode = revision.reasonCode,
                actor = revision.actor,
                createdAtMs = revision.createdAtMs,
                isCurrent = revision.stateVersion == stateVersion &&
                    revision.artifactSha256 == artifactSha256,
            )
        },
    )
}

private data class RedactedJson(val value: JsonElement, val masked: Boolean)

private fun JsonElement.redactSecrets(key: String? = null): RedactedJson {
    if (key?.isSecretKey() == true) {
        return RedactedJson(JsonPrimitive("<masked-secret-ref>"), true)
    }
    return when (this) {
        is JsonObject -> {
            var masked = false
            val entries = entries.sortedBy { it.key }.associate { (childKey, childValue) ->
                val child = childValue.redactSecrets(childKey)
                masked = masked || child.masked
                childKey to child.value
            }
            RedactedJson(JsonObject(entries), masked)
        }
        is JsonArray -> {
            var masked = false
            val values = map { childValue ->
                val child = childValue.redactSecrets()
                masked = masked || child.masked
                child.value
            }
            RedactedJson(JsonArray(values), masked)
        }
        is JsonPrimitive -> if (isString && content.startsWith("secret-ref:")) {
            RedactedJson(JsonPrimitive(maskedSecretReference(content)), true)
        } else {
            RedactedJson(this, false)
        }
        JsonNull -> RedactedJson(JsonNull, false)
    }
}

private fun String.isSecretKey(): Boolean {
    val normalized = lowercase().replace('-', '_')
    return normalized.contains("secret") || normalized.contains("password") ||
        normalized.contains("token") || normalized.contains("api_key") ||
        normalized.contains("credential")
}

private fun maskedSecretReference(reference: String): String =
    "secret-ref:••••" + reference.takeLast(4)

private fun JsonElement.toReviewJson(): String = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { (key, value) ->
        key to value.toCanonicalElement()
    }).toString()
    else -> toCanonicalElement().toString()
}

private fun JsonElement.toCanonicalElement(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { (key, value) ->
        key to value.toCanonicalElement()
    })
    is JsonArray -> JsonArray(map(JsonElement::toCanonicalElement))
    is JsonPrimitive, JsonNull -> this
}

private const val MAX_REVIEW_SUMMARY_CHARS = 2_048
