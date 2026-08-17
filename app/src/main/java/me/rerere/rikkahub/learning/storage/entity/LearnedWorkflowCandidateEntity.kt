package me.rerere.rikkahub.learning.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationReport
import me.rerere.rikkahub.learning.workflow.model.isCanonicalWorkflowSha256
import me.rerere.rikkahub.learning.workflow.model.isSafeWorkflowIdentifier
import me.rerere.rikkahub.learning.workflow.model.isSafeWorkflowVersion

@Entity(
    tableName = "learned_workflow_candidates",
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_policy_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_policy_id"]),
        Index(
            value = ["assistant_id", "authority_subject_id", "state", "updated_at_ms", "id"],
        ),
        Index(
            value = ["source_policy_id", "source_policy_revision", "assistant_id"],
            unique = true,
        ),
        Index(value = ["state", "updated_at_ms", "id"]),
        Index(value = ["artifact_sha256"]),
    ],
)
data class LearnedWorkflowCandidateEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "candidate_version") val candidateVersion: Long,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    val state: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    @ColumnInfo(name = "authority_subject_id") val authoritySubjectId: String?,
    @ColumnInfo(name = "source_policy_id") val sourcePolicyId: String,
    @ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
    @ColumnInfo(name = "source_policy_artifact_sha256") val sourcePolicyArtifactSha256: String,
    @ColumnInfo(name = "source_grant_digest") val sourceGrantDigest: String,
    @ColumnInfo(name = "positive_anchor_evidence_id") val positiveAnchorEvidenceId: String,
    @ColumnInfo(name = "evidence_ids_wire") val evidenceIdsWire: String,
    @ColumnInfo(name = "canonical_template_json") val canonicalTemplateJson: String,
    @ColumnInfo(name = "typed_slots_wire") val typedSlotsWire: String,
    @ColumnInfo(name = "capability_snapshot_wire") val capabilitySnapshotWire: String,
    @ColumnInfo(name = "tool_schema_fingerprints_wire") val toolSchemaFingerprintsWire: String,
    @ColumnInfo(name = "producer_provider_identity") val producerProviderIdentity: String,
    @ColumnInfo(name = "producer_model_identity") val producerModelIdentity: String,
    @ColumnInfo(name = "producer_configuration_identity") val producerConfigurationIdentity: String,
    @ColumnInfo(name = "producer_config_generation") val producerConfigGeneration: Long,
    @ColumnInfo(name = "compiler_version") val compilerVersion: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String,
    @ColumnInfo(name = "template_version") val templateVersion: String,
    @ColumnInfo(name = "validator_version") val validatorVersion: String,
    @ColumnInfo(name = "verifier_version") val verifierVersion: String,
    @ColumnInfo(name = "max_output_utf8_bytes") val maxOutputUtf8Bytes: Int,
    @ColumnInfo(name = "artifact_sha256") val artifactSha256: String,
    @ColumnInfo(name = "verification_report_wire") val verificationReportWire: String?,
    @ColumnInfo(name = "verified_at_ms") val verifiedAtMs: Long?,
    @ColumnInfo(name = "archived_at_ms") val archivedAtMs: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    init {
        require(id.startsWith(LearnedWorkflowCandidate.CANDIDATE_ID_PREFIX) && id.length <= 128)
        require(candidateVersion > 0L && stateVersion >= candidateVersion)
        require(LearnedWorkflowCandidateState.entries.any { it.name == state })
        require(assistantId.length == 36)
        require(sourcePolicyId.isSafeWorkflowIdentifier())
        require(sourcePolicyRevision > 0L)
        listOf(sourcePolicyArtifactSha256, sourceGrantDigest, artifactSha256).forEach {
            require(it.isCanonicalWorkflowSha256())
        }
        require(positiveAnchorEvidenceId.isSafeWorkflowIdentifier())
        listOf(
            evidenceIdsWire,
            canonicalTemplateJson,
            typedSlotsWire,
            capabilitySnapshotWire,
            toolSchemaFingerprintsWire,
        ).forEach { require(it.toByteArray(Charsets.UTF_8).size in 2..MAX_WIRE_BYTES) }
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
        require(maxOutputUtf8Bytes in 1..LearnedWorkflowCandidate.MAX_OUTPUT_UTF8_BYTES)
        require(verificationReportWire == null ||
            verificationReportWire.toByteArray(Charsets.UTF_8).size in 2..MAX_REPORT_BYTES)
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs)
        require(verifiedAtMs == null || verifiedAtMs in createdAtMs..updatedAtMs)
        require(archivedAtMs == null || archivedAtMs in createdAtMs..updatedAtMs)
        require((state == LearnedWorkflowCandidateState.ARCHIVED.name) == (archivedAtMs != null))
        if (state == LearnedWorkflowCandidateState.PROPOSED.name) {
            require(verificationReportWire == null && verifiedAtMs == null)
        }
    }

    override fun toString(): String =
        "LearnedWorkflowCandidateEntity(state=$state, candidateVersion=$candidateVersion, " +
            "stateVersion=$stateVersion, payload=<redacted>, ids=<redacted>)"
}

