package me.rerere.rikkahub.learning.workflow.runtime

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.verification.WorkflowCandidateVerifier
import me.rerere.rikkahub.learning.verification.WorkflowVerificationStatus
import me.rerere.rikkahub.learning.verification.workflowReplayFixtureSetSha256
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowCompileResult
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowCompiler
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowValidationContext
import me.rerere.rikkahub.learning.workflow.WorkflowCandidateValidationResult
import me.rerere.rikkahub.learning.workflow.WorkflowCandidateValidator
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationReport
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus

/**
 * P4 production submission state machine. The only executable component is the pure fake verifier;
 * production Tool/Android/network/file handles are absent from this package.
 */
class LearnedWorkflowSubmissionOrchestrator(
    private val authority: WorkflowSubmissionAuthorityPort,
    private val candidates: WorkflowCandidateRuntimeStore,
    private val fixtureProvider: HostWorkflowFixtureProvider =
        ProductionHostWorkflowFixtureProvider,
    private val validator: WorkflowCandidateValidator = WorkflowCandidateValidator(),
    private val verifier: WorkflowCandidateVerifier = WorkflowCandidateVerifier(),
    /** Re-read immediately before every durable mutation; callers must provide the live gate. */
    private val rolloutFence: () -> Boolean,
) : LearnedWorkflowSubmissionService {
    override suspend fun submit(
        request: LearnedWorkflowSubmissionRequest,
        nowMs: Long,
    ): LearnedWorkflowSubmissionResult {
        if (!request.explicitUserSubmission) return rejected(
            null,
            LearnedWorkflowSubmissionFailure.EXPLICIT_USER_SUBMISSION_REQUIRED,
        )
        if (nowMs < 0L) return unavailable(LearnedWorkflowSubmissionFailure.UNKNOWN)
        return try {
            submitChecked(request, nowMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable(LearnedWorkflowSubmissionFailure.UNKNOWN)
        }
    }

    private suspend fun submitChecked(
        request: LearnedWorkflowSubmissionRequest,
        nowMs: Long,
    ): LearnedWorkflowSubmissionResult {
        val proposal = request.proposal
        val initialAuthority = authority.loadCurrent(proposal)
            ?: return unavailable(LearnedWorkflowSubmissionFailure.AUTHORITY_UNAVAILABLE)
        if (initialAuthority.exactGrant != proposal.exactGrant) {
            return rejected(null, LearnedWorkflowSubmissionFailure.AUTHORITY_MISMATCH)
        }
        if (!authority.revalidateExact(proposal.exactGrant)) {
            return rejected(null, LearnedWorkflowSubmissionFailure.AUTHORITY_MISMATCH)
        }
        val compiled = when (val result = LearnedWorkflowCompiler.compile(
            proposal,
            initialAuthority.catalog,
        )) {
            is LearnedWorkflowCompileResult.Compiled -> result.candidate
            is LearnedWorkflowCompileResult.Rejected -> return rejected(
                null,
                LearnedWorkflowSubmissionFailure.COMPILE_REJECTED,
                result.reason.name,
            )
        }
        when (val existing = candidates.readExact(compiled.id)) {
            is WorkflowCandidateReadResult.Ready -> {
                if (!existing.candidate.isSameSubmissionArtifact(compiled)) {
                    return rejected(
                        compiled.id,
                        LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
                    )
                }
                terminalResultOrNull(existing.candidate, replayed = true)?.let { return it }
            }
            WorkflowCandidateReadResult.Missing -> Unit
            WorkflowCandidateReadResult.Unavailable -> return unavailable(
                LearnedWorkflowSubmissionFailure.STORAGE_UNAVAILABLE,
            )
        }
        val bundle = fixtureProvider.resolve(
            request.fixtureProfile,
            compiled,
            initialAuthority.catalog,
        ) ?: return rejected(
            compiled.id,
            LearnedWorkflowSubmissionFailure.PROFILE_NOT_APPLICABLE,
        )
        if (bundle.profile != request.fixtureProfile) {
            return rejected(
                compiled.id,
                LearnedWorkflowSubmissionFailure.PROFILE_NOT_APPLICABLE,
            )
        }

        // Validate the complete canonical template before the first durable write. In particular,
        // literal credentials/URLs/paths/injection text and schema-invalid args must never survive
        // a crash as a PROPOSED candidate merely because the post-insert validation had not run.
        val prePersistenceValidation = validator.validate(
            compiled,
            LearnedWorkflowValidationContext(
                requestAssistantId = proposal.consumingAssistantId,
                requestAuthoritySubjectId = compiled.authoritySubjectId,
                exactGrant = initialAuthority.exactGrant,
                authorityResolver = initialAuthority.authorityResolver,
                fakeAdapters = bundle.validatorAdapters,
                catalog = initialAuthority.catalog,
            ),
        )
        if (!prePersistenceValidation.accepted) {
            return rejected(
                compiled.id,
                LearnedWorkflowSubmissionFailure.VALIDATION_REJECTED,
                prePersistenceValidation.code.name,
            )
        }

        if (!rolloutFence()) return unavailable(LearnedWorkflowSubmissionFailure.ROLLOUT_DISABLED)

        val inserted = when (val insert = candidates.insertCompiledExact(compiled)) {
            is WorkflowCandidateInsertResult.Ready -> insert
            WorkflowCandidateInsertResult.Conflict -> return rejected(
                compiled.id,
                LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
            )
            WorkflowCandidateInsertResult.Unavailable -> return unavailable(
                LearnedWorkflowSubmissionFailure.STORAGE_UNAVAILABLE,
            )
        }
        var replayed = !inserted.inserted
        var current = inserted.candidate
        if (!current.isSameSubmissionArtifact(compiled)) {
            return rejected(
                compiled.id,
                LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
            )
        }
        terminalResultOrNull(current, replayed)?.let { return it }

        if (current.state == LearnedWorkflowCandidateState.PROPOSED) {
            if (!rolloutFence()) {
                return unavailable(LearnedWorkflowSubmissionFailure.ROLLOUT_DISABLED)
            }
            val validating = current.copy(
                state = LearnedWorkflowCandidateState.VALIDATING,
                stateVersion = current.stateVersion + 1L,
                updatedAtMs = nowMs.coerceAtLeast(current.updatedAtMs),
            )
            when (val transition = candidates.transitionExact(
                current,
                validating,
                WorkflowCandidateTransition.VALIDATION_STARTED,
            )) {
                is WorkflowCandidateTransitionResult.Applied -> current = transition.candidate
                WorkflowCandidateTransitionResult.Unavailable -> return unavailable(
                    LearnedWorkflowSubmissionFailure.STORAGE_UNAVAILABLE,
                )
                WorkflowCandidateTransitionResult.Conflict -> {
                    current = when (val read = candidates.readExact(compiled.id)) {
                        is WorkflowCandidateReadResult.Ready -> read.candidate
                        WorkflowCandidateReadResult.Missing -> return rejected(
                            compiled.id,
                            LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
                        )
                        WorkflowCandidateReadResult.Unavailable -> return unavailable(
                            LearnedWorkflowSubmissionFailure.STORAGE_UNAVAILABLE,
                        )
                    }
                    replayed = true
                }
            }
        }
        if (!current.isSameSubmissionArtifact(compiled)) {
            return rejected(
                compiled.id,
                LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
            )
        }
        terminalResultOrNull(current, replayed)?.let { return it }
        if (current.state != LearnedWorkflowCandidateState.VALIDATING) {
            return rejected(
                compiled.id,
                LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
            )
        }

        val initialValidation = validator.validate(
            current,
            LearnedWorkflowValidationContext(
                requestAssistantId = proposal.consumingAssistantId,
                requestAuthoritySubjectId = current.authoritySubjectId,
                exactGrant = initialAuthority.exactGrant,
                authorityResolver = initialAuthority.authorityResolver,
                fakeAdapters = bundle.validatorAdapters,
                catalog = initialAuthority.catalog,
            ),
        )
        if (!initialValidation.accepted) {
            return rejectValidation(
                current,
                bundle,
                nowMs,
                initialValidation,
                replayed,
            )
        }

        val report = verifier.verify(current, bundle.fixtures, bundle.fakeTools)
        if (report.subjectArtifactSha256 != current.artifactSha256 ||
            report.verifierVersion != current.verifierVersion
        ) {
            return rejectWithReport(
                current,
                invalidReceipt(bundle, nowMs, "VERIFIER_SUBJECT_MISMATCH"),
                nowMs,
                LearnedWorkflowSubmissionFailure.VERIFICATION_ABSTAINED,
                "VERIFIER_SUBJECT_MISMATCH",
                replayed,
            )
        }

        // Rebuild all mutable authority immediately before the terminal mutation. This catches
        // assistant deletion, scope drift and schema drift that happened while replay ran.
        val finalAuthority = authority.loadCurrent(proposal)
            ?: return unavailable(LearnedWorkflowSubmissionFailure.AUTHORITY_UNAVAILABLE)
        if (finalAuthority.exactGrant != proposal.exactGrant) {
            return rejectWithReport(
                current,
                invalidReceipt(bundle, nowMs, "AUTHORITY_CHANGED"),
                nowMs,
                LearnedWorkflowSubmissionFailure.AUTHORITY_MISMATCH,
                "AUTHORITY_CHANGED",
                replayed,
            )
        }
        val finalValidation = validator.validate(
            current,
            LearnedWorkflowValidationContext(
                requestAssistantId = proposal.consumingAssistantId,
                requestAuthoritySubjectId = current.authoritySubjectId,
                exactGrant = finalAuthority.exactGrant,
                authorityResolver = finalAuthority.authorityResolver,
                fakeAdapters = bundle.validatorAdapters,
                catalog = finalAuthority.catalog,
            ),
        )
        if (!finalValidation.accepted) {
            return rejectValidation(current, bundle, nowMs, finalValidation, replayed)
        }
        // This is intentionally the final external read before CAS.
        if (!authority.revalidateExact(proposal.exactGrant)) {
            return rejectWithReport(
                current,
                invalidReceipt(bundle, nowMs, "AUTHORITY_REVALIDATION_FAILED"),
                nowMs,
                LearnedWorkflowSubmissionFailure.AUTHORITY_MISMATCH,
                "AUTHORITY_REVALIDATION_FAILED",
                replayed,
            )
        }
        if (!rolloutFence()) return unavailable(LearnedWorkflowSubmissionFailure.ROLLOUT_DISABLED)

        val learnedReport = report.toLearnedWorkflowReport(nowMs.coerceAtLeast(current.updatedAtMs))
        val passed = report.status == WorkflowVerificationStatus.PASSED
        return finish(
            expected = current,
            report = learnedReport,
            nextState = if (passed) {
                LearnedWorkflowCandidateState.VERIFIED
            } else {
                LearnedWorkflowCandidateState.REJECTED
            },
            transition = if (passed) {
                WorkflowCandidateTransition.VALIDATION_PASSED
            } else {
                WorkflowCandidateTransition.VALIDATION_FAILED
            },
            nowMs = nowMs,
            failure = when (report.status) {
                WorkflowVerificationStatus.PASSED -> null
                WorkflowVerificationStatus.FAILED ->
                    LearnedWorkflowSubmissionFailure.VERIFICATION_FAILED
                WorkflowVerificationStatus.ABSTAIN ->
                    LearnedWorkflowSubmissionFailure.VERIFICATION_ABSTAINED
            },
            detailCode = report.failureCodes.firstOrNull()?.name,
            replayed = replayed,
        )
    }

    private suspend fun rejectValidation(
        expected: LearnedWorkflowCandidate,
        bundle: HostWorkflowFixtureBundle,
        nowMs: Long,
        validation: WorkflowCandidateValidationResult,
        replayed: Boolean,
    ): LearnedWorkflowSubmissionResult = rejectWithReport(
        expected = expected,
        report = invalidReceipt(bundle, nowMs, "LOCAL_VALIDATION_FAILED"),
        nowMs = nowMs,
        failure = LearnedWorkflowSubmissionFailure.VALIDATION_REJECTED,
        detailCode = validation.code.name,
        replayed = replayed,
    )

    private suspend fun rejectWithReport(
        expected: LearnedWorkflowCandidate,
        report: LearnedWorkflowVerificationReport,
        nowMs: Long,
        failure: LearnedWorkflowSubmissionFailure,
        detailCode: String?,
        replayed: Boolean,
    ): LearnedWorkflowSubmissionResult = finish(
        expected = expected,
        report = report,
        nextState = LearnedWorkflowCandidateState.REJECTED,
        transition = WorkflowCandidateTransition.VALIDATION_FAILED,
        nowMs = nowMs,
        failure = failure,
        detailCode = detailCode,
        replayed = replayed,
    )

    private suspend fun finish(
        expected: LearnedWorkflowCandidate,
        report: LearnedWorkflowVerificationReport,
        nextState: LearnedWorkflowCandidateState,
        transition: WorkflowCandidateTransition,
        nowMs: Long,
        failure: LearnedWorkflowSubmissionFailure?,
        detailCode: String?,
        replayed: Boolean,
    ): LearnedWorkflowSubmissionResult {
        val completedAt = nowMs.coerceAtLeast(expected.updatedAtMs)
        val next = expected.copy(
            state = nextState,
            stateVersion = expected.stateVersion + 1L,
            verificationReport = report,
            verifiedAtMs = completedAt.takeIf { nextState == LearnedWorkflowCandidateState.VERIFIED },
            updatedAtMs = completedAt,
        )
        return when (val mutation = candidates.transitionExact(expected, next, transition)) {
            is WorkflowCandidateTransitionResult.Applied -> mutation.candidate.toTerminalResult(
                failure,
                detailCode,
                replayed,
            )
            WorkflowCandidateTransitionResult.Unavailable -> unavailable(
                LearnedWorkflowSubmissionFailure.STORAGE_UNAVAILABLE,
            )
            WorkflowCandidateTransitionResult.Conflict -> when (val read = candidates.readExact(expected.id)) {
                is WorkflowCandidateReadResult.Ready -> {
                    if (!read.candidate.isSameSubmissionArtifact(expected)) {
                        rejected(
                            expected.id,
                            LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
                        )
                    } else {
                        terminalResultOrNull(read.candidate, true) ?: rejected(
                            expected.id,
                            LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
                        )
                    }
                }
                WorkflowCandidateReadResult.Missing -> rejected(
                    expected.id,
                    LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
                )
                WorkflowCandidateReadResult.Unavailable -> unavailable(
                    LearnedWorkflowSubmissionFailure.STORAGE_UNAVAILABLE,
                )
            }
        }
    }

    private fun invalidReceipt(
        bundle: HostWorkflowFixtureBundle,
        nowMs: Long,
        code: String,
    ): LearnedWorkflowVerificationReport = LearnedWorkflowVerificationReport(
        verifierVersion = me.rerere.rikkahub.learning.verification
            .WORKFLOW_CANDIDATE_VERIFIER_VERSION,
        fixtureSetSha256 = workflowReplayFixtureSetSha256(bundle.fixtures),
        status = LearnedWorkflowVerificationStatus.ABSTAIN,
        passedChecks = 0,
        failedChecks = 1,
        failureCodes = listOf(code),
        completedAtMs = nowMs.coerceAtLeast(0L),
    )
}

private fun LearnedWorkflowCandidate.isSameSubmissionArtifact(
    other: LearnedWorkflowCandidate,
): Boolean = copy(
    stateVersion = other.stateVersion,
    state = other.state,
    verificationReport = other.verificationReport,
    verifiedAtMs = other.verifiedAtMs,
    archivedAtMs = other.archivedAtMs,
    updatedAtMs = other.updatedAtMs,
) == other

private fun terminalResultOrNull(
    candidate: LearnedWorkflowCandidate,
    replayed: Boolean,
): LearnedWorkflowSubmissionResult? = when (candidate.state) {
    LearnedWorkflowCandidateState.VERIFIED,
    LearnedWorkflowCandidateState.PROMOTING,
    LearnedWorkflowCandidateState.PROMOTED_DISABLED,
    -> if (candidate.verificationReport?.status == LearnedWorkflowVerificationStatus.PASSED &&
        candidate.verifiedAtMs != null
    ) {
        LearnedWorkflowSubmissionResult.Verified(
            candidate.id,
            candidate.candidateVersion,
            candidate.stateVersion,
            replayed,
        )
    } else {
        rejected(
            candidate.id,
            LearnedWorkflowSubmissionFailure.CANDIDATE_CONFLICT,
            replayed = replayed,
        )
    }
    LearnedWorkflowCandidateState.REJECTED -> {
        val status = candidate.verificationReport?.status
        val firstCode = candidate.verificationReport?.failureCodes?.firstOrNull()
        rejected(
            candidate.id,
            when {
                firstCode == "LOCAL_VALIDATION_FAILED" ->
                    LearnedWorkflowSubmissionFailure.VALIDATION_REJECTED
                firstCode == "AUTHORITY_CHANGED" ||
                    firstCode == "AUTHORITY_REVALIDATION_FAILED" ->
                    LearnedWorkflowSubmissionFailure.AUTHORITY_MISMATCH
                status == LearnedWorkflowVerificationStatus.FAILED ->
                    LearnedWorkflowSubmissionFailure.VERIFICATION_FAILED
                status == LearnedWorkflowVerificationStatus.ABSTAIN ->
                    LearnedWorkflowSubmissionFailure.VERIFICATION_ABSTAINED
                else -> LearnedWorkflowSubmissionFailure.VALIDATION_REJECTED
            },
            firstCode,
            replayed,
        )
    }
    else -> null
}

private fun LearnedWorkflowCandidate.toTerminalResult(
    failure: LearnedWorkflowSubmissionFailure?,
    detailCode: String?,
    replayed: Boolean,
): LearnedWorkflowSubmissionResult = if (state == LearnedWorkflowCandidateState.VERIFIED &&
    verificationReport?.status == LearnedWorkflowVerificationStatus.PASSED && verifiedAtMs != null
) {
    LearnedWorkflowSubmissionResult.Verified(id, candidateVersion, stateVersion, replayed)
} else {
    rejected(
        id,
        failure ?: LearnedWorkflowSubmissionFailure.UNKNOWN,
        detailCode,
        replayed,
    )
}

private fun rejected(
    candidateId: String?,
    failure: LearnedWorkflowSubmissionFailure,
    detailCode: String? = null,
    replayed: Boolean = false,
) = LearnedWorkflowSubmissionResult.Rejected(candidateId, failure, detailCode, replayed)

private fun unavailable(
    failure: LearnedWorkflowSubmissionFailure,
) = LearnedWorkflowSubmissionResult.Unavailable(failure)
