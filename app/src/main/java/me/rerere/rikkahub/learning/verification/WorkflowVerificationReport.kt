package me.rerere.rikkahub.learning.verification

import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationReport
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus

enum class WorkflowVerificationStatus {
    PASSED,
    FAILED,
    ABSTAIN,
}

/** Fake verification never conveys authorization, regardless of [status]. */
enum class WorkflowVerificationCapabilityAuthority {
    NONE,
}

enum class WorkflowVerificationFailureCode {
    FIXTURE_SET_EMPTY,
    FIXTURE_DUPLICATE,
    FIXTURE_VERSION_MISMATCH,
    FIXTURE_SUBJECT_MISMATCH,
    TEMPLATE_MALFORMED,
    ACTION_COUNT_OUT_OF_BOUNDS,
    ACTION_ORACLE_MISMATCH,
    ACTION_SCHEMA_MISMATCH,
    SLOT_UNKNOWN,
    SLOT_UNBOUND,
    SLOT_TYPE_MISMATCH,
    FAKE_TOOL_MISSING,
    FAKE_CASE_MISSING,
    RESULT_ORACLE_MISMATCH,
    TERMINAL_ORACLE_MISMATCH,
    OUTPUT_UTF8_LIMIT_EXCEEDED,
}

data class WorkflowVerificationActionObservation(
    val actionIndex: Int,
    val toolName: String,
    val schemaFingerprint: String,
    val argsSha256: String,
    val resultKind: WorkflowReplayResultKind,
    val outputSha256: String?,
    val outputUtf8Bytes: Int,
    val errorCode: String?,
) {
    init {
        require(actionIndex >= 0)
        require(argsSha256.isVerifierSha256())
        require(outputSha256 == null || outputSha256.isVerifierSha256())
        require(outputUtf8Bytes >= 0)
    }
}

data class WorkflowVerificationFixtureReport(
    val fixtureId: String,
    val status: WorkflowVerificationStatus,
    val terminal: WorkflowReplayTerminal?,
    val observations: List<WorkflowVerificationActionObservation>,
    val failureCode: WorkflowVerificationFailureCode?,
    val failureActionIndex: Int?,
)

data class WorkflowVerificationReport(
    val verifierVersion: String,
    val fixtureSetSha256: String,
    val subjectArtifactSha256: String,
    val status: WorkflowVerificationStatus,
    val fixtures: List<WorkflowVerificationFixtureReport>,
    val failureCodes: List<WorkflowVerificationFailureCode>,
    val capabilityAuthority: WorkflowVerificationCapabilityAuthority =
        WorkflowVerificationCapabilityAuthority.NONE,
) {
    init {
        require(verifierVersion.isNotBlank())
        require(fixtureSetSha256.isVerifierSha256())
        require(subjectArtifactSha256.isVerifierSha256())
        require(failureCodes.distinct().sortedBy(Enum<*>::name) == failureCodes)
        require(capabilityAuthority == WorkflowVerificationCapabilityAuthority.NONE)
        require(status != WorkflowVerificationStatus.PASSED || failureCodes.isEmpty())
    }

    val passedChecks: Int get() = fixtures.count { it.status == WorkflowVerificationStatus.PASSED }
    val failedChecks: Int get() = fixtures.count { it.status == WorkflowVerificationStatus.FAILED }

    /** Content-free receipt suitable for the candidate artifact. */
    fun toLearnedWorkflowReport(completedAtMs: Long): LearnedWorkflowVerificationReport {
        require(completedAtMs >= 0L)
        return LearnedWorkflowVerificationReport(
            verifierVersion = verifierVersion,
            fixtureSetSha256 = fixtureSetSha256,
            status = when (status) {
                WorkflowVerificationStatus.PASSED -> LearnedWorkflowVerificationStatus.PASSED
                WorkflowVerificationStatus.FAILED -> LearnedWorkflowVerificationStatus.FAILED
                WorkflowVerificationStatus.ABSTAIN -> LearnedWorkflowVerificationStatus.ABSTAIN
            },
            passedChecks = passedChecks,
            failedChecks = failedChecks,
            failureCodes = failureCodes.map(Enum<*>::name),
            completedAtMs = completedAtMs,
        )
    }

    override fun toString(): String =
        "WorkflowVerificationReport(status=$status, fixtures=${fixtures.size}, " +
            "failures=${failureCodes.size}, capabilityAuthority=$capabilityAuthority)"
}

