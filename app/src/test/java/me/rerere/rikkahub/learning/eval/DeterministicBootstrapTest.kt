package me.rerere.rikkahub.learning.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicBootstrapTest {
    private val config = BootstrapConfig(resamples = 500, confidenceLevelBasisPoints = 9_500)
    private val planDigest = FrozenOfflineLearningEvaluation.plan.digestSha256()

    @Test
    fun `bootstrap result is byte stable for identical input`() {
        val first = DeterministicBootstrap.mean(
            listOf(0.0, 1.0, 1.0, 0.0, 1.0), config, planDigest, "stable-rate",
        )
        val second = DeterministicBootstrap.mean(
            listOf(0.0, 1.0, 1.0, 0.0, 1.0), config, planDigest, "stable-rate",
        )
        assertEquals(first, second)
    }

    @Test
    fun `bootstrap canonicalizes input ordering`() {
        val first = DeterministicBootstrap.mean(
            listOf(-1.0, 0.0, 1.0, 1.0), config, planDigest, "paired-rate",
        )
        val second = DeterministicBootstrap.mean(
            listOf(1.0, -1.0, 1.0, 0.0), config, planDigest, "paired-rate",
        )
        assertEquals(first, second)
    }

    @Test
    fun `bootstrap preserves point estimate inside deterministic interval`() {
        val result = requireNotNull(
            DeterministicBootstrap.mean(
                listOf(0.0, 0.0, 1.0, 1.0), config, planDigest, "interval-rate",
            ),
        )
        assertEquals(0.5, result.estimate, 0.0)
        assertTrue(result.lower <= result.estimate)
        assertTrue(result.upper >= result.estimate)
        assertEquals(config.resamples, result.resamples)
    }

    @Test
    fun `empty observed sample remains unmeasured`() {
        assertNull(
            DeterministicBootstrap.mean(emptyList(), config, planDigest, "empty-rate"),
        )
    }
}
