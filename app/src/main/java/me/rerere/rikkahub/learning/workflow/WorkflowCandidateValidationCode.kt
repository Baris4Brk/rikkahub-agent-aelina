package me.rerere.rikkahub.learning.workflow

enum class WorkflowCandidateValidationCode {
    VALID,
    CANDIDATE_STATE_UNSUPPORTED,
    ARTIFACT_HASH_MISMATCH,
    TEMPLATE_MALFORMED,
    TEMPLATE_UNKNOWN_KEY,
    TEMPLATE_NOT_MANUAL,
    TEMPLATE_ENABLED,
    TEMPLATE_IDENTITY_MISMATCH,
    EXECUTION_BOUND_OUT_OF_BOUNDS,
    ASSISTANT_MISSING,
    ASSISTANT_MISMATCH,
    AUTHORITY_SUBJECT_MISMATCH,
    GRANT_NOT_EXACT,
    ACTION_COUNT_OUT_OF_BOUNDS,
    TIMEOUT_OUT_OF_BOUNDS,
    OUTPUT_BOUND_OUT_OF_BOUNDS,
    TOOL_FORBIDDEN,
    TOOL_NOT_CATALOGUED,
    TOOL_EXTERNAL_UNTRUSTED,
    TOOL_ORIGIN_NOT_TRUSTED_WORKFLOW,
    TOOL_RISK_TOO_HIGH,
    FAKE_ADAPTER_MISSING,
    CAPABILITY_SNAPSHOT_MISMATCH,
    TOOL_SCHEMA_MISMATCH,
    INPUT_SCHEMA_MISSING,
    INPUT_SCHEMA_INVALID,
    SLOT_UNKNOWN,
    SLOT_UNBOUND,
    SLOT_TYPE_MISMATCH,
    SLOT_UNUSED,
    SECRET_LITERAL,
    PROMPT_INJECTION,
    URL_NOT_ALLOWED,
    PATH_NOT_ALLOWED,
    ARGUMENT_BOUNDS_EXCEEDED,
}

data class WorkflowCandidateValidationResult(
    val code: WorkflowCandidateValidationCode,
    val actionIndex: Int? = null,
    val detailCode: String? = null,
) {
    init {
        require(actionIndex == null || actionIndex >= 0)
        require(detailCode == null || detailCode.matches(DETAIL_CODE))
    }

    val accepted: Boolean get() = code == WorkflowCandidateValidationCode.VALID

    override fun toString(): String =
        "WorkflowCandidateValidationResult(code=$code, action=$actionIndex, detail=$detailCode)"

    companion object {
        private val DETAIL_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
        val VALID = WorkflowCandidateValidationResult(WorkflowCandidateValidationCode.VALID)
    }
}
