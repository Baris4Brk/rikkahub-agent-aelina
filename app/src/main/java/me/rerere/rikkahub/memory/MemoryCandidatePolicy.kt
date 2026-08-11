package me.rerere.rikkahub.memory

enum class MemoryCandidateDisposition {
    AUTO_APPLY,
    REVIEW,
    SUPERSEDE,
    IGNORE,
}

enum class MemoryDuplicateAssessment {
    NONE,
    EXACT,
    NEAR,
}

enum class MemoryRiskFlag {
    SECRET,
    SENSITIVE_CATEGORY,
    /** A non-sensitive but unsafe-for-bulk-accept review condition. */
    NEAR_DUPLICATE,
}

class MemoryCandidatePolicy(
    private val contentGuard: MemoryContentGuard = MemoryContentGuard(),
) {
    /**
     * Shared safety predicate for automatic write-back and the review screen's batch action.
     * A caller may use this only to select candidates; actual writes still go through the
     * revision-aware mutation/review path.
     */
    fun isSafeNewCreate(proposal: MemoryProposal): Boolean =
        proposal.action == MemoryCandidateAction.CREATE &&
            proposal.truthStatus == MemoryTruthStatus.CONFIRMED &&
            proposal.confidence >= SAFE_CREATE_CONFIDENCE &&
            (proposal.kind in AUTO_APPLY_KINDS || proposal.isSafeNarrativeCreate()) &&
            contentGuard.inspect(proposal.title + "\n" + proposal.content).isEmpty()

    /**
     * Persists every reason that prevents a proposal from being safely batch-accepted later.
     * The candidate table deliberately has one structured review-flags field, so near-duplicate
     * detection survives the asynchronous trip from extraction to the user review screen.
     */
    fun reviewFlagsFor(
        proposal: MemoryProposal,
        duplicate: MemoryDuplicateAssessment,
    ): Set<MemoryRiskFlag> = buildSet {
        addAll(contentGuard.inspect(proposal.title + "\n" + proposal.content))
        if (duplicate == MemoryDuplicateAssessment.NEAR) add(MemoryRiskFlag.NEAR_DUPLICATE)
    }

    fun decide(
        proposal: MemoryProposal,
        mode: MemoryAutoSaveMode,
        duplicate: MemoryDuplicateAssessment = MemoryDuplicateAssessment.NONE,
    ): MemoryCandidateDisposition {
        if (proposal.action == MemoryCandidateAction.IGNORE) return MemoryCandidateDisposition.IGNORE
        if (duplicate == MemoryDuplicateAssessment.EXACT) return MemoryCandidateDisposition.SUPERSEDE
        if (duplicate == MemoryDuplicateAssessment.NEAR) return MemoryCandidateDisposition.REVIEW
        if (contentGuard.inspect(proposal.title + "\n" + proposal.content).isNotEmpty()) {
            return MemoryCandidateDisposition.REVIEW
        }
        if (mode != MemoryAutoSaveMode.SAFE_NEW_ONLY) return MemoryCandidateDisposition.REVIEW
        return if (isSafeNewCreate(proposal)) {
            MemoryCandidateDisposition.AUTO_APPLY
        } else {
            MemoryCandidateDisposition.REVIEW
        }
    }
}

private fun MemoryProposal.isSafeNarrativeCreate(): Boolean =
    kind in setOf(MemoryKind.EPISODE, MemoryKind.DECISION) &&
        attribution in setOf(MemoryAttribution.USER, MemoryAttribution.SHARED) &&
        truthStatus == MemoryTruthStatus.CONFIRMED &&
        importance >= 0.60f

data class MemoryRedactionResult(
    val text: String,
    val risks: Set<MemoryRiskFlag>,
)

class MemoryContentGuard {
    fun inspect(text: String): Set<MemoryRiskFlag> = buildSet {
        if (SECRET_PATTERNS.any { it.containsMatchIn(text) }) add(MemoryRiskFlag.SECRET)
        if (SENSITIVE_TERMS.any { text.contains(it, ignoreCase = true) }) {
            add(MemoryRiskFlag.SENSITIVE_CATEGORY)
        }
    }

    fun redact(text: String): MemoryRedactionResult {
        var redacted = text
        SECRET_PATTERNS.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        OMITTED_INPUT_PATTERNS.forEach { pattern -> redacted = pattern.replace(redacted, "<omitted>") }
        return MemoryRedactionResult(redacted, inspect(text))
    }
}

private const val SAFE_CREATE_CONFIDENCE = 0.90f

private val AUTO_APPLY_KINDS = setOf(
    MemoryKind.USER_PROFILE,
    MemoryKind.PREFERENCE,
    MemoryKind.LONG_TERM_GOAL,
    MemoryKind.PROJECT_FACT,
    MemoryKind.WORKING_CONSTRAINT,
    MemoryKind.RELATIONSHIP,
)

private val SECRET_PATTERNS = listOf(
    Regex("(?i)\\b(?:api[_ -]?key|access[_ -]?token|secret|password|passwd)\\b\\s*[:=]\\s*[^\\s,;]{6,}"),
    Regex("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{12,}"),
    Regex("\\beyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}(?:\\.[a-zA-Z0-9_-]{8,})?\\b"),
    Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    Regex("(?i)\\b(?:otp|verification code|验证码)\\b\\D{0,8}\\d{4,8}\\b"),
    Regex("(?<!\\d)(?:\\d[ -]*?){13,19}(?!\\d)"),
)

/** Large opaque encodings are not useful memory evidence and can waste the extraction budget. */
private val OMITTED_INPUT_PATTERNS = listOf(
    Regex("(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{160,}={0,2}(?![A-Za-z0-9+/=])"),
    Regex("(?is)<tool_result\\b[^>]*>.*?</tool_result>"),
    Regex("(?is)<(?:thinking|analysis)\\b[^>]*>.*?</(?:thinking|analysis)>"),
)

private val SENSITIVE_TERMS = listOf(
    "宗教",
    "政治观点",
    "性取向",
    "性生活",
    "犯罪记录",
    "medical diagnosis",
    "religion",
    "political view",
    "sexual orientation",
    "criminal record",
)
