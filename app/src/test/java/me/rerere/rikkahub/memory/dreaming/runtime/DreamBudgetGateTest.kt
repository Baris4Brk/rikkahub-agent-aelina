package me.rerere.rikkahub.memory.dreaming.runtime

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamBudgetGateTest {
    @Test
    fun `UTC day window is start inclusive and end exclusive`() {
        val window = checkNotNull(dreamUtcDayWindowOrNull(DREAM_UTC_DAY_MILLIS + 123L))

        assertEquals(DREAM_UTC_DAY_MILLIS, window.startInclusiveEpochMs)
        assertEquals(DREAM_UTC_DAY_MILLIS * 2, window.endExclusiveEpochMs)
        assertTrue(window.startInclusiveEpochMs in window)
        assertTrue(window.endExclusiveEpochMs - 1 in window)
        assertFalse(window.endExclusiveEpochMs in window)
        assertEquals(null, dreamUtcDayWindowOrNull(-1L))
        assertEquals(null, dreamUtcDayWindowOrNull(Long.MAX_VALUE))
    }

    @Test
    fun `exact budget boundary is allowed and next token is denied`() {
        val policy = DreamingCostPolicy(
            dailyRunLimit = 2,
            dailyInputTokenLimit = 100,
            dailyOutputTokenLimit = 50,
        )
        val window = checkNotNull(dreamUtcDayWindowOrNull(NOW))
        val usage = DreamDailyUsage(1, 60, 30, 0, 0)

        assertTrue(
            evaluateDreamBudget(request(input = 40, output = 20), policy, window, usage) is
                DreamBudgetEvaluation.Allowed,
        )
        assertEquals(
            DreamBudgetDenialReason.INPUT_TOKEN_LIMIT,
            deniedReason(evaluateDreamBudget(request(input = 41, output = 20), policy, window, usage)),
        )
    }

    @Test
    fun `unmeasured usage fails closed only when its token cap is enabled`() {
        val window = checkNotNull(dreamUtcDayWindowOrNull(NOW))
        val usage = DreamDailyUsage(1, 0, 0, 1, 1)

        assertEquals(
            DreamBudgetDenialReason.INPUT_USAGE_UNMEASURED,
            deniedReason(evaluateDreamBudget(request(), DreamingCostPolicy(), window, usage)),
        )
        assertTrue(
            evaluateDreamBudget(
                request(input = null),
                DreamingCostPolicy(
                    dailyInputTokenLimit = null,
                    dailyOutputTokenLimit = null,
                ),
                window,
                usage,
            ) is DreamBudgetEvaluation.Allowed,
        )
    }

    @Test
    fun `enabled input cap rejects an unavailable estimate`() {
        val evaluation = evaluateDreamBudget(
            request(input = null),
            DreamingCostPolicy(),
            checkNotNull(dreamUtcDayWindowOrNull(NOW)),
            DreamDailyUsage(0, 0, 0, 0, 0),
        )

        assertEquals(DreamBudgetDenialReason.INPUT_ESTIMATE_UNAVAILABLE, deniedReason(evaluation))
    }

    @Test
    fun `process permit serializes cross-scope budget reads and prevents double spend`() = runBlocking {
        val spent = AtomicLong(0)
        val policy = DreamingCostPolicy(
            dailyRunLimit = 4,
            dailyInputTokenLimit = 100,
            dailyOutputTokenLimit = null,
        )
        val source = DreamingCostPolicySource { policy }
        val store = DreamDailyUsageStore {
            DreamDailyUsage(0, spent.get(), 0, 0, 0)
        }
        val gates = listOf(DreamBudgetGate(source, store), DreamBudgetGate(source, store))

        val results = gates.mapIndexed { index, gate ->
            async(Dispatchers.Default) {
                gate.withPermit(
                    request(
                        scopeId = if (index == 0) PRIVATE_SCOPE else DreamScopeId.Global,
                        runId = "run-$index",
                        input = 60,
                    ),
                ) {
                    spent.addAndGet(60)
                }
            }
        }.awaitAll()

        assertEquals(1, results.count { it is DreamBudgetPermitResult.Granted<*> })
        assertEquals(1, results.count { it is DreamBudgetPermitResult.Denied })
        assertEquals(60L, spent.get())
    }

    private fun request(
        scopeId: DreamScopeId = PRIVATE_SCOPE,
        runId: String = "run-a",
        input: Long? = 10,
        output: Long = 10,
    ) = DreamBudgetAdmissionRequest(
        scopeId = scopeId,
        runId = runId,
        nowEpochMs = NOW,
        firstProviderAttempt = true,
        estimatedInputTokens = input,
        maxOutputTokens = output,
    )

    private fun deniedReason(evaluation: DreamBudgetEvaluation): DreamBudgetDenialReason =
        (evaluation as DreamBudgetEvaluation.Denied).denial.reason

    private companion object {
        const val NOW = DREAM_UTC_DAY_MILLIS * 10 + 123
        val PRIVATE_SCOPE = DreamScopeId.requireCanonical(
            "20000000-0000-4000-8000-000000000005",
        )
    }
}
