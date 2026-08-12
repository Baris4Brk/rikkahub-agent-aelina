package me.rerere.rikkahub.memory.dreaming.synthesis

import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamOpaqueToken
import me.rerere.rikkahub.memory.dreaming.model.DreamProposalNonce
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.DreamSupportType
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisMode
import me.rerere.rikkahub.memory.dreaming.model.requireDreamValidUnicode

const val DREAM_PROMPT_CONTRACT_VERSION = "dream-proposal-v1"
const val DREAM_VALIDATOR_VERSION = "dream-validator-v1"

data class DreamProposalEnvelope(
    val schemaVersion: Int,
    val proposalNonce: DreamProposalNonce,
    val baseMemoryEpoch: Long,
    val baseDreamRevision: Long,
    val mode: DreamSynthesisMode,
    val operations: List<DreamProposalOperation>,
) {
    init {
        require(baseMemoryEpoch >= 0L && baseDreamRevision >= 0L)
        require(operations.size in 1..MAX_DREAM_PROPOSAL_OPERATIONS)
    }
}

sealed interface DreamProposalOperation {
    data class UpsertClaim(
        val targetClaimToken: DreamOpaqueToken?,
        val expectedClaimRevision: Long?,
        val claim: DreamProposedClaim,
    ) : DreamProposalOperation {
        init {
            require((targetClaimToken == null) == (expectedClaimRevision == null))
            require(expectedClaimRevision == null || expectedClaimRevision > 0L)
        }
    }

    data class SupersedeClaim(
        val targetClaimToken: DreamOpaqueToken,
        val expectedClaimRevision: Long,
        val replacement: DreamProposedClaim,
    ) : DreamProposalOperation {
        init {
            require(expectedClaimRevision > 0L)
        }
    }

    data class InvalidateClaim(
        val targetClaimToken: DreamOpaqueToken,
        val expectedClaimRevision: Long,
        val reason: DreamProposalInvalidationReason,
        val evidence: List<DreamProposedEvidence>,
    ) : DreamProposalOperation {
        init {
            require(expectedClaimRevision > 0L)
            require(evidence.size in 1..MAX_DREAM_EVIDENCE_PER_OPERATION)
        }
    }

    data object NoOp : DreamProposalOperation
}

data class DreamProposedClaim(
    val claimKeyHint: String,
    val storageClass: DreamStorageClass,
    val epistemicType: DreamEpistemicType,
    val title: String,
    val statement: String,
    val temporalExpression: String?,
    val evidence: List<DreamProposedEvidence>,
) {
    init {
        require(claimKeyHint.isNotBlank() && claimKeyHint.length <= 512)
        require(title.isNotBlank() && title.length <= 4_096)
        require(statement.isNotBlank() && statement.length <= 32_000)
        require(temporalExpression == null || temporalExpression.length <= 128)
        require(evidence.size in 1..MAX_DREAM_EVIDENCE_PER_OPERATION)
        requireDreamValidUnicode(claimKeyHint, title, statement, temporalExpression)
        require(!claimKeyHint.any(Char::isISOControl))
        require(listOf(title, statement, temporalExpression.orEmpty()).none { text ->
            text.any { it.isISOControl() && it != '\n' && it != '\t' }
        }) { "Claim text contains a prohibited control character" }
    }
}

data class DreamProposedEvidence(
    val memoryToken: DreamOpaqueToken,
    val expectedRevision: Long,
    val supportType: DreamSupportType,
) {
    init {
        require(expectedRevision > 0L)
    }
}

enum class DreamProposalInvalidationReason {
    CONTRADICTED_BY_AUTHORITY,
    SUPERSEDED_BY_AUTHORITY,
    NO_LONGER_SUPPORTED,
}

const val MAX_DREAM_PROPOSAL_OPERATIONS = 256
const val MAX_DREAM_EVIDENCE_PER_OPERATION = 64
const val MAX_DREAM_PROPOSAL_UTF8_BYTES = 512_000
