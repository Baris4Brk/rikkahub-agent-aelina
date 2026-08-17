package me.rerere.rikkahub.learning.curator

import me.rerere.rikkahub.learning.model.LearningScope

enum class CuratorDeltaOperation {
    UPDATE_CANDIDATE,
    MERGE_CANDIDATE,
    SPLIT_CANDIDATE,
    SUPERSEDE_CANDIDATE,
}

/** Canonical content fields that Curator v1 may propose changing. Identity/state are never fields. */
enum class CuratorPolicyField {
    TRIGGER,
    PROCEDURE,
    VERIFICATION,
    BOUNDARY,
    FAILURE_MODE,
    TOOL_SCHEMAS,
}

data class CuratorPolicyDocument(
    val trigger: String,
    val procedure: String,
    val verification: String,
    val boundary: String,
    val failureMode: String,
    val applicableToolSchemaSha256: List<String>,
) {
    init {
        listOf(trigger, procedure, verification, boundary, failureMode).forEach {
            require(it.length in 1..MAX_CURATOR_FIELD_CHARS)
            require(it.isSafeCuratorText())
        }
        require(applicableToolSchemaSha256.size <= MAX_CURATOR_TOOL_SCHEMAS)
        require(applicableToolSchemaSha256 == applicableToolSchemaSha256.distinct().sorted())
        require(applicableToolSchemaSha256.all(String::isCuratorSha256))
    }

    fun value(field: CuratorPolicyField): String = when (field) {
        CuratorPolicyField.TRIGGER -> trigger
        CuratorPolicyField.PROCEDURE -> procedure
        CuratorPolicyField.VERIFICATION -> verification
        CuratorPolicyField.BOUNDARY -> boundary
        CuratorPolicyField.FAILURE_MODE -> failureMode
        CuratorPolicyField.TOOL_SCHEMAS -> applicableToolSchemaSha256.joinToString(",")
    }

    fun replace(field: CuratorPolicyField, value: String): CuratorPolicyDocument = when (field) {
        CuratorPolicyField.TRIGGER -> copy(trigger = value)
        CuratorPolicyField.PROCEDURE -> copy(procedure = value)
        CuratorPolicyField.VERIFICATION -> copy(verification = value)
        CuratorPolicyField.BOUNDARY -> copy(boundary = value)
        CuratorPolicyField.FAILURE_MODE -> copy(failureMode = value)
        CuratorPolicyField.TOOL_SCHEMAS -> copy(
            applicableToolSchemaSha256 = value.split(',').filter(String::isNotBlank),
        )
    }

    val contentSha256: String get() = CuratorV1Canonicalizer.documentSha256(this)

    override fun toString(): String =
        "CuratorPolicyDocument(fields=5, schemas=${applicableToolSchemaSha256.size}, " +
            "content=<redacted>)"
}

enum class CuratorPolicyState {
    CANDIDATE,
    REVIEWED,
    ARCHIVED,
    SUPERSEDED,
}

data class CuratorPolicyHead(
    val policyId: String,
    val scope: LearningScope,
    val revision: Long,
    val state: CuratorPolicyState,
    val document: CuratorPolicyDocument,
    /**
     * Canonical Policy artifact fence. The default keeps pure fixtures compact; production
     * adapters must pass the canonical PolicyArtifactIdentity digest read from LearningDB.
     */
    val artifactSha256: String = document.contentSha256,
    /** Exact canonical storage status, when this head came from the production Policy store. */
    val storageStateCode: String? = null,
) {
    init {
        require(policyId.isSafeCuratorId())
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(revision > 0L)
        require(state != CuratorPolicyState.ARCHIVED || revision > 0L)
        require(artifactSha256.isCuratorSha256())
        storageStateCode?.let {
            require(it.length in 1..64 && it.all { character ->
                character in 'A'..'Z' || character in '0'..'9' || character == '_'
            })
        }
    }

    override fun toString(): String =
        "CuratorPolicyHead(scope=${scope.kind}, revision=$revision, state=$state, " +
            "content=<redacted>, ids=<redacted>)"
}

data class CuratorSourceFence(
    val policyId: String,
    val scope: LearningScope,
    val expectedRevision: Long,
    val baseHash: String,
) {
    init {
        require(policyId.isSafeCuratorId())
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(expectedRevision > 0L)
        require(baseHash.isCuratorSha256())
    }
}

