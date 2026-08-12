package me.rerere.rikkahub.memory.dreaming.snapshot

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.memory.dreaming.model.DREAM_SNAPSHOT_SCHEMA_VERSION
import me.rerere.rikkahub.memory.dreaming.model.DreamCanonicalJson
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimHead
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.canonicalMapOf
import me.rerere.rikkahub.memory.dreaming.model.jsonNumberOrNull
import me.rerere.rikkahub.memory.dreaming.model.normalizeDreamText

enum class DreamSnapshotSection(val wireName: String, val order: Int) {
    PROFILE("profile", 0),
    CURRENT_PROJECTS("current_projects", 1),
    ACTIVE_PLANS("active_plans", 2),
    ACTIVE_CONSTRAINTS("active_constraints", 3),
    OTHER_CONTEXT("other_context", 4),
}

data class DreamSnapshotCompileRequest(
    val scopeId: DreamScopeId,
    val compilerRevision: String,
    val claims: List<DreamClaimHead>,
    val limits: DreamSnapshotCompileLimits = DreamSnapshotCompileLimits(),
) {
    init {
        require(compilerRevision.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(claims.size <= 10_000)
        require(claims.all { it.scopeId == scopeId })
    }
}

data class DreamSnapshotCompileLimits(
    val maxActiveClaims: Int = 1_024,
    val maxClaimFragmentUtf8Bytes: Int = 96 * 1_024,
    val maxManifestUtf8Bytes: Int = 512 * 1_024,
    val maxPayloadUtf8Bytes: Int = 2 * 1_024 * 1_024,
) {
    init {
        require(maxActiveClaims in 0..10_000)
        require(maxClaimFragmentUtf8Bytes in 1..512_000)
        require(maxManifestUtf8Bytes in 2..2_000_000)
        require(maxPayloadUtf8Bytes in 256..8_000_000)
        require(maxManifestUtf8Bytes <= maxPayloadUtf8Bytes)
    }
}

enum class DreamSnapshotCompilationFailure {
    ACTIVE_CLAIM_COUNT_LIMIT,
    CLAIM_FRAGMENT_TOO_LARGE,
    MANIFEST_TOO_LARGE,
    PAYLOAD_TOO_LARGE,
}

class DreamSnapshotCompilationException(
    val failure: DreamSnapshotCompilationFailure,
) : IllegalArgumentException(failure.name)

data class DreamSnapshotManifestEntry(
    val claimId: String,
    val claimRevision: Long,
    val section: DreamSnapshotSection,
    val ordinal: Int,
    val fragmentHash: DreamSha256,
)

/** Canonical bytes contain no snapshot ID, run ID, timestamps, provider, or mutable DB epoch. */
data class DreamCompiledSnapshot(
    val schemaVersion: Int,
    val compilerRevision: String,
    val payloadJson: String,
    val payloadHash: DreamSha256,
    val manifestJson: String,
    val manifestHash: DreamSha256,
    val manifest: List<DreamSnapshotManifestEntry>,
    val claimCount: Int,
    val estimatedTokens: Int,
) {
    init {
        require(schemaVersion == DREAM_SNAPSHOT_SCHEMA_VERSION)
        require(payloadJson.toByteArray(StandardCharsets.UTF_8).isNotEmpty())
        require(manifest.size == claimCount)
        require(estimatedTokens >= 0)
    }
}

/** Pure and deterministic: equal claim versions + compiler revision always produce equal bytes. */
object DreamSnapshotCompiler {
    fun compile(request: DreamSnapshotCompileRequest): DreamCompiledSnapshot {
        val activeUnsorted = request.claims.filter { it.state == DreamClaimState.ACTIVE_CONTEXTUAL }
        if (activeUnsorted.size > request.limits.maxActiveClaims) {
            throw DreamSnapshotCompilationException(DreamSnapshotCompilationFailure.ACTIVE_CLAIM_COUNT_LIMIT)
        }
        val active = activeUnsorted
            .sortedWith(
                compareBy<DreamClaimHead>(
                    { sectionOf(it).order },
                    { it.claimKey },
                    { it.claimId },
                    { it.revision },
                ),
            )
        val entries = mutableListOf<DreamSnapshotManifestEntry>()
        val sectionArrays = DreamSnapshotSection.entries.associateWith { mutableListOf<JsonObject>() }
        var manifestUtf8Bytes = 2L // []
        var payloadUtf8Bytes = DreamCanonicalJson.encode(
            payload(request.compilerRevision, JsonArray(emptyList()), sectionArrays),
        ).utf8Size()
        if (payloadUtf8Bytes > request.limits.maxPayloadUtf8Bytes) {
            throw DreamSnapshotCompilationException(DreamSnapshotCompilationFailure.PAYLOAD_TOO_LARGE)
        }
        active.forEach { claim ->
            val section = sectionOf(claim)
            val fragment = claimFragment(claim)
            val fragmentUtf8Bytes = DreamCanonicalJson.encode(fragment).utf8Size()
            if (fragmentUtf8Bytes > request.limits.maxClaimFragmentUtf8Bytes) {
                throw DreamSnapshotCompilationException(DreamSnapshotCompilationFailure.CLAIM_FRAGMENT_TOO_LARGE)
            }
            val ordinal = sectionArrays.getValue(section).size
            val entry = DreamSnapshotManifestEntry(
                claimId = claim.claimId,
                claimRevision = claim.revision,
                section = section,
                ordinal = ordinal,
                fragmentHash = DreamCanonicalJson.sha256(fragment),
            )
            val manifestEntry = entry.toJson()
            val manifestEntryUtf8Bytes = DreamCanonicalJson.encode(manifestEntry).utf8Size()
            val manifestSeparatorBytes = if (entries.isEmpty()) 0L else 1L
            val sectionSeparatorBytes = if (ordinal == 0) 0L else 1L
            val nextManifestUtf8Bytes = manifestUtf8Bytes + manifestSeparatorBytes + manifestEntryUtf8Bytes
            if (nextManifestUtf8Bytes > request.limits.maxManifestUtf8Bytes) {
                throw DreamSnapshotCompilationException(DreamSnapshotCompilationFailure.MANIFEST_TOO_LARGE)
            }
            val nextPayloadUtf8Bytes = payloadUtf8Bytes + manifestSeparatorBytes + manifestEntryUtf8Bytes +
                sectionSeparatorBytes + fragmentUtf8Bytes
            if (nextPayloadUtf8Bytes > request.limits.maxPayloadUtf8Bytes) {
                throw DreamSnapshotCompilationException(DreamSnapshotCompilationFailure.PAYLOAD_TOO_LARGE)
            }
            sectionArrays.getValue(section) += fragment
            entries += entry
            manifestUtf8Bytes = nextManifestUtf8Bytes
            payloadUtf8Bytes = nextPayloadUtf8Bytes
        }
        val manifestElement = JsonArray(entries.map(DreamSnapshotManifestEntry::toJson))
        val payload = payload(request.compilerRevision, manifestElement, sectionArrays)
        val payloadJson = DreamCanonicalJson.encode(payload)
        val manifestJson = DreamCanonicalJson.encode(manifestElement)
        check(payloadJson.utf8Size() == payloadUtf8Bytes) { "Snapshot UTF-8 accounting drifted" }
        check(manifestJson.utf8Size() == manifestUtf8Bytes) { "Manifest UTF-8 accounting drifted" }
        return DreamCompiledSnapshot(
            schemaVersion = DREAM_SNAPSHOT_SCHEMA_VERSION,
            compilerRevision = request.compilerRevision,
            payloadJson = payloadJson,
            payloadHash = DreamCanonicalJson.sha256(payload),
            manifestJson = manifestJson,
            manifestHash = DreamCanonicalJson.sha256(manifestElement),
            manifest = entries,
            claimCount = entries.size,
            estimatedTokens = ((payloadUtf8Bytes + 3L) / 4L).toInt(),
        )
    }

    private fun payload(
        compilerRevision: String,
        manifest: JsonArray,
        sectionArrays: Map<DreamSnapshotSection, List<JsonObject>>,
    ): JsonObject = JsonObject(
        canonicalMapOf(
            "compiler_revision" to JsonPrimitive(compilerRevision),
            "manifest" to manifest,
            "schema_version" to JsonPrimitive(DREAM_SNAPSHOT_SCHEMA_VERSION),
            "sections" to JsonObject(
                DreamSnapshotSection.entries.associateTo(linkedMapOf()) { section ->
                    section.wireName to JsonArray(sectionArrays.getValue(section))
                },
            ),
        ),
    )

    private fun claimFragment(claim: DreamClaimHead): JsonObject = JsonObject(
        canonicalMapOf(
            "claim_key" to JsonPrimitive(claim.claimKey),
            "confidence_permille" to JsonPrimitive(claim.confidencePermille),
            "epistemic_type" to JsonPrimitive(claim.epistemicType.name),
            "statement" to JsonPrimitive(normalizeDreamText(claim.statement)),
            "storage_class" to JsonPrimitive(claim.storageClass.name),
            "temporal_state" to JsonPrimitive(claim.temporalState.name),
            "title" to JsonPrimitive(normalizeDreamText(claim.title)),
            "valid_from_epoch_ms" to claim.validFromEpochMs.jsonNumberOrNull(),
            "valid_to_epoch_ms" to claim.validToEpochMs.jsonNumberOrNull(),
        ),
    )

    private fun sectionOf(claim: DreamClaimHead): DreamSnapshotSection = when {
        claim.epistemicType == DreamEpistemicType.PROJECT_STATE -> DreamSnapshotSection.CURRENT_PROJECTS
        claim.epistemicType == DreamEpistemicType.PLAN -> DreamSnapshotSection.ACTIVE_PLANS
        claim.epistemicType == DreamEpistemicType.CONSTRAINT -> DreamSnapshotSection.ACTIVE_CONSTRAINTS
        claim.storageClass == DreamStorageClass.PROFILE -> DreamSnapshotSection.PROFILE
        else -> DreamSnapshotSection.OTHER_CONTEXT
    }
}

private fun DreamSnapshotManifestEntry.toJson(): JsonObject = JsonObject(
    canonicalMapOf(
        "claim_id" to JsonPrimitive(claimId),
        "claim_revision" to JsonPrimitive(claimRevision),
        "fragment_hash" to JsonPrimitive(fragmentHash.value),
        "ordinal" to JsonPrimitive(ordinal),
        "section" to JsonPrimitive(section.wireName),
    ),
)

private fun String.utf8Size(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()
