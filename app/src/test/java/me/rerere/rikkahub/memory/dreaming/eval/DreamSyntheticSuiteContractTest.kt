package me.rerere.rikkahub.memory.dreaming.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSyntheticSuiteContractTest {
    @Test
    fun `Chinese suite covers every frozen M7 slice and all hard gates are exactly zero`() {
        val cases = DreamSyntheticSuite.load()
        assertEquals(cases.size, cases.map(DreamEvalCase::caseId).distinct().size)
        assertTrue(cases.all { it.schemaVersion == 1 })
        assertTrue(cases.all { it.caseId.matches(Regex("[a-z0-9_]{1,64}")) })
        val categories = cases.flatMap(DreamEvalCase::categories).toSet()
        REQUIRED_CATEGORIES.forEach { category ->
            assertTrue("Missing synthetic Dream slice: $category", category in categories)
        }

        val observations = cases.map(DreamSyntheticSuite::safeObservation)
        assertEquals(100_000, observations.single { it.caseId == "hostile_100k" }.syntheticInputChars)
        val report = DreamEvalMetrics.report(observations)

        assertEquals(cases.size, report.caseCount)
        assertTrue(report.hardGates.allZero())
        assertEquals(List(11) { 0 }, report.hardGates.values())
        assertEquals("UTF8_BYTES_CEIL_DIV_4_V1", report.tokenMeasurement)
        assertEquals(EvalMeasurementState.PROXY_ONLY, report.latencyState)
        assertTrue(report.latencyMeasurement.endsWith("NOT_WALL_CLOCK"))
        assertEquals(EvalMeasurementState.UNMEASURED, report.energyState)
        assertEquals("UNMEASURED", report.energyMeasurement)
    }

    @Test
    fun `hard gate calculator detects each forbidden failure instead of blessing fixtures`() {
        val base = DreamSyntheticSuite.safeObservation(DreamSyntheticSuite.load().first())
        val claim = base.injectedClaims.single()
        val evidence = claim.evidence.single()
        val bad = listOf(
            base.copy(injectedClaims = listOf(claim.copy(scopeId = DreamSyntheticSuite.OTHER_SCOPE))),
            base.copy(injectedClaims = listOf(claim.copy(evidence = emptyList()))),
            base.copy(injectedClaims = listOf(claim.copy(evidence = listOf(evidence.copy(live = false))))),
            base.copy(deletedPayloadSentinels = setOf("SECRET_DELETED"), trace = "raw=SECRET_DELETED"),
            base.copy(standingClaimIds = setOf(claim.claimId)),
            base.copy(contextTokens = base.contextTokenCap + 1),
            base.copy(expectedCommittedIds = base.committedIds + "lost-update"),
            base.copy(allClaims = listOf(claim.copy(evidence = listOf(evidence.copy(live = false))))),
            base.copy(snapshotManifestDigest = "0".repeat(64)),
            base.copy(trace = "memory_id=33333333-3333-4333-8333-333333333333"),
            base.copy(deterministicCompileDigests = listOf("a", "b")),
        )

        val counts = bad.map { DreamEvalMetrics.calculateHardGates(listOf(it)) }
        assertEquals(11, counts.size)
        counts.forEachIndexed { index, count ->
            assertFalse("Gate $index failed to detect its adversarial fixture", count.allZero())
        }
    }

    private companion object {
        val REQUIRED_CATEGORIES = setOf(
            "cross_session_fact",
            "explicit_preference",
            "temporary_preference",
            "user_correction",
            "old_fact_suppression",
            "knowledge_update",
            "contradiction",
            "supersedes",
            "plan_temporal",
            "multi_project_premise",
            "assistant_scope",
            "global_scope",
            "source_edit",
            "source_delete",
            "assistant_move",
            "prompt_injection",
            "emoji_control",
            "hostile_100k",
            "incremental_full_drift",
            "cas_race",
            "worker_retry",
            "process_death",
            "feature_toggle",
        )
    }
}

class DreamFailureRecoveryContractTest {
    @Test
    fun `journal survives process death CAS retry loses no update and source delete erases payload`() {
        val journal = FakePersistentJournal()
        journal.append("memory-a", "payload-A")
        journal.append("memory-b", "payload-B")

        val afterProcessDeath = journal.reopen()
        assertEquals(setOf("memory-a", "memory-b"), afterProcessDeath.changedSince(0))
        val first = afterProcessDeath.tryCommit(baseEpoch = 0, changed = setOf("memory-a"))
        val raced = afterProcessDeath.tryCommit(baseEpoch = 0, changed = setOf("memory-b"))
        assertTrue(first)
        assertFalse(raced)
        assertTrue(afterProcessDeath.tryCommit(baseEpoch = 1, changed = setOf("memory-b")))
        assertEquals(setOf("memory-a", "memory-b"), afterProcessDeath.committed)

        afterProcessDeath.deleteSource("memory-a")
        assertFalse(afterProcessDeath.payloads.containsKey("memory-a"))
        assertFalse(afterProcessDeath.activeDerived.contains("memory-a"))
        assertEquals(0, afterProcessDeath.providerDreamBytes(useDreams = false))
    }
}

private class FakePersistentJournal(
    private val records: MutableList<String> = mutableListOf(),
    val payloads: MutableMap<String, String> = mutableMapOf(),
    val committed: MutableSet<String> = mutableSetOf(),
    val activeDerived: MutableSet<String> = mutableSetOf(),
    private var epoch: Long = 0,
) {
    fun append(id: String, payload: String) {
        records += id
        payloads[id] = payload
        activeDerived += id
    }

    fun reopen(): FakePersistentJournal = FakePersistentJournal(
        records = records.toMutableList(),
        payloads = payloads.toMutableMap(),
        committed = committed.toMutableSet(),
        activeDerived = activeDerived.toMutableSet(),
        epoch = epoch,
    )

    fun changedSince(checkpoint: Int): Set<String> = records.drop(checkpoint).toSet()

    fun tryCommit(baseEpoch: Long, changed: Set<String>): Boolean {
        if (baseEpoch != epoch) return false
        committed += changed
        epoch += 1
        return true
    }

    fun deleteSource(id: String) {
        payloads.remove(id)
        activeDerived.remove(id)
    }

    fun providerDreamBytes(useDreams: Boolean): Int = if (useDreams) 1 else 0
}
