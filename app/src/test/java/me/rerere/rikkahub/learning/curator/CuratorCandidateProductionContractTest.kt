package me.rerere.rikkahub.learning.curator

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CuratorCandidateProductionContractTest {
    private val scope = LearningScope.Assistant(Uuid.parse("00000000-0000-0000-0000-000000000031"))
    private val source = CuratorSourceFence("policy-a", scope, 7L, "a".repeat(64))
    private val evidence = CuratorEvidenceRef("source-a", scope, 3L, "e".repeat(64))
    private val candidate = CuratorDeltaCandidate.Update(
        candidateId = "candidate-a",
        source = source,
        evidence = listOf(evidence),
        diffs = listOf(
            CuratorTargetDiff(
                "policy-a",
                listOf(
                    CuratorFieldDiff(
                        CuratorPolicyField.BOUNDARY,
                        "b".repeat(64),
                        "Require an explicit reviewed boundary.",
                    ),
                ),
            ),
        ),
    )
    private val exact = CuratorProductionSourceFence(source, 5L, "ACTIVE", 900L)

    @Test
    fun `proposal requires an explicit user review and exact sorted source set`() {
        assertThrows(IllegalArgumentException::class.java) {
            CuratorCandidateProductionRequest(candidate, listOf(exact), false, 901L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CuratorCandidateProductionRequest(
                candidate,
                listOf(exact.copy(source = source.copy(policyId = "policy-b"))),
                true,
                901L,
            )
        }
    }

    @Test
    fun `production store is transactional proposed-only and rechecks exact evidence`() {
        val source = mainSource("storage/curator/RoomCuratorCandidateProductionStore.kt")
        assertTrue(source.contains("database.withTransaction"))
        assertTrue(source.contains("expectedContentRevision"))
        assertTrue(source.contains("expectedUpdatedAtMs"))
        assertTrue(source.contains("SOURCE_NOT_REVIEWED"))
        assertTrue(source.contains("countExactValidEvidenceFence"))
        assertTrue(source.contains("listEvidenceValidity"))
        assertTrue(source.contains("listExactReviewedSources"))
        assertTrue(source.contains("CuratorProductionSourceFence"))
        assertTrue(source.contains("DeterministicCuratorDeltaApplier"))
        assertTrue(source.contains("insertProposed("))
        assertTrue(source.contains("CuratorDeltaRevisionActor.USER"))
        assertTrue(!source.contains("applyApproved("))
        assertTrue(!source.contains("transitionFenced("))
    }

    @Test
    fun `facade and dependency graph expose only the typed production boundary`() {
        val facade = mainSource("runtime/LearningRuntimeFacade.kt")
        val di = mainSource("../di/DataSourceModule.kt")
        assertTrue(facade.contains("CuratorCandidateProductionStore"))
        assertTrue(facade.contains("RoomCuratorCandidateProductionStore"))
        assertTrue(di.contains("CuratorCandidateProductionCoordinator"))
    }

    @Test
    fun `disabled exact operation performs zero proposal writes`() = runBlocking {
        var writes = 0
        val store = CuratorCandidateProductionStore {
            writes += 1
            CuratorCandidateProductionResult.Proposed(
                candidateId = it.candidate.candidateId,
                candidateSha256 = "c".repeat(64),
                stateVersion = 1L,
                proposedAtMs = it.proposedAtMs,
            )
        }
        val request = CuratorCandidateProductionRequest(candidate, listOf(exact), true, 901L)

        val denied = CuratorCandidateProductionCoordinator(
            store,
            DisabledLearningPositiveMutationGate,
        ).propose(request)
        assertEquals(
            CuratorCandidateProductionResult.Conflict(
                CuratorCandidateProductionConflict.ROLLOUT_DISABLED,
            ),
            denied,
        )
        assertEquals(0, writes)

        val updateOnly = LearningPositiveMutationGate {
            it == LearningPositiveMutation.CURATOR_UPDATE_CANDIDATE
        }
        val proposed = CuratorCandidateProductionCoordinator(store, updateOnly).propose(request)
        assertTrue(proposed is CuratorCandidateProductionResult.Proposed)
        assertEquals(1, writes)
    }

    private fun mainSource(relative: String): String {
        val appRoot = Paths.get(System.getProperty("user.dir")).let { cwd ->
            if (Files.isDirectory(cwd.resolve("src/main"))) cwd else cwd.resolve("app")
        }
        val normalized = if (relative.startsWith("../di/")) {
            appRoot.resolve(
                "src/main/java/me/rerere/rikkahub/di/${relative.removePrefix("../di/")}",
            )
        } else {
            appRoot.resolve("src/main/java/me/rerere/rikkahub/learning/$relative")
        }
        return Files.readString(normalized)
    }
}
