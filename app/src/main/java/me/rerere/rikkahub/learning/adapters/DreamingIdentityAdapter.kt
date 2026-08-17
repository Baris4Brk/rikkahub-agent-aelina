package me.rerere.rikkahub.learning.adapters

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.learning.api.ActiveIdentityBlock
import me.rerere.rikkahub.learning.api.IdentityContextProvider
import me.rerere.rikkahub.learning.api.IdentityContextItem
import me.rerere.rikkahub.learning.api.IdentityContextKind
import me.rerere.rikkahub.learning.api.IdentityContextRequest
import me.rerere.rikkahub.learning.api.IdentityContextResult
import me.rerere.rikkahub.learning.api.IdentityContextUnavailableReason
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeFenceResult
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeFenceValidator
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeSelectionRequest
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeSelectionResult
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeSelector
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjection
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReadRequest
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReader
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionUnavailableReason
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingFeatureFlagSource

/**
 * Read-only Learning adapter over Dreaming's frozen runtime projection API.
 *
 * It never opens a Dream/Memory DAO. Only an assistant Learning scope has an exact V1 Memory
 * authority counterpart. AUTHORITY_SUBJECT is deliberately not interpreted as an assistant UUID
 * or widened to global memory. The returned block contains only already-synthesized, source-fenced
 * Claim statements; Snapshot/Claim/Memory IDs and source rows never cross this boundary.
 */
