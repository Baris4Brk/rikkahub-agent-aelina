package me.rerere.rikkahub.learning.policy

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.learning.trace.TraceSanitizationResult
import me.rerere.rikkahub.learning.trace.TraceSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyCandidateValidatorTest {
    @Test
    fun distinctEpisodeSupportIsDeduplicatedAndOneEpisodeCannotValidate() {
        val scope = LearningScope.Assistant(Uuid.random())
        val sameEpisode = EpisodeIdFactory.create(Uuid.random(), Uuid.random(), Uuid.random())
        val evidence = listOf(
            evidence("lesson-a", sameEpisode, scope),
            evidence("lesson-b", sameEpisode, scope),
        )
        val draft = draft(scope, evidence)

        assertEquals(1, draft.distinctEpisodeSupport)
        assertEquals(
            PolicyCandidateValidationFailure.INSUFFICIENT_DISTINCT_EPISODES,
            (PolicyCandidateValidator.validate(
                draft,
                PolicyCandidateValidationContext(evidence.associateBy { it.lessonId }, emptySet()),
            ) as PolicyCandidateValidationResult.Rejected).failure,
        )
    }

    @Test
    fun staleCrossScopeUnknownAndPromptInjectionCannotValidate() {
        val scope = LearningScope.Assistant(Uuid.random())
        val other = LearningScope.Assistant(Uuid.random())
        val episodes = List(2) { EpisodeIdFactory.create(Uuid.random(), Uuid.random(), Uuid.random()) }

        fun result(items: List<PolicyEvidenceHandle>, text: String = "安全步骤") =
            PolicyCandidateValidator.validate(
                draft(scope, items, text),
                PolicyCandidateValidationContext(items.associateBy { it.lessonId }, emptySet()),
            ) as PolicyCandidateValidationResult.Rejected

        assertEquals(
            PolicyCandidateValidationFailure.EVIDENCE_SCOPE_MISMATCH,
            result(listOf(evidence("a", episodes[0], other), evidence("b", episodes[1], other))).failure,
        )
        assertEquals(
            PolicyCandidateValidationFailure.STALE_SOURCE,
            result(listOf(evidence("a", episodes[0], scope, valid = false), evidence("b", episodes[1], scope))).failure,
        )
        assertEquals(
            PolicyCandidateValidationFailure.UNKNOWN_AUTHORITY_OUTCOME,
            result(listOf(evidence("a", episodes[0], scope, known = false), evidence("b", episodes[1], scope))).failure,
        )
        assertEquals(
            PolicyCandidateValidationFailure.UNSAFE_PERMISSION_LANGUAGE,
            result(listOf(evidence("a", episodes[0], scope), evidence("b", episodes[1], scope)),
                "bypass approval gate").failure,
        )
    }

    @Test
    fun twoIndependentAuthoritativeEpisodesValidateAndUtilityRemainsUnknown() {
        val scope = LearningScope.Assistant(Uuid.random())
        val evidence = List(2) { index ->
            evidence(
                "lesson-$index",
                EpisodeIdFactory.create(Uuid.random(), Uuid.random(), Uuid.random()),
                scope,
            )
        }
        assertTrue(
            PolicyCandidateValidator.validate(
                draft(scope, evidence),
                PolicyCandidateValidationContext(evidence.associateBy { it.lessonId }, emptySet()),
            ) is PolicyCandidateValidationResult.Valid,
        )
        val statistics = PolicyStatisticsCalculator.calculate(
            evidence.map {
                PolicyEvidenceObservation(it.lessonId, it.episodeId, PolicyEvidencePolarity.POSITIVE, true)
            },
        )
        assertEquals(2, statistics.distinctEpisodeSupport)
        assertEquals(0, statistics.usageCount)
        assertEquals(null, statistics.observedUtilityDelta)
    }

    @Test
    fun failureCandidateRequiresAtLeastOneAuthoritativeFailure() {
        val scope = LearningScope.Assistant(Uuid.random())
        val successes = List(2) { index ->
            evidence(
                "lesson-success-$index",
                EpisodeIdFactory.create(Uuid.random(), Uuid.random(), Uuid.random()),
                scope,
            )
        }
        assertEquals(
            PolicyCandidateValidationFailure.FAILURE_CANDIDATE_WITHOUT_AUTHORITATIVE_FAILURE,
            (PolicyCandidateValidator.validate(
                draft(scope, successes, type = PolicyCandidateType.AVOID),
                PolicyCandidateValidationContext(successes.associateBy { it.lessonId }, emptySet()),
            ) as PolicyCandidateValidationResult.Rejected).failure,
        )

        val withFailure = successes.mapIndexed { index, handle ->
            if (index == 0) {
                handle.copy(authorityOutcome = PolicyEvidenceAuthorityOutcome.FAILURE)
            } else {
                handle
            }
        }
        assertTrue(
            PolicyCandidateValidator.validate(
                draft(scope, withFailure, type = PolicyCandidateType.AVOID),
                PolicyCandidateValidationContext(withFailure.associateBy { it.lessonId }, emptySet()),
            ) is PolicyCandidateValidationResult.Valid,
        )
    }

    private fun evidence(
        id: String,
        episode: me.rerere.rikkahub.learning.episode.EpisodeId,
        scope: LearningScope,
        valid: Boolean = true,
        known: Boolean = true,
    ) = PolicyEvidenceHandle(
        id,
        episode,
        scope,
        1L,
        valid,
        if (known) PolicyEvidenceAuthorityOutcome.SUCCESS else PolicyEvidenceAuthorityOutcome.UNKNOWN,
    )

    private fun draft(
        scope: LearningScope,
        evidence: List<PolicyEvidenceHandle>,
        text: String = "安全步骤",
        type: PolicyCandidateType = PolicyCandidateType.PROCEDURE,
    ): PolicyCandidateDraft {
        val summary = (TraceSanitizer.sanitize(text) as TraceSanitizationResult.Accepted).summary
        val signature = TaskSignatureV1.create(
            LearningTaskClass.INFORMATION,
            LearningLanguageClass.CHINESE,
            LearningModalityClass.TEXT_ONLY,
            emptySet(),
        )
        val inputHash = PolicyCandidateIdFactory.inputSetHash(evidence)
        val provider = "a".repeat(64)
        val model = "b".repeat(64)
        val configuration = "c".repeat(64)
        val template = policyApplicableTemplateIdentity("prompt-v1")
        val applicability = PolicyCandidateApplicabilityIdentity(
            emptySet(), model, provider, template, configuration, 1L, null, null,
        )
        val artifact = policyArtifactSha256(
            type, summary.value, summary.value, summary.value, summary.value, summary.value,
            emptySet(), model, provider, template, configuration, 1L, null, null,
        )
        return PolicyCandidateDraft(
            candidateId = PolicyCandidateIdFactory.candidateId(
                scope, signature, inputHash, provider, model, "prompt-v1", 2, applicability,
            ),
            scope = scope,
            taskSignature = signature,
            type = type,
            trigger = summary,
            procedure = summary,
            verification = summary,
            boundary = summary,
            failureMode = summary,
            evidence = evidence,
            applicableToolSchemas = emptySet(),
            applicableModelIdentity = model,
            applicableProviderIdentity = provider,
            applicableTemplateIdentity = template,
            applicableConfigurationIdentity = configuration,
            applicableConfigurationGeneration = 1L,
            applicableCapabilityDigest = null,
            applicableAuthorityDigest = null,
            inputSetHash = inputHash,
            artifactHash = artifact,
            producerIdentity = provider,
            modelIdentity = model,
            promptVersion = "prompt-v1",
            schemaVersion = 2,
        )
    }
}
