package me.rerere.rikkahub.memory.dreaming

import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryAttribution
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryLifecycleStatus
import me.rerere.rikkahub.memory.MemorySourceKind
import me.rerere.rikkahub.memory.MemorySourceRole
import me.rerere.rikkahub.memory.MemoryTruthStatus
import me.rerere.rikkahub.memory.dreaming.input.DreamInputBuildRequest
import me.rerere.rikkahub.memory.dreaming.input.DreamInputBuilder
import me.rerere.rikkahub.memory.dreaming.input.DreamInputCandidate
import me.rerere.rikkahub.memory.dreaming.input.DreamInputCandidateOrigin
import me.rerere.rikkahub.memory.dreaming.input.DreamRunTokenFactory
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityFingerprintV1
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityMemory
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityPin
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthoritySource
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimHead
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimSourcePin
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamOpaqueToken
import me.rerere.rikkahub.memory.dreaming.model.DreamOpaqueTokenKind
import me.rerere.rikkahub.memory.dreaming.model.DreamProposalNonce
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.DreamSupportType
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisFence
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisMode
import me.rerere.rikkahub.memory.dreaming.source.DreamSourceLocator
import me.rerere.rikkahub.memory.dreaming.source.DreamSourceReadResult
import me.rerere.rikkahub.memory.dreaming.source.DreamSourceReader
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState

internal object DreamingTestFixtures {
    const val RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val CLAIM_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    const val NEW_CLAIM_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    const val SNAPSHOT_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    const val NOW = 1_720_000_000_000L

    val scope = DreamScopeId.requireCanonical("11111111-1111-4111-8111-111111111111")

    fun fence(
        baseMemoryEpoch: Long = 7,
        lastAppliedEpoch: Long = 4,
        dreamRevision: Long = 3,
        mode: DreamSynthesisMode = DreamSynthesisMode.INCREMENTAL,
    ) = DreamSynthesisFence(
        scopeId = scope,
        runId = RUN_ID,
        leaseOwner = "unit-worker",
        baseMemoryEpoch = baseMemoryEpoch,
        baseLastAppliedMemoryEpoch = lastAppliedEpoch,
        baseDreamRevision = dreamRevision,
        expectedActiveSnapshotId = null,
        frozenNowEpochMs = NOW,
        sourceTimezoneId = "Asia/Shanghai",
        mode = mode,
    )

    fun source(
        conversationId: String = "conversation-1",
        messageId: String = "message-1",
        digest: String = "1".repeat(64),
    ) = DreamAuthoritySource(
        conversationId = conversationId,
        messageId = messageId,
        role = MemorySourceRole.USER,
        sourceKind = MemorySourceKind.TEXT,
        consumedTextDigest = DreamSha256(digest),
        evidenceGroupId = "evidence-group-1",
    )

    fun memory(
        id: String = "42",
        revision: Long = 2,
        content: String = "The user is building an offline Android memory system.",
        title: String? = "Project",
        sources: List<DreamAuthoritySource> = listOf(source()),
        expiresAt: Long? = null,
    ) = DreamAuthorityMemory(
        scopeId = scope,
        memoryId = id,
        revision = revision,
        title = title,
        content = content,
        kind = MemoryKind.PROJECT_FACT,
        attribution = MemoryAttribution.USER,
        truthStatus = MemoryTruthStatus.CONFIRMED,
        lifecycleStatus = MemoryLifecycleStatus.ACTIVE,
        approvalSource = MemoryApprovalSource.USER_REVIEWED,
        tags = listOf("android", "memory"),
        createdAtEpochMs = NOW - 10_000,
        updatedAtEpochMs = NOW - 1_000,
        occurredAtEpochMs = NOW - 20_000,
        expiresAtEpochMs = expiresAt,
        originAssistantId = null,
        participants = listOf("user"),
        outcome = null,
        sources = sources,
    )

    fun pin(memory: DreamAuthorityMemory) = DreamAuthorityPin(
        scopeId = memory.scopeId,
        memoryId = memory.memoryId,
        expectedRevision = memory.revision,
        expectedAuthorityFingerprint = DreamAuthorityFingerprintV1.compute(memory),
        expectedSourceManifestHash = DreamAuthorityFingerprintV1.sourceManifestHash(memory.sources),
    )

    fun locator(memory: DreamAuthorityMemory, source: DreamAuthoritySource = memory.sources.first()) =
        DreamSourceLocator(
            scopeId = memory.scopeId,
            conversationId = source.conversationId,
            messageId = source.messageId,
            role = source.role,
            sourceKind = source.sourceKind,
            expectedConsumedTextDigest = source.consumedTextDigest,
            evidenceGroupId = source.evidenceGroupId,
        )

    fun claim(
        id: String = CLAIM_ID,
        key: String = "project.offline_memory",
        state: DreamClaimState = DreamClaimState.ACTIVE_CONTEXTUAL,
    ) = DreamClaimHead(
        claimId = id,
        scopeId = scope,
        revision = 1,
        claimKey = key,
        storageClass = DreamStorageClass.EPISODIC,
        epistemicType = DreamEpistemicType.PROJECT_STATE,
        state = state,
        title = "Offline memory project",
        statement = "The user is building an offline Android memory system.",
        confidencePermille = 900,
        temporalState = TemporalState.CURRENT,
        validFromEpochMs = NOW - 20_000,
        validToEpochMs = NOW + 20_000,
        versionHash = DreamSha256("2".repeat(64)),
        sources = memory().let { authority ->
            listOf(DreamClaimSourcePin(pin(authority), DreamSupportType.SUPPORTS, directAuthority = true))
        },
    )

    fun candidate(memory: DreamAuthorityMemory = memory(), requireReread: Boolean = true) =
        DreamInputCandidate(
            origin = DreamInputCandidateOrigin.AUTHORITY_CHANGE,
            memory = memory,
            pin = pin(memory),
            sourceLocators = memory.sources.map { locator(memory, it) },
            requireSourceReread = requireReread,
        )

    suspend fun input(
        memory: DreamAuthorityMemory = memory(),
        claims: List<DreamClaimHead> = emptyList(),
        sourceTimestamp: Long = NOW - 30_000,
    ) = DreamInputBuilder(
        sourceReader = DreamSourceReader { request ->
            request.locators.map { locator ->
                DreamSourceReadResult.Found(
                    locator = locator,
                    text = "Original user source text",
                    sourceTimestampEpochMs = sourceTimestamp,
                    consumedTextDigest = locator.expectedConsumedTextDigest,
                )
            }
        },
        tokenFactory = DeterministicTokenFactory(),
    ).build(
        DreamInputBuildRequest(
            fence = fence(),
            candidates = listOf(candidate(memory)),
            currentClaims = claims,
        ),
    )

    class DeterministicTokenFactory : DreamRunTokenFactory {
        private var memoryCounter = 0
        private var claimCounter = 0

        override fun nextToken(kind: DreamOpaqueTokenKind): DreamOpaqueToken {
            val counter = if (kind == DreamOpaqueTokenKind.MEMORY) memoryCounter++ else claimCounter++
            val character = ('A'.code + counter).toChar()
            return DreamOpaqueToken(kind.prefix + character.toString().repeat(22))
        }

        override fun nextProposalNonce(): DreamProposalNonce = DreamProposalNonce("p_" + "N".repeat(43))
    }
}
