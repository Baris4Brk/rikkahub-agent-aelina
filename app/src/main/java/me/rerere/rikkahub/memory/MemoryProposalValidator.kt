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
        val structurallyAcceptedRelations = envelope.relations.mapIndexedNotNull { index, relation ->
            relation.takeIf {
                index < MAX_MEMORY_RELATIONS && validRelation(it, acceptedKeys, context)
            }
        }
        val acceptedRelations = rejectDerivedCycles(structurallyAcceptedRelations)
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
private const val MAX_MEMORY_RELATIONS = 12

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
            proposal.targetIds.zip(proposal.expectedRevisions).all { (id, revision) ->
                visible[id] == revision
            }

    MemoryCandidateAction.MERGE ->
        proposal.targetIds.size >= 2 &&
            proposal.targetIds.size == proposal.expectedRevisions.size &&
            proposal.targetIds.zip(proposal.expectedRevisions).all { (id, revision) ->
                visible[id] == revision
            }
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
    val selfRelation =
        relation.sourceProposalKey != null && relation.sourceProposalKey == relation.targetProposalKey ||
            relation.sourceMemoryId != null && relation.sourceMemoryId == relation.targetMemoryId
    return sourceCount == 1 && targetCount == 1 && relation.weight in 0f..1f &&
        !selfRelation && relation.description.length <= 500 &&
        relation.description.none(Char::isISOControl) &&
        relation.evidenceMessageIds.isNotEmpty() &&
        context.allowedEvidenceMessageIds.containsAll(relation.evidenceMessageIds) &&
        relation.sourceProposalKey?.let(acceptedProposalKeys::contains) != false &&
        relation.targetProposalKey?.let(acceptedProposalKeys::contains) != false &&
        relation.sourceMemoryId?.let(context.visibleExistingMemories::containsKey) != false &&
        relation.targetMemoryId?.let(context.visibleExistingMemories::containsKey) != false
}

/**
 * Relation candidates are review-only, but rejecting an obvious DERIVED_FROM cycle before it is
 * persisted keeps later review deterministic. Existing-link cycles are rechecked at activation.
 */
private fun rejectDerivedCycles(
    relations: List<MemoryRelationProposal>,
): List<MemoryRelationProposal> {
    val accepted = arrayListOf<MemoryRelationProposal>()
    val graph = hashMapOf<String, MutableSet<String>>()
    relations.forEach { relation ->
        if (relation.type != MemoryRelationType.DERIVED_FROM) {
            accepted += relation
            return@forEach
        }
        val source = relation.sourceEndpointToken()
        val target = relation.targetEndpointToken()
        if (source == null || target == null || graph.reachable(target, source)) return@forEach
        graph.getOrPut(source) { linkedSetOf() }.add(target)
        accepted += relation
    }
    return accepted
}

private fun MemoryRelationProposal.sourceEndpointToken(): String? =
    sourceProposalKey?.let { "p:$it" } ?: sourceMemoryId?.let { "m:$it" }

private fun MemoryRelationProposal.targetEndpointToken(): String? =
    targetProposalKey?.let { "p:$it" } ?: targetMemoryId?.let { "m:$it" }

private fun Map<String, MutableSet<String>>.reachable(start: String, target: String): Boolean {
    val pending = ArrayDeque<String>()
    val visited = hashSetOf<String>()
    pending.add(start)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current == target) return true
        get(current).orEmpty().forEach(pending::addLast)
    }
    return false
}
