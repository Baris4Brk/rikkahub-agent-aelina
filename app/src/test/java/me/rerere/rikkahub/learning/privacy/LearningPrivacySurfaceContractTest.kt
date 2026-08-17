package me.rerere.rikkahub.learning.privacy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release guard for every durable Learning privacy surface, including tables introduced after P1.
 * The explicit inventory intentionally fails whenever LearningDatabase gains an unaudited entity.
 */
class LearningPrivacySurfaceContractTest {
    @Test
    fun `every current LearningDB entity is in the retention erase audit inventory`() {
        val database = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/storage/LearningDatabase.kt",
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningDatabase.kt",
        ).readText(Charsets.UTF_8)
        val registered = Regex("([A-Za-z][A-Za-z0-9_]*Entity)::class")
            .findAll(database.substringAfter("entities = [").substringBefore("],"))
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(CURRENT_LEARNING_ENTITY_INVENTORY, registered)
        val matrix = projectFile(
            "docs/agent-learning/LearningDB-retention-erase-matrix-v9.md",
            "../docs/agent-learning/LearningDB-retention-erase-matrix-v9.md",
        ).readText(Charsets.UTF_8)
        registered.forEach { entity ->
            assertTrue("Retention/erase matrix omitted $entity", "`$entity`" in matrix)
        }
    }

    @Test
    fun `all Learning entities and main authority receipts own redacted string projections`() {
        val roots = listOf(
            projectDirectory(
                "app/src/main/java/me/rerere/rikkahub/learning/storage",
                "src/main/java/me/rerere/rikkahub/learning/storage",
            ),
            projectDirectory(
                "app/src/main/java/me/rerere/rikkahub/data/db/entity",
                "src/main/java/me/rerere/rikkahub/data/db/entity",
            ),
        )
        val sources = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

        (CURRENT_LEARNING_ENTITY_INVENTORY + MAIN_AUTHORITY_PRIVACY_SURFACES).forEach { name ->
            val source = sources.singleOrNull { file ->
                "data class $name(" in file.readText(Charsets.UTF_8)
            }
            assertNotNull("Missing audited source for $name", source)
            val text = requireNotNull(source).readText(Charsets.UTF_8)
            val section = text.substringAfter("data class $name(")
                .substringBefore("\n@Entity(")
            assertTrue("$name uses the default data-class toString", "override fun toString" in section)
            assertTrue("$name string projection lacks an explicit redaction marker", "<redacted>" in section)
            val constructor = section.substringBefore(") {")
            val projection = section.substringAfter("override fun toString")
                .substringBefore("\n}")
            val privateFields = Regex("\\bval\\s+([A-Za-z][A-Za-z0-9_]*)")
                .findAll(constructor)
                .map { it.groupValues[1] }
                .filter { field -> isPrivateProjectionField(field) }
                .toList()
            privateFields.forEach { field ->
                val directInterpolation = Regex(
                    Regex.escape("${'$'}$field") + "\\b",
                )
                val bracedInterpolation = "${'$'}{$field}"
                assertFalse(
                    "$name default string directly interpolates private field $field",
                    directInterpolation.containsMatchIn(projection) ||
                        bracedInterpolation in projection,
                )
            }
        }
    }

    @Test
    fun `structured candidate provider utility and authority surfaces expose no raw field escape hatch`() {
        val roots = listOf(
            projectDirectory(
                "app/src/main/java/me/rerere/rikkahub/learning/storage",
                "src/main/java/me/rerere/rikkahub/learning/storage",
            ),
            projectDirectory(
                "app/src/main/java/me/rerere/rikkahub/data/db/entity",
                "src/main/java/me/rerere/rikkahub/data/db/entity",
            ),
        )
        val sources = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val audited = CURRENT_LEARNING_ENTITY_INVENTORY + MAIN_AUTHORITY_PRIVACY_SURFACES
        val forbidden = listOf(
            "rawprompt",
            "prompttext",
            "conversationtext",
            "messagetext",
            "chainofthought",
            "privatereasoning",
            "toolargs",
            "toolarguments",
            "tooloutput",
            "credential",
            "secret",
            "errormessage",
            "exceptiontext",
            "absoluteuri",
            "absolutepath",
        )

        audited.forEach { name ->
            val source = sources.single { "data class $name(" in it.readText(Charsets.UTF_8) }
            val text = source.readText(Charsets.UTF_8)
            val section = text.substringAfter("data class $name(").substringBefore("\n@Entity(")
            val fields = Regex("\\bval\\s+([A-Za-z][A-Za-z0-9_]*)")
                .findAll(section.substringBefore(") {"))
                .map { it.groupValues[1] }
                .toList()
            fields.forEach { field ->
                val normalized = field.lowercase().replace("_", "")
                assertFalse(
                    "$name.$field is an unaudited raw/private-data field",
                    forbidden.any(normalized::contains),
                )
            }
        }
    }

    @Test
    fun `redacted policy review export cannot select raw authority or provider material`() {
        val rendererSource = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/runtime/" +
                "LearningRuntimePolicyPresentation.kt",
            "src/main/java/me/rerere/rikkahub/learning/runtime/" +
                "LearningRuntimePolicyPresentation.kt",
        ).readText(Charsets.UTF_8)
        val renderer = rendererSource.substringAfter(
            "internal fun PolicyReviewDetail.toRedactedPolicyReviewReport(): String",
        ).substringBefore("internal val POLICY_DISPATCH_SHA256")

        listOf(
            "triggerSummary.reviewReportValue",
            "procedureSummary.reviewReportValue",
            "verificationSummary.reviewReportValue",
            "boundarySummary.reviewReportValue",
            "failureModeSummary.reviewReportValue",
            "return report.take(MAX_POLICY_REDACTED_REPORT_CHARS)",
        ).forEach { required -> assertTrue("Review export lost $required", required in renderer) }
        listOf(
            "scope_id=",
            "policy_id=",
            "conversation_id=",
            "source_id=",
            "before_snapshot=",
            "after_snapshot=",
            "tool_args=",
            "tool_output=",
            "credential=",
        ).forEach { forbidden ->
            assertFalse("Review export contains raw field $forbidden", forbidden in renderer)
        }
    }

    @Test
    fun `reset explicitly removes independent curator and provider cohort roots`() {
        val resetter = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/handoff/LearningInboxBatchStore.kt",
            "src/main/java/me/rerere/rikkahub/learning/handoff/LearningInboxBatchStore.kt",
        ).readText(Charsets.UTF_8)
            .substringAfter("class LearningDerivedStateResetter")
            .substringBefore("private fun LearningInboxEventEntity.toInitialJob")

        val curator = resetter.indexOf("deleteAllCandidatesForDerivedReset")
        val policies = resetter.indexOf("deleteAllPolicies")
        val jobs = resetter.indexOf("jobDao().deleteAll")
        val cohorts = resetter.indexOf("deleteUnreferencedConfigCohorts")
        assertTrue("Curator roots must be reset before their Policy foreign keys", curator in 0 until policies)
        assertTrue("Provider cohorts must be reset after job manifest cascade", cohorts > jobs)

        val curatorDao = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/storage/curator/CuratorDeltaDao.kt",
            "src/main/java/me/rerere/rikkahub/learning/storage/curator/CuratorDeltaDao.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(
            "Curator reset must delete the independent candidate roots",
            "@Query(\"DELETE FROM curator_delta_candidates\")" in curatorDao,
        )
        listOf("CuratorDeltaRevisionEntity.kt", "CuratorDeltaLineageEntity.kt").forEach { file ->
            val dependent = projectFile(
                "app/src/main/java/me/rerere/rikkahub/learning/storage/curator/$file",
                "src/main/java/me/rerere/rikkahub/learning/storage/curator/$file",
            ).readText(Charsets.UTF_8)
            assertTrue("$file lost candidate-root cascade", "onDelete = ForeignKey.CASCADE" in dependent)
        }
    }

    @Test
    fun `exact scope erase addresses independent roots without requiring a Policy row`() {
        val source = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/storage/LearningRetentionPolicyV1.kt",
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningRetentionPolicyV1.kt",
        ).readText(Charsets.UTF_8)
            .substringAfter("class LearningDerivedDataEraseStore")
            .substringBefore("private fun subtractFloor")

        listOf(
            "policyShadowObservationDao()",
            "deleteEvaluationScopePage",
            "deleteAssignmentScopePage",
            "redactScopeBeforeErase",
            "policyExposureDao()",
            "deleteAssistantScope",
            "deleteAuthoritySubjectScope",
            "deletePoliciesByScope",
            "deleteRewardWindowsByScope",
            "deleteLessonsByScope",
            "deleteTraceByScope",
            "deleteEpisodesByScope",
            "deleteErasableSourceValidityByScope",
            "jobDao().deleteByScope",
            "deleteUnreferencedConfigCohortsPage",
            "inboxDao().deleteByScope",
        ).forEach { operation ->
            assertTrue("Exact-scope erase omitted independent operation $operation", operation in source)
        }
        assertFalse(
            "Independent scope roots must not be gated by Policy existence",
            Regex("if\\s*\\([^)]*polic(?:y|ies)[^)]*(?:==|>|isNotEmpty)", RegexOption.IGNORE_CASE)
                .containsMatchIn(source),
        )
        assertTrue(
            "Bounded provider cohort pruning must be included in the content-free erase receipt",
            "providerConfigCohorts = providerConfigCohorts" in source,
        )
    }

    private fun projectFile(vararg paths: String): File =
        requireNotNull(paths.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${paths.joinToString()} from ${File(".").absolutePath}"
        }

    private fun projectDirectory(vararg paths: String): File =
        requireNotNull(paths.asSequence().map(::File).firstOrNull(File::isDirectory)) {
            "Cannot locate ${paths.joinToString()} from ${File(".").absolutePath}"
        }

    private companion object {
        val CURRENT_LEARNING_ENTITY_INVENTORY = setOf(
            "LearningInboxEventEntity",
            "LearningStreamCheckpointEntity",
            "LearningJobEntity",
            "LearningEpisodeEntity",
            "LearningTraceFeatureEntity",
            "LearningEpisodeLessonEntity",
            "LearningRewardWindowEntity",
            "LearningSourceValidityEntity",
            "LearningPolicyEntity",
            "PolicyEvidenceEntity",
            "PolicyRevisionEntity",
            "PolicyLineageEntity",
            "LearningProviderConfigCohortEntity",
            "LearningProviderJobManifestEntity",
            "LearningProviderAttemptEntity",
            "LearningRewardSignalEntity",
            "PolicyRewardEvidenceEntity",
            "LearningPolicyShadowObservationEntity",
            "LearningPolicyShadowObservationItemEntity",
            "LearningPolicyExposureEntity",
            "LearningPolicyExposureItemEntity",
            "LearningObservedUtilityAssignmentEntity",
            "LearningObservedUtilityOutcomeEntity",
            "LearningObservedUtilityEvaluationReceiptEntity",
            "LearnedWorkflowCandidateEntity",
            "LearnedWorkflowCandidateRevisionEntity",
            "CuratorDeltaCandidateEntity",
            "CuratorDeltaRevisionEntity",
            "CuratorDeltaLineageEntity",
        )

        val MAIN_AUTHORITY_PRIVACY_SURFACES = setOf(
            "LearningConversationSourceAuthorityEntity",
            "LearningMessageSourceAuthorityEntity",
            "LearningOutboxEntity",
            "LearningPolicyGrantEntity",
            "LearningPolicyGrantRevisionEntity",
            "RewardFeedbackAuthorityEntity",
            "RewardFeedbackAuthorityRevisionEntity",
        )

        val PRIVATE_PROJECTION_FIELD_FRAGMENTS = setOf(
            "wire",
            "summary",
            "snapshot",
            "integrity",
            "artifact",
            "digest",
            "text",
            "payload",
            "path",
            "uri",
            "url",
        )

        fun isPrivateProjectionField(field: String): Boolean {
            val lower = field.lowercase()
            return field == "id" || field.endsWith("Id") || field.endsWith("Ids") ||
                field.endsWith("Identity") || field.endsWith("Identities") ||
                PRIVATE_PROJECTION_FIELD_FRAGMENTS.any(lower::contains)
        }
    }
}