class DreamingIdentityAdapter(
    private val featureFlags: DreamingFeatureFlagSource,
    private val projectionReader: DreamSnapshotProjectionReader,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : IdentityContextProvider {
    init {
        require(timeoutMs in 1L..MAX_TIMEOUT_MS) { "Unsafe Dream identity timeout" }
    }

    override suspend fun queryRelevantIdentity(
        request: IdentityContextRequest,
    ): IdentityContextResult {
        val dreamScope = request.expectedScope.toExactDreamScopeOrNull()
            ?: return unavailable(IdentityContextUnavailableReason.SCOPE_NOT_REPRESENTABLE)

        val loaded = try {
            withTimeoutOrNull(timeoutMs) {
                runCatchingDreamIdentityLoad {
                    val flags = featureFlags.flagsFor(dreamScope)
                    if (!flags.schemaReady || !flags.use || flags.shadow) {
                        DreamIdentityLoad.Disabled
                    } else {
                        DreamIdentityLoad.Loaded(
                            projectionReader.read(
                                DreamSnapshotProjectionReadRequest(
                                    scopeId = dreamScope,
                                    frozenNowEpochMs = request.frozenNowEpochMs,
                                ),
                            ),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } ?: return unavailable(IdentityContextUnavailableReason.TIMEOUT)

        if (loaded === DreamIdentityLoad.Disabled) {
            return unavailable(IdentityContextUnavailableReason.DISABLED)
        }
        if (loaded === DreamIdentityLoad.Failed) {
            return unavailable(IdentityContextUnavailableReason.SOURCE_FAILURE)
        }
        val projection = (loaded as DreamIdentityLoad.Loaded).projection
        if (projection is DreamSnapshotProjection.Unavailable) {
            return unavailable(projection.reason.toIdentityUnavailableReason())
        }

        val fence = DreamRuntimeFenceValidator.validate(
            projection = projection,
            expectedScopeId = dreamScope,
        )
        if (fence !is DreamRuntimeFenceResult.Valid) {
            return unavailable(IdentityContextUnavailableReason.INVALID_PROJECTION)
        }
        val selection = DreamRuntimeSelector.select(
            DreamRuntimeSelectionRequest(
                fence = fence,
                frozenNowEpochMs = request.frozenNowEpochMs,
            ),
        )
        if (selection !is DreamRuntimeSelectionResult.Selected) {
            return unavailable(IdentityContextUnavailableReason.INVALID_PROJECTION)
        }

        // TaskSignature is intentionally not reverse-mapped to text. Until Dreaming owns a
        // scope-safe ranker for that opaque hint, canonical Snapshot order is the only authority-
        // preserving order. Bounds are whole-item: no Claim statement is sliced or normalized.
        val items = ArrayList<IdentityContextItem>(request.budget.maxItems)
        var usedChars = 0
        for (claim in selection.selection.claims) {
            if (items.size >= request.budget.maxItems) break
            val kind = claim.epistemicType.toIdentityKindOrNull() ?: continue
            val item = try {
                IdentityContextItem(kind = kind, text = claim.statement)
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (item.text.length > request.budget.maxChars - usedChars) continue
            items += item
            usedChars += item.text.length
        }
        if (items.isEmpty()) {
            return unavailable(IdentityContextUnavailableReason.NO_RELEVANT_CONTEXT)
        }
        return IdentityContextResult.Available(ActiveIdentityBlock(items))
    }

    override fun toString(): String = "DreamingIdentityAdapter(publicReadApi=ready)"

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val MAX_TIMEOUT_MS = 10_000L
    }
}

private sealed interface DreamIdentityLoad {
    data object Disabled : DreamIdentityLoad
    data object Failed : DreamIdentityLoad

    data class Loaded(val projection: DreamSnapshotProjection) : DreamIdentityLoad
}

private suspend fun runCatchingDreamIdentityLoad(
    block: suspend () -> DreamIdentityLoad,
): DreamIdentityLoad = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DreamIdentityLoad.Failed
}

private fun LearningScope.toExactDreamScopeOrNull(): DreamScopeId? = when (this) {
    is LearningScope.Assistant -> DreamScopeId.privateScope(assistantId)
    is LearningScope.AuthoritySubject -> null
}

private fun DreamEpistemicType.toIdentityKindOrNull(): IdentityContextKind? = when (this) {
    DreamEpistemicType.PROJECT_STATE -> IdentityContextKind.CURRENT_PROJECT
    DreamEpistemicType.PLAN -> IdentityContextKind.ACTIVE_PLAN
    DreamEpistemicType.CONSTRAINT -> IdentityContextKind.ACTIVE_CONSTRAINT
    DreamEpistemicType.OBSERVATION,
    DreamEpistemicType.BELIEF,
    DreamEpistemicType.PREFERENCE_SUMMARY,
    -> null
}

private fun DreamSnapshotProjectionUnavailableReason.toIdentityUnavailableReason():
    IdentityContextUnavailableReason = when (this) {
    DreamSnapshotProjectionUnavailableReason.DATABASE_READ_FAILED ->
        IdentityContextUnavailableReason.SOURCE_FAILURE

    DreamSnapshotProjectionUnavailableReason.PAYLOAD_PARSE_FAILED,
    DreamSnapshotProjectionUnavailableReason.PAYLOAD_HASH_INVALID,
    DreamSnapshotProjectionUnavailableReason.MANIFEST_INVALID,
    DreamSnapshotProjectionUnavailableReason.CLAIM_VERSION_MISSING,
    DreamSnapshotProjectionUnavailableReason.UNKNOWN_SCHEMA,
    DreamSnapshotProjectionUnavailableReason.UNKNOWN,
    -> IdentityContextUnavailableReason.INVALID_PROJECTION

    DreamSnapshotProjectionUnavailableReason.FEATURE_NOT_READY,
    DreamSnapshotProjectionUnavailableReason.SCOPE_STATE_MISSING,
    DreamSnapshotProjectionUnavailableReason.ACTIVE_SNAPSHOT_MISSING,
    DreamSnapshotProjectionUnavailableReason.SNAPSHOT_ROW_MISSING,
    -> IdentityContextUnavailableReason.PROJECTION_UNAVAILABLE
}

private fun unavailable(reason: IdentityContextUnavailableReason): IdentityContextResult =
    IdentityContextResult.Unavailable(reason)
