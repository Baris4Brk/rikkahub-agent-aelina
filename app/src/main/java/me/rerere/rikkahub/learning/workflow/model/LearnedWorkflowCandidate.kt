package me.rerere.rikkahub.learning.workflow.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.learning.model.LearningScope
import kotlin.uuid.Uuid

enum class LearnedWorkflowCandidateState {
    PROPOSED,
    VALIDATING,
    VERIFIED,
    PROMOTING,
    PROMOTED_DISABLED,
    REJECTED,
    STALE_SCHEMA,
    STALE_SOURCE,
    STALE_AUTHORITY,
    ARCHIVED,
}

@Serializable
enum class LearnedWorkflowSlotType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ENUM,
    SECRET_REF,
}

/** A typed candidate slot. Secret material is represented only by an opaque host reference. */
@Serializable
data class LearnedWorkflowTypedSlot(
    val name: String,
    val type: LearnedWorkflowSlotType,
    val required: Boolean,
    val value: JsonElement? = null,
    val secretRef: String? = null,
    val enumValues: List<String> = emptyList(),
) {
    init {
        require(name.matches(SLOT_NAME)) { "Invalid workflow slot name" }
        require(enumValues.size <= MAX_ENUM_VALUES)
        require(enumValues.distinct().sorted() == enumValues)
        require(enumValues.all { it.length in 1..MAX_ENUM_VALUE_CHARS && it.isSafeWireText() })
        if (type == LearnedWorkflowSlotType.SECRET_REF) {
            require(value == null) { "Secret slot cannot persist a literal value" }
            require(secretRef?.matches(SECRET_REFERENCE) == true) {
                "Secret slot requires an opaque reference"
            }
            require(enumValues.isEmpty())
        } else {
            require(secretRef == null) { "Non-secret slot cannot persist a secret reference" }
            require(type == LearnedWorkflowSlotType.ENUM || enumValues.isEmpty())
            require(type != LearnedWorkflowSlotType.ENUM || enumValues.isNotEmpty())
            if (value != null) {
                require(value.matchesSlotType(type, enumValues)) { "Workflow slot value/type mismatch" }
            }
        }
    }

    val isBound: Boolean get() = value != null || secretRef != null || !required

    override fun toString(): String =
        "LearnedWorkflowTypedSlot(name=$name, type=$type, required=$required, " +
            "bound=$isBound, value=<redacted>)"

    companion object {
        private val SLOT_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
        private val SECRET_REFERENCE = Regex("^secret-ref:[A-Za-z0-9_.:@/-]{1,160}$")
        private const val MAX_ENUM_VALUES = 32
        private const val MAX_ENUM_VALUE_CHARS = 128
    }
}

private fun JsonElement.matchesSlotType(
    type: LearnedWorkflowSlotType,
    enumValues: List<String>,
): Boolean = when (type) {
    LearnedWorkflowSlotType.STRING ->
        this is kotlinx.serialization.json.JsonPrimitive && isString
    LearnedWorkflowSlotType.INTEGER ->
        this is kotlinx.serialization.json.JsonPrimitive && !isString && longOrNull != null
    LearnedWorkflowSlotType.NUMBER ->
        this is kotlinx.serialization.json.JsonPrimitive && !isString && doubleOrNull != null
    LearnedWorkflowSlotType.BOOLEAN ->
        this is kotlinx.serialization.json.JsonPrimitive && !isString && booleanOrNull != null
    LearnedWorkflowSlotType.ENUM ->
        this is kotlinx.serialization.json.JsonPrimitive && isString && content in enumValues
    LearnedWorkflowSlotType.SECRET_REF -> false
}

@Serializable
data class LearnedWorkflowToolSchemaFingerprint(
    val actionIndex: Int,
    val toolName: String,
    val schemaFingerprint: String,
) {
    init {
        require(actionIndex >= 0)
        require(toolName.isSafeWorkflowIdentifier())
        require(schemaFingerprint.isCanonicalWorkflowSha256())
    }
}

@Serializable
enum class LearnedWorkflowVerificationStatus {
    PASSED,
    FAILED,
    ABSTAIN,
}

/** Structured, content-free verifier receipt. P4-004 supplies the actual verifier. */
@Serializable
data class LearnedWorkflowVerificationReport(
    val verifierVersion: String,
    val fixtureSetSha256: String,
    val status: LearnedWorkflowVerificationStatus,
    val passedChecks: Int,
    val failedChecks: Int,
    val failureCodes: List<String>,
    val completedAtMs: Long,
) {
    init {
        require(verifierVersion.isSafeWorkflowVersion())
        require(fixtureSetSha256.isCanonicalWorkflowSha256())
        require(passedChecks >= 0 && failedChecks >= 0)
        require(failureCodes.size <= 64)
        require(failureCodes.distinct().sorted() == failureCodes)
        require(failureCodes.all { it.matches(CODE) })
        require(completedAtMs >= 0L)
        require(status != LearnedWorkflowVerificationStatus.PASSED || failedChecks == 0)
    }

    override fun toString(): String =
        "LearnedWorkflowVerificationReport(status=$status, passed=$passedChecks, " +
            "failed=$failedChecks, codes=${failureCodes.size})"

    companion object {
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }
}

