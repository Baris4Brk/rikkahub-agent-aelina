package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Frozen, content-free provider manifest for exactly one durable P1 job.
 *
 * [requestHmacSha256] is a Keystore-backed, domain-separated HMAC over every provider-visible byte
 * and generation parameter; it must never be replaced with an unkeyed prompt digest.
 * [inputIdentitySha256] separately commits the bounded source projection. The runtime attestation
 * commits either the exact LiteRT artifact/runtime tuple or the remote transport capability tuple.
 */
@Entity(
    tableName = "learning_provider_job_manifests",
    foreignKeys = [
        ForeignKey(
            entity = LearningJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningProviderConfigCohortEntity::class,
            parentColumns = ["id"],
            childColumns = ["cohort_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["cohort_id", "frozen_at_ms", "job_id"]),
        Index(value = ["provider_request_key"], unique = true),
        Index(value = ["request_hmac_sha256"]),
        Index(value = ["runtime_attestation_sha256"]),
    ],
)
data class LearningProviderJobManifestEntity(
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "cohort_id")
    val cohortId: String,
    @ColumnInfo(name = "manifest_schema_version")
    val manifestSchemaVersion: Int,
    @ColumnInfo(name = "request_hmac_sha256")
    val requestHmacSha256: String,
    @ColumnInfo(name = "input_identity_sha256")
    val inputIdentitySha256: String,
    @ColumnInfo(name = "runtime_attestation_sha256")
    val runtimeAttestationSha256: String,
    @ColumnInfo(name = "redaction_policy_identity")
    val redactionPolicyIdentity: String,
    @ColumnInfo(name = "field_categories_identity")
    val fieldCategoriesIdentity: String,
    @ColumnInfo(name = "token_estimator_identity")
    val tokenEstimatorIdentity: String,
    @ColumnInfo(name = "provider_request_key")
    val providerRequestKey: String,
    @ColumnInfo(name = "input_utf8_bytes")
    val inputUtf8Bytes: Long,
    @ColumnInfo(name = "max_input_utf8_bytes")
    val maxInputUtf8Bytes: Long,
    @ColumnInfo(name = "estimated_input_tokens")
    val estimatedInputTokens: Long,
    @ColumnInfo(name = "max_output_tokens")
    val maxOutputTokens: Long,
    @ColumnInfo(name = "max_output_utf8_bytes")
    val maxOutputUtf8Bytes: Long,
    @ColumnInfo(name = "max_provider_calls")
    val maxProviderCalls: Int,
    @ColumnInfo(name = "max_cost_micros")
    val maxCostMicros: Long,
    @ColumnInfo(name = "timeout_ms")
    val timeoutMs: Long,
    @ColumnInfo(name = "frozen_at_ms")
    val frozenAtMs: Long,
) {
    init {
        requireLearningStorageId(jobId, "provider manifest job ID")
        requireLearningStorageId(cohortId, "provider manifest cohort ID")
        require(manifestSchemaVersion == PROVIDER_JOB_MANIFEST_SCHEMA_VERSION) {
            "Unsupported provider job manifest schema"
        }
        requireSha256(requestHmacSha256, "provider request HMAC")
        requireSha256(inputIdentitySha256, "provider input identity")
        requireSha256(runtimeAttestationSha256, "provider dispatch attestation")
        requireLearningIdentity(redactionPolicyIdentity, "provider redaction policy identity")
        requireLearningIdentity(fieldCategoriesIdentity, "provider field categories identity")
        requireLearningIdentity(tokenEstimatorIdentity, "provider token estimator identity")
        requireLearningIdentity(providerRequestKey, "provider request key")
        require(
            inputUtf8Bytes > 0L && maxInputUtf8Bytes >= inputUtf8Bytes &&
                maxInputUtf8Bytes <= PROVIDER_MANIFEST_MAX_INPUT_UTF8_BYTES
        ) {
            "Provider input exceeds its frozen byte cap"
        }
        require(estimatedInputTokens > 0L) { "Provider input token estimate must be positive" }
        require(maxOutputTokens in 1L..PROVIDER_MANIFEST_MAX_OUTPUT_TOKENS) {
            "Provider output token cap is outside the runtime hard bound"
        }
        require(maxOutputUtf8Bytes in 1L..PROVIDER_MANIFEST_MAX_OUTPUT_UTF8_BYTES) {
            "Provider output byte cap is outside the runtime hard bound"
        }
        require(maxProviderCalls == 1) { "A P1 provider job permits exactly one call per attempt" }
        require(maxCostMicros >= 0L) { "Negative provider cost cap" }
        require(timeoutMs in 1L..PROVIDER_MANIFEST_MAX_TIMEOUT_MS) {
            "Provider timeout is outside the runtime hard bound"
        }
        require(frozenAtMs >= 0L) { "Negative provider manifest freeze time" }
    }

    /** Generic API over the v1 storage column retained to avoid a destructive schema rename. */
    @get:Ignore
    val dispatchAttestationSha256: String
        get() = runtimeAttestationSha256

    override fun toString(): String =
        "LearningProviderJobManifestEntity(schema=$manifestSchemaVersion, " +
            "inputUtf8Bytes=$inputUtf8Bytes/$maxInputUtf8Bytes, " +
            "maxOutputTokens=$maxOutputTokens, maxProviderCalls=$maxProviderCalls, " +
            "maxCostMicros=$maxCostMicros, identities=<redacted>)"
}

const val PROVIDER_JOB_MANIFEST_SCHEMA_VERSION: Int = 1
private const val PROVIDER_MANIFEST_MAX_INPUT_UTF8_BYTES = 160L * 1_024L
private const val PROVIDER_MANIFEST_MAX_OUTPUT_UTF8_BYTES = 128L * 1_024L
private const val PROVIDER_MANIFEST_MAX_OUTPUT_TOKENS = 8_192L
private const val PROVIDER_MANIFEST_MAX_TIMEOUT_MS = 2L * 60_000L
