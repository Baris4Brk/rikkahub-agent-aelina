package me.rerere.rikkahub.learning.eval

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random
import kotlin.math.ceil

internal object EvalDigest {
    fun sha256(domain: String, fields: List<String>): String {
        requireSafeEvalLabel(domain)
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, domain)
        fields.forEach { update(digest, it) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun seed(domain: String, fields: List<String>): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, domain)
        fields.forEach { update(digest, it) }
        return ByteBuffer.wrap(digest.digest(), 0, Long.SIZE_BYTES).long
    }

    fun bucket(domain: String, fields: List<String>, bound: Int): Int {
        require(bound > 0)
        val bytes = MessageDigest.getInstance("SHA-256").also { digest ->
            update(digest, domain)
            fields.forEach { update(digest, it) }
        }.digest()
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = ((value shl 8) xor (bytes[index].toInt() and 0xff).toLong()) and Long.MAX_VALUE
        }
        return (value % bound).toInt()
    }

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
    }
}

object PreRegisteredAssignmentEngine {
    fun assign(
        corpus: List<OfflineReplayUnit>,
        plan: OfflineEvalPlan,
    ): List<PreRegisteredAssignment> {
        require(corpus.map(OfflineReplayUnit::unitId).distinct().size == corpus.size)
        return corpus.sortedBy(OfflineReplayUnit::unitId).map { unit ->
            val fields = listOf(plan.planId, plan.assignmentSalt, unit.unitId)
            val holdout = EvalDigest.bucket("offline-eval-holdout-v1", fields, 10_000) <
                plan.holdoutBasisPoints
            val arm = plan.arms[
                EvalDigest.bucket("offline-eval-primary-arm-v1", fields, plan.arms.size)
            ]
            PreRegisteredAssignment(
                unitId = unit.unitId,
                primaryArm = arm,
                partition = if (holdout) EvalPartition.HOLDOUT else EvalPartition.MATCHED_REPLAY,
            )
        }
    }

    fun manifestDigest(assignments: List<PreRegisteredAssignment>): String = EvalDigest.sha256(
        domain = "offline-eval-assignment-manifest-v1",
        fields = assignments.sortedBy(PreRegisteredAssignment::unitId).flatMap { assignment ->
            listOf(assignment.unitId, assignment.primaryArm.name, assignment.partition.name)
        },
    )
}

object DeterministicBootstrap {
    /** Deterministic percentile-bootstrap mean; inputs are sorted to remove collection-order drift. */
    fun mean(
        values: List<Double>,
        config: BootstrapConfig,
        planDigestSha256: String,
        metricKey: String,
    ): ConfidenceInterval? {
        if (values.isEmpty()) return null
        require(values.all(Double::isFinite))
        require(planDigestSha256.matches(Regex("[0-9a-f]{64}")))
        requireSafeEvalLabel(metricKey)
        val canonical = values.sorted()
        val estimate = canonical.average()
        val random = Random(
            EvalDigest.seed(
                "offline-eval-bootstrap-seed-v1",
                listOf(planDigestSha256, metricKey, canonical.joinToString(",") { it.toString() }),
            ),
        )
        val resampledMeans = DoubleArray(config.resamples) {
            var sum = 0.0
            repeat(canonical.size) {
                sum += canonical[random.nextInt(canonical.size)]
            }
            sum / canonical.size.toDouble()
        }.sortedArray()
        val tailBasisPoints = (10_000 - config.confidenceLevelBasisPoints) / 2.0
        val lower = percentile(resampledMeans, tailBasisPoints / 10_000.0)
        val upper = percentile(resampledMeans, 1.0 - tailBasisPoints / 10_000.0)
        return ConfidenceInterval(
            lower = minOf(lower, estimate),
            estimate = estimate,
            upper = maxOf(upper, estimate),
            confidenceLevelBasisPoints = config.confidenceLevelBasisPoints,
            resamples = config.resamples,
        )
    }

    private fun percentile(values: DoubleArray, fraction: Double): Double {
        val rank = (ceil(fraction * values.size).toInt() - 1).coerceIn(values.indices)
        return values[rank]
    }
}

internal fun OfflineEvalPlan.digestSha256(): String = EvalDigest.sha256(
    domain = "offline-eval-plan-v1",
    fields = listOf(
        planId,
        assignmentSalt,
        holdoutBasisPoints.toString(),
        bootstrap.resamples.toString(),
        bootstrap.confidenceLevelBasisPoints.toString(),
    ) + arms.map(OfflineEvalArm::name),
)