fun LearnedWorkflowCandidate.toEntity(): LearnedWorkflowCandidateEntity =
    LearnedWorkflowCandidateEntity(
        id = id,
        candidateVersion = candidateVersion,
        stateVersion = stateVersion,
        state = state.name,
        assistantId = assistantId,
        authoritySubjectId = authoritySubjectId,
        sourcePolicyId = sourcePolicyId,
        sourcePolicyRevision = sourcePolicyRevision,
        sourcePolicyArtifactSha256 = sourcePolicyArtifactSha256,
        sourceGrantDigest = sourceGrantDigest,
        positiveAnchorEvidenceId = positiveAnchorEvidenceId,
        evidenceIdsWire = STRICT_JSON.encodeToString(evidenceIds),
        canonicalTemplateJson = canonicalTemplateJson,
        typedSlotsWire = WorkflowArtifactCanonicalizer.canonicalSlots(typedSlots),
        capabilitySnapshotWire = WorkflowArtifactCanonicalizer.canonicalCapabilities(
            capabilitySnapshot,
        ),
        toolSchemaFingerprintsWire = WorkflowArtifactCanonicalizer.canonicalToolSchemas(
            toolSchemaFingerprints,
        ),
        producerProviderIdentity = producerProviderIdentity,
        producerModelIdentity = producerModelIdentity,
        producerConfigurationIdentity = producerConfigurationIdentity,
        producerConfigGeneration = producerConfigGeneration,
        compilerVersion = compilerVersion,
        promptVersion = promptVersion,
        templateVersion = templateVersion,
        validatorVersion = validatorVersion,
        verifierVersion = verifierVersion,
        maxOutputUtf8Bytes = maxOutputUtf8Bytes,
        artifactSha256 = artifactSha256,
        verificationReportWire = verificationReport?.let {
            STRICT_JSON.encodeToString(it)
        },
        verifiedAtMs = verifiedAtMs,
        archivedAtMs = archivedAtMs,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

fun LearnedWorkflowCandidateEntity.toDomainOrNull(): LearnedWorkflowCandidate? = runCatching {
    val evidenceIds = STRICT_JSON.decodeFromString<List<String>>(evidenceIdsWire)
    val typedSlots = STRICT_JSON.decodeFromString<List<LearnedWorkflowTypedSlot>>(typedSlotsWire)
    val capabilities = STRICT_JSON.decodeFromString<List<String>>(capabilitySnapshotWire)
    val schemas = STRICT_JSON.decodeFromString<List<LearnedWorkflowToolSchemaFingerprint>>(
        toolSchemaFingerprintsWire,
    )
    val report = verificationReportWire?.let {
        STRICT_JSON.decodeFromString<LearnedWorkflowVerificationReport>(it)
    }
    require(evidenceIds.distinct().sorted() == evidenceIds)
    require(typedSlots.sortedBy { it.name } == typedSlots)
    require(capabilities.distinct().sorted() == capabilities)
    require(schemas.sortedBy { it.actionIndex } == schemas)
    LearnedWorkflowCandidate(
        id,
        candidateVersion,
        stateVersion,
        LearnedWorkflowCandidateState.valueOf(state),
        assistantId,
        authoritySubjectId,
        sourcePolicyId,
        sourcePolicyRevision,
        sourcePolicyArtifactSha256,
        sourceGrantDigest,
        positiveAnchorEvidenceId,
        evidenceIds,
        canonicalTemplateJson,
        typedSlots,
        capabilities,
        schemas,
        producerProviderIdentity,
        producerModelIdentity,
        producerConfigurationIdentity,
        producerConfigGeneration,
        compilerVersion,
        promptVersion,
        templateVersion,
        validatorVersion,
        verifierVersion,
        maxOutputUtf8Bytes,
        artifactSha256,
        report,
        verifiedAtMs,
        archivedAtMs,
        createdAtMs,
        updatedAtMs,
    )
}.getOrNull()

private val STRICT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}
private const val MAX_WIRE_BYTES = 64 * 1_024
private const val MAX_REPORT_BYTES = 16 * 1_024