data class CuratorEvidenceRef(
    val evidenceId: String,
    val scope: LearningScope,
    val sourceRevision: Long,
    val integritySha256: String,
) {
    init {
        require(evidenceId.isSafeCuratorId())
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(sourceRevision > 0L)
        require(integritySha256.isCuratorSha256())
    }
}

data class CuratorFieldDiff(
    val field: CuratorPolicyField,
    val beforeSha256: String,
    val afterValue: String,
) {
    init {
        require(beforeSha256.isCuratorSha256())
        if (field == CuratorPolicyField.TOOL_SCHEMAS) {
            require(afterValue.length <= MAX_CURATOR_FIELD_CHARS)
            require(afterValue.isEmpty() || afterValue.isSafeCuratorText())
        } else {
            require(afterValue.length in 1..MAX_CURATOR_FIELD_CHARS && afterValue.isSafeCuratorText())
        }
    }

    val afterSha256: String
        get() = CuratorV1Canonicalizer.fieldSha256(this@CuratorFieldDiff.field, afterValue)

    override fun toString(): String =
        "CuratorFieldDiff(field=$field, before=$beforeSha256, after=$afterSha256, value=<redacted>)"
}

data class CuratorTargetDiff(
    val targetPolicyId: String,
    val fields: List<CuratorFieldDiff>,
) {
    init {
        require(targetPolicyId.isSafeCuratorId())
        require(fields.isNotEmpty() && fields.size <= CuratorPolicyField.entries.size)
        require(fields == fields.sortedBy { it.field.ordinal })
        require(fields.map(CuratorFieldDiff::field).distinct().size == fields.size)
    }
}

sealed interface CuratorDeltaCandidate {
    val candidateId: String
    val operation: CuratorDeltaOperation
    val sources: List<CuratorSourceFence>
    val evidence: List<CuratorEvidenceRef>
    val diffs: List<CuratorTargetDiff>

    data class Update(
        override val candidateId: String,
        val source: CuratorSourceFence,
        override val evidence: List<CuratorEvidenceRef>,
        override val diffs: List<CuratorTargetDiff>,
    ) : CuratorDeltaCandidate {
        override val operation = CuratorDeltaOperation.UPDATE_CANDIDATE
        override val sources: List<CuratorSourceFence> = listOf(source)

        override fun toString(): String = this.redactedCandidateString()
    }

    data class Merge(
        override val candidateId: String,
        override val sources: List<CuratorSourceFence>,
        val outputPolicyId: String,
        val outputDocument: CuratorPolicyDocument,
        override val evidence: List<CuratorEvidenceRef>,
        override val diffs: List<CuratorTargetDiff>,
    ) : CuratorDeltaCandidate {
        override val operation = CuratorDeltaOperation.MERGE_CANDIDATE

        override fun toString(): String = this.redactedCandidateString()
    }

    data class SplitOutput(
        val policyId: String,
        val document: CuratorPolicyDocument,
    ) {
        init {
            require(policyId.isSafeCuratorId())
        }

        override fun toString(): String = "SplitOutput(content=<redacted>, ids=<redacted>)"
    }

    data class Split(
        override val candidateId: String,
        val source: CuratorSourceFence,
        val outputs: List<SplitOutput>,
        override val evidence: List<CuratorEvidenceRef>,
        override val diffs: List<CuratorTargetDiff>,
    ) : CuratorDeltaCandidate {
        override val operation = CuratorDeltaOperation.SPLIT_CANDIDATE
        override val sources: List<CuratorSourceFence> = listOf(source)

        override fun toString(): String = this.redactedCandidateString()
    }

    data class Supersede(
        override val candidateId: String,
        val source: CuratorSourceFence,
        val replacementPolicyId: String,
        val replacementDocument: CuratorPolicyDocument,
        override val evidence: List<CuratorEvidenceRef>,
        override val diffs: List<CuratorTargetDiff>,
    ) : CuratorDeltaCandidate {
        override val operation = CuratorDeltaOperation.SUPERSEDE_CANDIDATE
        override val sources: List<CuratorSourceFence> = listOf(source)

        override fun toString(): String = this.redactedCandidateString()
    }
}

