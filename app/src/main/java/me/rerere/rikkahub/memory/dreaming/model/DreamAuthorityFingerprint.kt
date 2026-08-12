package me.rerere.rikkahub.memory.dreaming.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Canonical semantic fingerprint. It intentionally does not reuse MemoryEntity.contentHash. */
object DreamAuthorityFingerprintV1 {
    fun compute(memory: DreamAuthorityMemory): DreamSha256 = DreamCanonicalJson.sha256(
        JsonObject(
            canonicalMapOf(
                "approval_source" to JsonPrimitive(memory.approvalSource.name),
                "attribution" to JsonPrimitive(memory.attribution.name),
                "content" to JsonPrimitive(normalizeDreamText(memory.content)),
                "created_at_epoch_ms" to JsonPrimitive(memory.createdAtEpochMs),
                "expires_at_epoch_ms" to memory.expiresAtEpochMs.jsonNumberOrNull(),
                "fingerprint_version" to JsonPrimitive(DREAM_AUTHORITY_FINGERPRINT_VERSION),
                "kind" to JsonPrimitive(memory.kind.name),
                "lifecycle_status" to JsonPrimitive(memory.lifecycleStatus.name),
                "memory_id" to JsonPrimitive(memory.memoryId),
                "occurred_at_epoch_ms" to memory.occurredAtEpochMs.jsonNumberOrNull(),
                "origin_assistant_id" to memory.originAssistantId.jsonStringOrNull(),
                "outcome" to memory.outcome.jsonStringOrNull(),
                "participants" to canonicalStringArray(memory.participants, sort = true),
                "revision" to JsonPrimitive(memory.revision),
                "scope_id" to JsonPrimitive(memory.scopeId.value),
                "source_manifest_hash" to JsonPrimitive(sourceManifestHash(memory.sources).value),
                "tags" to canonicalStringArray(memory.tags, sort = true),
                "title" to memory.title.jsonStringOrNull(),
                "tombstoned" to JsonPrimitive(memory.tombstoned),
                "truth_status" to JsonPrimitive(memory.truthStatus.name),
                "updated_at_epoch_ms" to JsonPrimitive(memory.updatedAtEpochMs),
            ),
        ),
    )

    fun sourceManifestHash(sources: List<DreamAuthoritySource>): DreamSha256 =
        DreamCanonicalJson.sha256(
            JsonArray(
                sources
                    .sortedWith(
                        compareBy<DreamAuthoritySource>(
                            { it.conversationId },
                            { it.messageId },
                            { it.role.name },
                            { it.sourceKind.name },
                            { it.consumedTextDigest.value },
                            { it.evidenceGroupId },
                        ),
                    )
                    .map { source ->
                        JsonObject(
                            canonicalMapOf(
                                "consumed_text_digest" to JsonPrimitive(source.consumedTextDigest.value),
                                "conversation_id" to JsonPrimitive(source.conversationId),
                                "evidence_group_id" to JsonPrimitive(source.evidenceGroupId),
                                "message_id" to JsonPrimitive(source.messageId),
                                "role" to JsonPrimitive(source.role.name),
                                "source_kind" to JsonPrimitive(source.sourceKind.name),
                            ),
                        )
                    },
            ),
        )
}

data class DreamCanonicalClaimVersion(
    val canonicalClaimJson: String,
    val contentHash: DreamSha256,
    val sourceManifestJson: String,
    val sourceManifestHash: DreamSha256,
)

/** Single canonical codec shared by validation, Room persistence, audit, and tests. */
object DreamClaimVersionCanonicalV1 {
    fun encode(version: DreamValidatedClaimVersion): DreamCanonicalClaimVersion {
        val sourceManifest = JsonArray(
            version.sources
                .sortedWith(
                    compareBy<DreamClaimSourcePin>(
                        { it.authority.scopeId.value },
                        { it.authority.memoryId },
                        { it.authority.expectedRevision },
                        { it.supportType.name },
                    ),
                )
                .map { source ->
                    JsonObject(
                        canonicalMapOf(
                            "authority_fingerprint" to JsonPrimitive(
                                source.authority.expectedAuthorityFingerprint.value,
                            ),
                            "direct_authority" to JsonPrimitive(source.directAuthority),
                            "memory_id" to JsonPrimitive(source.authority.memoryId),
                            "memory_revision" to JsonPrimitive(source.authority.expectedRevision),
                            "scope_id" to JsonPrimitive(source.authority.scopeId.value),
                            "source_manifest_hash" to JsonPrimitive(
                                source.authority.expectedSourceManifestHash.value,
                            ),
                            "support_type" to JsonPrimitive(source.supportType.name),
                        ),
                    )
                },
        )
        val sourceManifestHash = DreamCanonicalJson.sha256(sourceManifest)
        val canonical = JsonObject(
            canonicalMapOf(
                "claim_id" to JsonPrimitive(version.claimId),
                "claim_key" to JsonPrimitive(version.claimKey),
                "confidence_permille" to JsonPrimitive(version.confidencePermille),
                "epistemic_type" to JsonPrimitive(version.epistemicType.name),
                "reason" to JsonPrimitive(version.reason.name),
                "revision" to JsonPrimitive(version.nextRevision),
                "source_manifest_hash" to JsonPrimitive(sourceManifestHash.value),
                "state" to JsonPrimitive(version.nextState.name),
                "statement" to JsonPrimitive(normalizeDreamText(version.statement)),
                "storage_class" to JsonPrimitive(version.storageClass.name),
                "temporal_state" to JsonPrimitive(version.temporalState.name),
                "title" to JsonPrimitive(normalizeDreamText(version.title)),
                "valid_from_epoch_ms" to version.validFromEpochMs.jsonNumberOrNull(),
                "valid_to_epoch_ms" to version.validToEpochMs.jsonNumberOrNull(),
            ),
        )
        return DreamCanonicalClaimVersion(
            canonicalClaimJson = DreamCanonicalJson.encode(canonical),
            contentHash = DreamCanonicalJson.sha256(canonical),
            sourceManifestJson = DreamCanonicalJson.encode(sourceManifest),
            sourceManifestHash = sourceManifestHash,
        )
    }
}

/** Shared byte-level canonicalization for fingerprints, manifests, and compiled snapshots. */
object DreamCanonicalJson {
    fun encode(element: JsonElement): String = element.toString()

    fun sha256(element: JsonElement): DreamSha256 = sha256(encode(element).toByteArray(StandardCharsets.UTF_8))

    fun sha256(bytes: ByteArray): DreamSha256 = DreamSha256(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) },
    )
}

fun canonicalMapOf(vararg entries: Pair<String, JsonElement>): Map<String, JsonElement> =
    entries.sortedBy { it.first }.associateTo(linkedMapOf()) { it }

fun canonicalStringArray(values: List<String>, sort: Boolean): JsonArray {
    val normalized = values.map(::normalizeDreamText).let { if (sort) it.sorted() else it }
    return JsonArray(normalized.map(::JsonPrimitive))
}

fun String?.jsonStringOrNull(): JsonElement = this?.let { JsonPrimitive(normalizeDreamText(it)) } ?: JsonNull

fun Long?.jsonNumberOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
