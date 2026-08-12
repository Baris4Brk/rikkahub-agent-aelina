package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Versioned, sanitized reflection artifact. It is not a provider transcript or raw reflection. */
@Entity(
    tableName = "learning_episode_lessons",
    primaryKeys = ["episode_id", "lesson_version"],
    foreignKeys = [
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episode_id", "state", "created_at_ms"]),
        Index(value = ["scope_kind", "scope_id", "state", "created_at_ms"]),
        Index(value = ["artifact_sha256"]),
    ],
)
data class LearningEpisodeLessonEntity(
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "lesson_version")
    val lessonVersion: Int,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "lesson_type")
    val lessonType: String,
    @ColumnInfo(name = "trigger_summary")
    val triggerSummary: String,
    @ColumnInfo(name = "observation_summary")
    val observationSummary: String,
    @ColumnInfo(name = "lesson_summary")
    val lessonSummary: String,
    @ColumnInfo(name = "boundary_summary")
    val boundarySummary: String,
    @ColumnInfo(name = "evidence_manifest_sha256")
    val evidenceManifestSha256: String,
    @ColumnInfo(name = "artifact_sha256")
    val artifactSha256: String,
    @ColumnInfo(name = "producer_provider_identity")
    val producerProviderIdentity: String,
    @ColumnInfo(name = "producer_provider_kind")
    val producerProviderKind: String,
    @ColumnInfo(name = "producer_model_identity")
    val producerModelIdentity: String,
    @ColumnInfo(name = "producer_configuration_identity")
    val producerConfigurationIdentity: String,
    @ColumnInfo(name = "producer_config_generation")
    val producerConfigGeneration: Long,
    @ColumnInfo(name = "algorithm_identity")
    val algorithmIdentity: String,
    @ColumnInfo(name = "prompt_identity")
    val promptIdentity: String,
    @ColumnInfo(name = "template_identity")
    val templateIdentity: String,
    @ColumnInfo(name = "schema_identity")
    val schemaIdentity: String,
    @ColumnInfo(name = "input_token_count")
    val inputTokenCount: Long?,
    @ColumnInfo(name = "output_token_count")
    val outputTokenCount: Long?,
    @ColumnInfo(name = "estimated_cost_micros")
    val estimatedCostMicros: Long?,
    @ColumnInfo(name = "remote_provider")
    val remoteProvider: Boolean?,
    val state: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        requireLearningStorageId(episodeId, "lesson episode ID")
        require(lessonVersion > 0) { "Invalid lesson version" }
        requireLearningScope(scopeKind, scopeId)
        require(LearningLessonType.entries.any { it.name == lessonType }) { "Invalid lesson type" }
        requireBoundedRedactedText(triggerSummary, "lesson trigger")
        requireBoundedRedactedText(observationSummary, "lesson observation")
        requireBoundedRedactedText(lessonSummary, "lesson summary")
        requireBoundedRedactedText(boundarySummary, "lesson boundary")
        requireSha256(evidenceManifestSha256, "lesson evidence manifest")
        requireSha256(artifactSha256, "lesson artifact")
        listOf(
            producerProviderIdentity,
            producerModelIdentity,
            producerConfigurationIdentity,
            algorithmIdentity,
            promptIdentity,
            templateIdentity,
            schemaIdentity,
        ).forEach { requireLearningIdentity(it, "lesson producer identity") }
        require(producerProviderKind in setOf("local_litert", "remote")) {
            "Invalid lesson provider kind"
        }
        requireSha256(producerProviderIdentity, "lesson provider identity")
        requireSha256(producerModelIdentity, "lesson model identity")
        requireSha256(producerConfigurationIdentity, "lesson configuration identity")
        require(producerConfigGeneration >= 0L) { "Negative producer config generation" }
        listOfNotNull(inputTokenCount, outputTokenCount, estimatedCostMicros).forEach {
            require(it >= 0L) { "Negative lesson receipt aggregate" }
        }
        require(LearningLessonState.entries.any { it.name == state }) { "Invalid lesson state" }
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs) { "Invalid lesson clock" }
    }

    override fun toString(): String =
        "LearningEpisodeLessonEntity(version=$lessonVersion, type=$lessonType, state=$state, text=<redacted>, ids=<redacted>)"
}