private fun CuratorDeltaCandidate.redactedCandidateString(): String =
    "CuratorDeltaCandidate(operation=$operation, sources=${sources.size}, " +
        "evidence=${evidence.size}, targets=${diffs.size}, content=<redacted>, ids=<redacted>)"

enum class CuratorLineageRelation {
    MERGED_FROM,
    SPLIT_FROM,
    SUPERSEDES,
}

data class CuratorLineageEdge(
    val parentPolicyId: String,
    val childPolicyId: String,
    val relation: CuratorLineageRelation,
) {
    init {
        require(parentPolicyId.isSafeCuratorId() && childPolicyId.isSafeCuratorId())
        require(parentPolicyId != childPolicyId)
    }
}

enum class CuratorMutationKind {
    UPDATE,
    INSERT,
    ARCHIVE,
    RESTORE,
}

/** One deterministic storage-owner instruction. There is intentionally no DELETE kind. */
data class CuratorPlannedMutation(
    val kind: CuratorMutationKind,
    val before: CuratorPolicyHead?,
    val after: CuratorPolicyHead?,
) {
    init {
        require(before != null || after != null)
        when (kind) {
            CuratorMutationKind.UPDATE,
            CuratorMutationKind.ARCHIVE,
            CuratorMutationKind.RESTORE,
            -> require(before != null && after != null && before.policyId == after.policyId)
            CuratorMutationKind.INSERT -> require(before == null && after != null)
        }
    }
}

data class CuratorRollbackPlan(
    val applyPlanId: String,
    val expectedAppliedHeads: List<CuratorSourceFence>,
    val mutations: List<CuratorPlannedMutation>,
    val lineageToRemove: List<CuratorLineageEdge>,
) {
    init {
        require(applyPlanId.isSafeCuratorPlanId())
        require(expectedAppliedHeads.isNotEmpty())
        require(mutations.isNotEmpty())
    }
}

data class CuratorApplyPlan(
    val planId: String,
    val candidateId: String,
    val operation: CuratorDeltaOperation,
    val sourceFences: List<CuratorSourceFence>,
    val evidence: List<CuratorEvidenceRef>,
    val diffs: List<CuratorTargetDiff>,
    val mutations: List<CuratorPlannedMutation>,
    val lineage: List<CuratorLineageEdge>,
    val rollback: CuratorRollbackPlan,
) {
    init {
        require(planId.isSafeCuratorPlanId())
        require(candidateId.isSafeCuratorId())
        require(sourceFences.isNotEmpty() && evidence.isNotEmpty() && mutations.isNotEmpty())
        require(diffs.isNotEmpty())
    }

    override fun toString(): String =
        "CuratorApplyPlan(operation=$operation, sources=${sourceFences.size}, " +
            "evidence=${evidence.size}, mutations=${mutations.size}, content=<redacted>, ids=<redacted>)"
}

enum class CuratorConflictReason {
    INVALID_CANDIDATE,
    DUPLICATE_SOURCE,
    SOURCE_MISSING,
    SOURCE_STATE_CONFLICT,
    SCOPE_CONFLICT,
    REVISION_CONFLICT,
    BASE_HASH_CONFLICT,
    EVIDENCE_MISSING,
    EVIDENCE_CONFLICT,
    DIFF_CONFLICT,
    OUTPUT_ID_CONFLICT,
    REVISION_OVERFLOW,
    ROLLBACK_FENCE_CONFLICT,
    POLICY_IDENTITY_CONFLICT,
}

sealed interface CuratorApplyResult {
    data class Ready(val plan: CuratorApplyPlan) : CuratorApplyResult
    data class RollbackReady(val plan: CuratorRollbackPlan) : CuratorApplyResult
    data class Conflict(val reason: CuratorConflictReason) : CuratorApplyResult
}

fun interface CuratorPolicyHeadReader {
    fun find(policyId: String): CuratorPolicyHead?
}

fun interface CuratorEvidenceReader {
    fun find(evidenceId: String): CuratorEvidenceRef?
}

fun interface CuratorRollbackHeadReader {
    fun find(policyId: String): CuratorPolicyHead?
}

internal fun String.isCuratorSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

internal fun String.isSafeCuratorId(): Boolean =
    length in 1..256 && all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '_' || character == '-' || character == '.' || character == ':' ||
            character == '@'
    }

