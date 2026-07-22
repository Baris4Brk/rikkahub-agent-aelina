package me.rerere.rikkahub.memory

data class MemoryProposalValidationContext(
    val allowedEvidenceMessageIds: Set<String>,
    /** Memory id to current revision for records exposed to the extraction model. */
    val visibleExistingMemories: Map<Int, Int>,
    val narrativeEventsEnabled: Boolean = true,
    val insightsTheoriesEnabled: Boolean = true,
    val nowMs: Long = System.currentTimeMillis(),
)

data class MemoryProposalValidationResult(
    val accepted: List<MemoryProposal>,
    val rejected: List<RejectedMemoryProposal>,
    val acceptedRelations: List<MemoryRelationProposal> = emptyList(),
    val rejectedRelations: List<MemoryRelationProposal> = emptyList(),
)

data class RejectedMemoryProposal(
    val proposal: MemoryProposal,
    val code: MemoryProposalRejectionCode,
)

enum class MemoryProposalRejectionCode {
    TOO_MANY_PROPOSALS,
    INVALID_TITLE,
    INVALID_CONTENT,
    INVALID_TAGS,
    INVALID_SCORE,
    INVALID_EVIDENCE,
    INVALID_TARGET,
    DUPLICATE_PROPOSAL_KEY,
    FEATURE_DISABLED,
    INVALID_NARRATIVE_METADATA,
}

class MemoryProposalValidator {
    fun validate(
        envelope: MemoryExtractionEnvelope,
        context: MemoryProposalValidationContext,
    ): MemoryProposalValidationResult {
        val accepted = arrayListOf<MemoryProposal>()
        val rejected = arrayListOf<RejectedMemoryProposal>()
        val keys = envelope.proposals.mapNotNull(MemoryProposal::proposalKey)
        envelope.proposals.forEachIndexed { index, proposal ->
            val code = when {
                index >= MAX_MEMORY_PROPOSALS -> MemoryProposalRejectionCode.TOO_MANY_PROPOSALS
                proposal.action != MemoryCandidateAction.IGNORE &&
                    proposal.title.trim().length !in 1..MAX_MEMORY_TITLE_CHARS ->
                    MemoryProposalRejectionCode.INVALID_TITLE
                proposal.action != MemoryCandidateAction.IGNORE &&
                    proposal.content.trim().length !in MIN_MEMORY_CONTENT_CHARS..MAX_MEMORY_CONTENT_CHARS ->
                    MemoryProposalRejectionCode.INVALID_CONTENT
                !validTags(proposal.tags) -> MemoryProposalRejectionCode.INVALID_TAGS
                proposal.importance !in 0f..1f || proposal.confidence !in 0f..1f ->
                    MemoryProposalRejectionCode.INVALID_SCORE
                proposal.evidenceMessageIds.isEmpty() ||
                    !context.allowedEvidenceMessageIds.containsAll(proposal.evidenceMessageIds) ->
                    MemoryProposalRejectionCode.INVALID_EVIDENCE
                !validTargets(proposal, context.visibleExistingMemories) ->
                    MemoryProposalRejectionCode.INVALID_TARGET
                envelope.version >= 2 && (proposal.proposalKey.isNullOrBlank() || keys.count { it == proposal.proposalKey } != 1) ->
                    MemoryProposalRejectionCode.DUPLICATE_PROPOSAL_KEY
                proposal.kind in EVENT_KINDS && !context.narrativeEventsEnabled ->
                    MemoryProposalRejectionCode.FEATURE_DISABLED
                proposal.kind in IDEA_KINDS && !context.insightsTheoriesEnabled ->
                    MemoryProposalRejectionCode.FEATURE_DISABLED
                !validNarrativeMetadata(proposal, context.nowMs) ->
                    MemoryProposalRejectionCode.INVALID_NARRATIVE_METADATA
                else -> null
            }
            if (code == null) accepted += proposal else rejected += RejectedMemoryProposal(proposal, code)
        }
        val acceptedKeys = accepted.mapNotNullTo(hashSetOf(), MemoryProposal::proposalKey)
        val acceptedRelations = envelope.relations.filter { relation ->
            validRelation(relation, acceptedKeys, context)
        }
        return MemoryProposalValidationResult(
            accepted,
            rejected,
            acceptedRelations,
            envelope.relations - acceptedRelations.toSet(),
        )
    }
}

private const val MAX_MEMORY_PROPOSALS = 8
private const val MAX_MEMORY_TITLE_CHARS = 80
private const val MIN_MEMORY_CONTENT_CHARS = 8
private const val MAX_MEMORY_CONTENT_CHARS = 4_000
private const val MAX_MEMORY_TAGS = 8
private const val MAX_MEMORY_TAG_CHARS = 32

private fun validTags(tags: List<String>): Boolean =
    tags.size <= MAX_MEMORY_TAGS && tags.all { tag ->
        val normalized = tag.trim()
        normalized.isNotEmpty() && normalized.length <= MAX_MEMORY_TAG_CHARS &&
            '|' !in normalized && normalized.none(Char::isISOControl)
    }

private fun validTargets(proposal: MemoryProposal, visible: Map<Int, Int>): Boolean = when (proposal.action) {
    MemoryCandidateAction.CREATE,
    MemoryCandidateAction.IGNORE,
    -> proposal.targetIds.isEmpty() && proposal.expectedRevisions.isEmpty()

    MemoryCandidateAction.UPDATE ->
        proposal.targetIds.size == 1 && proposal.expectedRevisions.size == 1 &&
            proposal.targetIds.all(visible::containsKey)

    MemoryCandidateAction.MERGE ->
        proposal.targetIds.size >= 2 &&
            proposal.targetIds.size == proposal.expectedRevisions.size &&
            proposal.targetIds.all(visible::containsKey)
}

private val EVENT_KINDS = setOf(MemoryKind.EPISODE, MemoryKind.DECISION)
private val IDEA_KINDS = setOf(MemoryKind.INSIGHT, MemoryKind.THEORY)

private fun validNarrativeMetadata(proposal: MemoryProposal, nowMs: Long): Boolean {
    if (proposal.participants.size > 2 || proposal.participants.any { it !in CANONICAL_PARTICIPANTS }) return false
    if (proposal.kind !in EVENT_KINDS + IDEA_KINDS) return true
    if ((proposal.outcome?.length ?: 0) > 1_000) return false
    if (proposal.occurredAtMs != null && proposal.occurredAtMs > nowMs + 24L * 60L * 60L * 1_000L) return false
    if (proposal.kind !in IDEA_KINDS && proposal.content.length > 2_000) return false
    return true
}

private val CANONICAL_PARTICIPANTS = setOf("USER", "ASSISTANT")

private fun validRelation(
    relation: MemoryRelationProposal,
    acceptedProposalKeys: Set<String>,
    context: MemoryProposalValidationContext,
): Boolean {
    val sourceCount = listOfNotNull(relation.sourceProposalKey, relation.sourceMemoryId).size
    val targetCount = listOfNotNull(relation.targetProposalKey, relation.targetMemoryId).size
    return sourceCount == 1 && targetCount == 1 && relation.weight in 0f..1f &&
        relation.description.length <= 500 &&
        context.allowedEvidenceMessageIds.containsAll(relation.evidenceMessageIds) &&
        relation.sourceProposalKey?.let(acceptedProposalKeys::contains) != false &&
        relation.targetProposalKey?.let(acceptedProposalKeys::contains) != false &&
        relation.sourceMemoryId?.let(context.visibleExistingMemories::containsKey) != false &&
        relation.targetMemoryId?.let(context.visibleExistingMemories::containsKey) != false
}