/** Complete bounded P4-001 candidate artifact; it never lives in the executable Workflow table. */
data class LearnedWorkflowCandidate(
    val id: String,
    val candidateVersion: Long,
    val stateVersion: Long,
    val state: LearnedWorkflowCandidateState,
    val assistantId: String,
    val authoritySubjectId: String?,
    val sourcePolicyId: String,
    val sourcePolicyRevision: Long,
    val sourcePolicyArtifactSha256: String,
    val sourceGrantDigest: String,
    val positiveAnchorEvidenceId: String,
    val evidenceIds: List<String>,
    val canonicalTemplateJson: String,
    val typedSlots: List<LearnedWorkflowTypedSlot>,
    val capabilitySnapshot: List<String>,
    val toolSchemaFingerprints: List<LearnedWorkflowToolSchemaFingerprint>,
    val producerProviderIdentity: String,
    val producerModelIdentity: String,
    val producerConfigurationIdentity: String,
    val producerConfigGeneration: Long,
    val compilerVersion: String,
    val promptVersion: String,
    val templateVersion: String,
    val validatorVersion: String,
    val verifierVersion: String,
    val maxOutputUtf8Bytes: Int,
    val artifactSha256: String,
    val verificationReport: LearnedWorkflowVerificationReport?,
    val verifiedAtMs: Long?,
    val archivedAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(id.startsWith(CANDIDATE_ID_PREFIX) && id.length <= 128)
        require(candidateVersion > 0L && stateVersion > 0L)
        require(assistantId.isCanonicalNonNilUuid()) { "Invalid workflow candidate Assistant" }
        authoritySubjectId?.let { LearningScope.AuthoritySubject(it) }
        require(sourcePolicyId.isSafeWorkflowIdentifier())
        require(sourcePolicyRevision > 0L)
        require(sourcePolicyArtifactSha256.isCanonicalWorkflowSha256())
        require(sourceGrantDigest.isCanonicalWorkflowSha256())
        require(positiveAnchorEvidenceId.isSafeWorkflowIdentifier())
        require(evidenceIds.isNotEmpty() && evidenceIds.size <= MAX_EVIDENCE_IDS)
        require(evidenceIds.distinct().sorted() == evidenceIds)
        require(evidenceIds.all(String::isSafeWorkflowIdentifier))
        require(positiveAnchorEvidenceId in evidenceIds)
        require(canonicalTemplateJson.toByteArray(Charsets.UTF_8).size in 2..MAX_TEMPLATE_BYTES)
        require(typedSlots.size <= MAX_TYPED_SLOTS)
        require(typedSlots.map { it.name }.distinct().size == typedSlots.size)
        require(capabilitySnapshot.isNotEmpty() && capabilitySnapshot.size <= MAX_CAPABILITIES)
        require(capabilitySnapshot.distinct().sorted() == capabilitySnapshot)
        require(capabilitySnapshot.all(String::isSafeWorkflowCapability))
        require(toolSchemaFingerprints.isNotEmpty() &&
            toolSchemaFingerprints.size <= MAX_ACTIONS)
        require(toolSchemaFingerprints.map { it.actionIndex } == toolSchemaFingerprints.indices.toList())
        listOf(
            producerProviderIdentity,
            producerModelIdentity,
            producerConfigurationIdentity,
            compilerVersion,
            promptVersion,
            templateVersion,
            validatorVersion,
            verifierVersion,
        ).forEach { require(it.isSafeWorkflowVersion()) }
        require(producerConfigGeneration >= 0L)
        require(maxOutputUtf8Bytes in 1..MAX_OUTPUT_UTF8_BYTES)
        require(artifactSha256.isCanonicalWorkflowSha256())
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs)
        require(verifiedAtMs == null || verifiedAtMs in createdAtMs..updatedAtMs)
        require(archivedAtMs == null || archivedAtMs in createdAtMs..updatedAtMs)
        require(state != LearnedWorkflowCandidateState.ARCHIVED || archivedAtMs != null)
        require(state == LearnedWorkflowCandidateState.ARCHIVED || archivedAtMs == null)
        require(verificationReport == null || verificationReport.verifierVersion == verifierVersion)
        if (state == LearnedWorkflowCandidateState.PROPOSED) {
            require(verificationReport == null && verifiedAtMs == null) {
                "A proposed/edit candidate cannot retain verifier authority"
            }
        }
    }

    val policyScope: LearningScope = authoritySubjectId?.let { LearningScope.AuthoritySubject(it) }
        ?: LearningScope.Assistant(Uuid.parse(assistantId))

    override fun toString(): String =
        "LearnedWorkflowCandidate(state=$state, candidateVersion=$candidateVersion, " +
            "stateVersion=$stateVersion, actions=${toolSchemaFingerprints.size}, ids=<redacted>)"

    companion object {
        const val CANDIDATE_ID_PREFIX = "workflow-candidate-v1:"
        const val MAX_TEMPLATE_BYTES = 32 * 1_024
        const val MAX_OUTPUT_UTF8_BYTES = 64 * 1_024
        const val MAX_ACTIONS = 8
        const val MAX_TYPED_SLOTS = 32
        const val MAX_EVIDENCE_IDS = 64
        const val MAX_CAPABILITIES = 64
    }
}

internal fun String.isCanonicalWorkflowSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

internal fun String.isSafeWorkflowIdentifier(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == ':' || char == '@'
    }

internal fun String.isSafeWorkflowCapability(): Boolean =
    length in 1..128 && this == lowercase() && all { char ->
        char in 'a'..'z' || char in '0'..'9' || char == '.' || char == '_' || char == '-'
    }

internal fun String.isSafeWorkflowVersion(): Boolean =
    length in 1..160 && isSafeWireText()

internal fun String.isSafeWireText(): Boolean =
    isNotBlank() && none { it.code < 0x20 || it == '\u007f' }

private fun String.isCanonicalNonNilUuid(): Boolean = runCatching {
    this != NIL_UUID && Uuid.parse(this).toString() == this
}.getOrDefault(false)

private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