internal fun String.isSafeCuratorPlanId(): Boolean =
    startsWith("curator-plan-v1:") && removePrefix("curator-plan-v1:").isCuratorSha256()

/**
 * Curator fields are review summaries, never transport containers. This is deliberately stricter
 * than the generic sanitized-summary type: credentials, addresses, filesystem locations, raw
 * structured payloads, prompt-control text and tool call I/O all fail closed before persistence.
 */
internal fun String.isSafeCuratorText(): Boolean {
    if (isBlank() || anyUnsafeCuratorControl()) return false
    val normalized = trim()
    return CURATOR_FORBIDDEN_TEXT_PATTERNS.none { it.containsMatchIn(normalized) }
}

private fun String.anyUnsafeCuratorControl(): Boolean = any {
    it == '\u0000' || it == '\u007f' || (it.code < 0x20 && it != '\n')
}

private val CURATOR_FORBIDDEN_TEXT_PATTERNS = listOf(
    // Credential assignments and common bearer/private-key material.
    Regex(
        "(?i)\\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|auth(?:orization)?|" +
            "proxy[_ -]?authorization|password|passwd|secret|credential|cookie|" +
            "set[_ -]?cookie)\\b\\s*[:=]\\s*[^\\s,;]+",
    ),
    Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{8,}"),
    Regex("-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----"),
    Regex("(?i)\\b(?:sk|pk|ghp|github_pat|xox[baprs])[-_][A-Za-z0-9_-]{8,}"),
    Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
    Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),

    // Network locations and filesystem paths are not review summaries.
    Regex("(?i)(?:https?|ftp|file)://|\\bwww\\."),
    Regex("(?i)(?:^|[\\s(\"'=])(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\s]+"),
    Regex("(?:^|[\\s(\"'=])/(?:[^/\\s]+/)+[^/\\s]*"),

    // Raw JSON/XML/code envelopes and serialized tool arguments/results.
    Regex("(?s)^\\s*[\\[{].*[\\]}]\\s*$"),
    Regex("(?s)\"[^\"\\r\\n]{1,128}\"\\s*:\\s*(?:\"|[\\[{0-9tfn-])"),
    Regex("(?is)<\\s*/?\\s*[a-z][a-z0-9:_-]*(?:\\s+[^>]*)?/?>|<\\?xml"),
    Regex("(?s)```.*```"),
    Regex(
        "(?i)\\b(?:tool[_ -]?(?:args?|arguments?|output|result)|" +
            "tool[_ -]?(?:call|call[_ -]?id)|function[_ -]?(?:call|arguments?)|" +
            "raw[_ -]?(?:input|output|prompt|response)|chain[_ -]?of[_ -]?thought|" +
            "private[_ -]?reasoning)\\b\\s*[:=]",
    ),

    // Prompt-injection/control language, including common delimiter attacks.
    Regex(
        "(?i)\\bignore\\s+(?:all\\s+|the\\s+)?(?:previous|prior|system|developer|user)\\s+" +
            "(?:instructions?|messages?|prompts?)",
    ),
    Regex(
        "(?i)\\bdisregard\\s+(?:all\\s+|the\\s+)?" +
            "(?:previous|prior|system|developer|user)\\s+" +
            "(?:instructions?|messages?|prompts?)",
    ),
    Regex("(?i)\\b(?:system|developer)\\s*(?:prompt|message)\\s*[:=]"),
    Regex(
        "(?i)\\b(?:reveal|print|repeat|expose|leak)\\b.{0,48}\\b" +
            "(?:system|developer|hidden|initial)\\s+(?:prompt|instructions?|message)",
    ),
    Regex(
        "(?i)\\b(?:bypass|disable|override|circumvent)\\b.{0,40}\\b" +
            "(?:approval|permission|safety|policy|guard|gate)",
    ),
    Regex("(?i)\\b(?:system|developer|assistant)\\s*:\\s*"),
)

const val MAX_CURATOR_FIELD_CHARS = 2_048
const val MAX_CURATOR_TOOL_SCHEMAS = 16
const val MAX_CURATOR_EVIDENCE = 32
const val MAX_CURATOR_SOURCES = 8
const val MAX_CURATOR_SPLIT_OUTPUTS = 4
