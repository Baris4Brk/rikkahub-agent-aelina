package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable, content-free identity of one exact provider configuration cohort.
 *
 * Credentials, endpoint strings, model names and serialized provider configuration are
 * deliberately absent. An exact identity tuple is reused; its globally monotonic generation is
 * allocated only when that tuple first appears.
 */
@Entity(
    tableName = "learning_provider_config_cohorts",
    indices = [
        Index(
            value = [
                "provider_kind",
                "provider_identity_sha256",
                "model_identity_sha256",
                "configuration_identity_sha256",
            ],
            unique = true,
        ),
        Index(value = ["configuration_generation"], unique = true),
        Index(value = ["created_at_ms", "id"]),
    ],
)
data class LearningProviderConfigCohortEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "provider_kind")
    val providerKind: String,
    @ColumnInfo(name = "provider_identity_sha256")
    val providerIdentitySha256: String,
    @ColumnInfo(name = "model_identity_sha256")
    val modelIdentitySha256: String,
    @ColumnInfo(name = "configuration_identity_sha256")
    val configurationIdentitySha256: String,
    @ColumnInfo(name = "configuration_generation")
    val configurationGeneration: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    init {
        requireLearningStorageId(id, "provider cohort ID")
        require(providerKind == "local_litert" || providerKind == "remote") {
            "Unsupported provider cohort kind"
        }
        requireSha256(providerIdentitySha256, "provider cohort provider identity")
        requireSha256(modelIdentitySha256, "provider cohort model identity")
        requireSha256(configurationIdentitySha256, "provider cohort configuration identity")
        require(configurationGeneration > 0L) { "Provider configuration generation must be positive" }
        require(createdAtMs >= 0L) { "Negative provider cohort creation time" }
    }

    override fun toString(): String =
        "LearningProviderConfigCohortEntity(kind=$providerKind, generation=$configurationGeneration, identity=<redacted>)"
}
