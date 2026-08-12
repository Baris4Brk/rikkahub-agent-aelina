package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DREAM_SNAPSHOT_SCHEMA_VERSION
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotSection
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState

internal object DreamRuntimeTestFixtures {
    const val NOW = 1_720_000_000_000L
    const val SNAPSHOT_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    const val CLAIM_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val CLAIM_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    const val CLAIM_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    val scope = DreamScopeId.requireCanonical("11111111-1111-4111-8111-111111111111")

    fun claim(
        id: String = CLAIM_A,
        revision: Long = 1L,
        section: DreamSnapshotSection = DreamSnapshotSection.CURRENT_PROJECTS,
        ordinal: Int = 0,
        snapshotState: DreamClaimState = DreamClaimState.ACTIVE_CONTEXTUAL,
        currentState: DreamClaimState? = DreamClaimState.ACTIVE_CONTEXTUAL,
        currentRevision: Long? = revision,
        storageClass: DreamStorageClass = DreamStorageClass.EPISODIC,
        epistemicType: DreamEpistemicType = DreamEpistemicType.PROJECT_STATE,
        title: String = "离线记忆项目",
        statement: String = "用户当前正在实现离线 Android 记忆系统。",
        temporalState: TemporalState = TemporalState.CURRENT,
        validFromEpochMs: Long? = NOW - 1_000L,
        validToEpochMs: Long? = NOW + 1_000L,
        versionHash: DreamSha256 = DreamSha256("2".repeat(64)),
        currentVersionHash: DreamSha256? = versionHash,
        fragmentIntegrity: DreamRuntimeFragmentIntegrity = DreamRuntimeFragmentIntegrity.VERIFIED,
        sourceValidity: DreamRuntimeSourceValidity = DreamRuntimeSourceValidity.CURRENT_CONFIRMED,
        sourceCheckedAtEpochMs: Long = NOW,
        directSourceCount: Int = 1,
        directSupportingSourceCount: Int = directSourceCount,
        indirectSourceCount: Int = 0,
        scopeId: DreamScopeId = scope,
    ) = DreamRuntimeClaimProjection(
        ref = DreamRuntimeClaimRef(id, revision),
        scopeId = scopeId,
        section = section,
        ordinal = ordinal,
        snapshotState = snapshotState,
        currentState = currentState,
        currentRevision = currentRevision,
        currentVersionHash = currentVersionHash,
        storageClass = storageClass,
        epistemicType = epistemicType,
        title = title,
        statement = statement,
        confidencePermille = 900,
        temporalState = temporalState,
        validFromEpochMs = validFromEpochMs,
        validToEpochMs = validToEpochMs,
        versionHash = versionHash,
        fragmentIntegrity = fragmentIntegrity,
        sourceFence = DreamRuntimeSourceFence(
            validity = sourceValidity,
            validatedAtEpochMs = sourceCheckedAtEpochMs,
            validatedClaimRevision = revision,
            directAuthoritySourceCount = directSourceCount,
            directSupportingSourceCount = directSupportingSourceCount,
            indirectDerivedSourceCount = indirectSourceCount,
        ),
    )

    fun projection(
        claims: List<DreamRuntimeClaimProjection> = listOf(claim()),
        scopeId: DreamScopeId = scope,
        snapshotId: String = SNAPSHOT_ID,
        activeSnapshotId: String? = snapshotId,
        status: DreamRuntimeSnapshotStatus = DreamRuntimeSnapshotStatus.ACTIVE,
        snapshotRevision: Long = 3L,
        sourceMemoryEpoch: Long = 7L,
        currentMemoryEpoch: Long = sourceMemoryEpoch,
        committedDreamRevision: Long = snapshotRevision,
        currentDreamRevision: Long = committedDreamRevision,
        payloadIntegrity: DreamRuntimePayloadIntegrity = DreamRuntimePayloadIntegrity.VERIFIED,
        readConsistency: DreamRuntimeReadConsistency = DreamRuntimeReadConsistency.ATOMIC,
        expectedClaimCount: Int = claims.size,
    ) = DreamSnapshotProjection.Available(
        scopeId = scopeId,
        schemaVersion = DREAM_SNAPSHOT_SCHEMA_VERSION,
        snapshotId = snapshotId,
        activeSnapshotId = activeSnapshotId,
        snapshotStatus = status,
        snapshotRevision = snapshotRevision,
        sourceMemoryEpoch = sourceMemoryEpoch,
        currentMemoryEpoch = currentMemoryEpoch,
        committedDreamRevision = committedDreamRevision,
        currentDreamRevision = currentDreamRevision,
        payloadHash = DreamSha256("1".repeat(64)),
        payloadIntegrity = payloadIntegrity,
        snapshotCompilerRevision = "compiler-v1",
        expectedClaimCount = expectedClaimCount,
        readConsistency = readConsistency,
        claims = claims,
    )

    fun request(
        projection: DreamSnapshotProjection = projection(),
        useDreams: Boolean = true,
        maxTokens: Int = ABSOLUTE_DREAM_RUNTIME_MAX_TOKENS,
        maxChars: Int = ABSOLUTE_DREAM_RUNTIME_MAX_CHARS,
        maxUtf8Bytes: Int = ABSOLUTE_DREAM_RUNTIME_MAX_UTF8_BYTES,
        maxClaims: Int = ABSOLUTE_DREAM_RUNTIME_MAX_CLAIMS,
        ranking: DreamRuntimeRanking = DreamRuntimeRanking.SnapshotOrder,
        estimator: DreamRuntimeTokenEstimator = DreamRuntimeTokenEstimator { it.length },
    ) = DreamContextCompileRequest(
        useDreams = useDreams,
        expectedScopeId = scope,
        projection = projection,
        frozenNowEpochMs = NOW,
        limits = DreamRuntimeCompileLimits(
            maxTokens = maxTokens,
            maxChars = maxChars,
            maxUtf8Bytes = maxUtf8Bytes,
            maxClaims = maxClaims,
        ),
        ranking = ranking,
        tokenEstimator = estimator,
    )
}
