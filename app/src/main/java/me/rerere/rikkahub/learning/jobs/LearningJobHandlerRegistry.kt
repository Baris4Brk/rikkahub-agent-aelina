package me.rerere.rikkahub.learning.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.ai.background.BackgroundProviderAttemptAuthority
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.requiresFrozenP1ExecutionIdentity

/** Content-free, immutable input. A handler never receives Room, a DAO, or raw source content. */
class LearningJobExecutionInputV1 internal constructor(
    val jobId: String,
    val sourceEventId: String,
    val streamId: String,
    val scopeKind: String,
    val scopeId: String,
    val replayGeneration: Long,
    /** Durable job creation fence used to rebuild only the input cohort visible at enqueue time. */
    val createdAtMs: Long,
    val attempt: Int,
    val stableProviderIdempotencyKey: String,
    val executionSpec: LearningJobExecutionSpecV1,
    /** Opaque durable permit; provider-backed handlers must pass it through unchanged. */
    val providerAttemptAuthority: BackgroundProviderAttemptAuthority? = null,
    /** Content-free manifest/cap/audit receipt admitted in the same transaction as this attempt. */
    val providerManifestReceipt: LearningProviderManifestReceipt? = null,
) {
    init {
        val providerBackedJob = executionSpec.isProviderEffectJob()
        require((providerAttemptAuthority != null) == providerBackedJob) {
            "Durable provider authority does not match the execution spec"
        }
        require((providerManifestReceipt != null) == providerBackedJob) {
            "Provider manifest receipt does not match the execution spec"
        }
        providerManifestReceipt?.let { receipt ->
            val authority = requireNotNull(providerAttemptAuthority)
            require(receipt.providerKind == executionSpec.providerKindIdentity)
            require(receipt.providerIdentitySha256 == executionSpec.providerIdentity)
            require(receipt.modelIdentitySha256 == executionSpec.modelIdentity)
            require(
                receipt.configurationIdentitySha256 ==
                    executionSpec.providerConfigurationIdentity,
            )
            require(receipt.configurationGeneration == executionSpec.providerConfigGeneration)
            require(receipt.providerRequestKey == stableProviderIdempotencyKey)
            require(authority.stableProviderIdempotencyKey == stableProviderIdempotencyKey)
            require(
                authority.expectedDispatchAttestationSha256 ==
                    receipt.dispatchAttestationSha256,
            )
        }
    }

    override fun toString(): String =
        "LearningJobExecutionInputV1(type=${executionSpec.jobType}, attempt=$attempt, " +
            "scope=$scopeKind, ids=<redacted>)"
}

/** Typed output marker. Free-form output cannot be committed through the fenced completion API. */
interface LearningJobTypedOutput {
    val outputSchemaIdentity: String
}

sealed interface LearningJobHandlerResult<out O : LearningJobTypedOutput> {
    data class Success<O : LearningJobTypedOutput>(val output: O) : LearningJobHandlerResult<O>

    data class Retry(
        val errorCode: LearningJobFailureCode,
        val retryDelayMs: Long,
    ) : LearningJobHandlerResult<Nothing> {
        init {
            require(retryDelayMs in 0L..MAX_HANDLER_RETRY_DELAY_MS) {
                "Unsafe handler retry delay"
            }
        }
    }

    data class DeadLetter(
        val errorCode: LearningJobFailureCode,
    ) : LearningJobHandlerResult<Nothing>
}

/** The only interface implemented by business handlers; its signature makes DB access explicit. */
fun interface LearningJobHandler<O : LearningJobTypedOutput> {
    suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<O>
}

enum class LearningJobHandlerReadiness {
    READY,
    WAITING_CONFIGURATION,
}

fun interface LearningJobHandlerReadinessProbe {
    suspend fun current(): LearningJobHandlerReadiness
}

/** Cooperative cancellation/deadline checkpoint; the runner, not the handler, owns heartbeats. */
class LearningJobExecutionControl internal constructor(
    val monotonicDeadlineMs: Long,
    private val monotonicMs: () -> Long,
) {
    init {
        require(monotonicDeadlineMs >= 0L) { "Negative job deadline" }
    }

    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (monotonicMs() >= monotonicDeadlineMs) {
            throw LearningJobDeadlineExceededException()
        }
    }
}

class LearningJobDeadlineExceededException :
    IllegalStateException("Learning job monotonic deadline reached")

/**
 * Storage-owned writer paired with a typed handler by the registry. It is intentionally internal:
 * a handler cannot receive or manufacture the transaction capability passed to this component.
 */
internal fun interface LearningJobTypedOutputCommitter<O : LearningJobTypedOutput> {
    suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: O,
    )
}

class PreparedLearningJobCompletion private constructor(
    private val expectedInput: LearningJobExecutionInputV1,
    private val persist: suspend (LearningDatabase) -> Unit,
) {
    internal suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        currentJob: LearningJobEntity,
    ) {
        check(expectedInput.matches(currentJob)) { "Learning completion identity changed" }
        persist(database)
    }

    companion object {
        internal fun <O : LearningJobTypedOutput> create(
            input: LearningJobExecutionInputV1,
            output: O,
            committer: LearningJobTypedOutputCommitter<O>,
        ): PreparedLearningJobCompletion {
            require(output.outputSchemaIdentity.matches(SAFE_OUTPUT_SCHEMA_IDENTITY)) {
                "Invalid typed output schema identity"
            }
            if (input.executionSpec.jobType.requiresFrozenP1ExecutionIdentity) {
                require(
                    output.outputSchemaIdentity == input.executionSpec.outputSchemaIdentity
                ) { "Typed output does not match the frozen P1 output schema" }
            }
            return PreparedLearningJobCompletion(input) { database ->
                committer.persistInOpenTransaction(database, input, output)
            }
        }
    }
}

internal sealed interface LearningJobDispatchResult {
    data class Success(
        val completion: PreparedLearningJobCompletion,
        val heartbeatRequired: Boolean,
    ) : LearningJobDispatchResult

    data class Retry(val errorCode: LearningJobFailureCode, val retryDelayMs: Long) :
        LearningJobDispatchResult

    data class DeadLetter(val errorCode: LearningJobFailureCode) : LearningJobDispatchResult
}

internal data class LearningJobHandlerReadinessSnapshot(
    val readyTypes: Set<LearningJobType>,
    val registeredCount: Int,
    val waitingConfigurationCount: Int,
) {
    init {
        require(registeredCount >= 0 && waitingConfigurationCount >= 0)
        require(readyTypes.size + waitingConfigurationCount == registeredCount)
    }
}

/** Typed, duplicate-rejecting registry. The production P0 graph deliberately installs it empty. */
class LearningJobHandlerRegistry private constructor(
    private val bindings: Map<LearningJobType, ErasedLearningJobHandlerBinding>,
) {
    internal suspend fun readiness(): LearningJobHandlerReadinessSnapshot {
        val ready = linkedSetOf<LearningJobType>()
        var waiting = 0
        bindings.toSortedMap(compareBy { it.ordinal }).forEach { (type, binding) ->
            val state = try {
                binding.readiness.current()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LearningJobHandlerReadiness.WAITING_CONFIGURATION
            }
            if (state == LearningJobHandlerReadiness.READY) ready += type else waiting += 1
        }
        return LearningJobHandlerReadinessSnapshot(
            readyTypes = ready,
            registeredCount = bindings.size,
            waitingConfigurationCount = waiting,
        )
    }

    internal suspend fun dispatch(
        job: LearningJobEntity,
        monotonicDeadlineMs: Long,
        monotonicMs: () -> Long,
        providerAttemptAuthority: BackgroundProviderAttemptAuthority? = null,
        providerManifestReceipt: LearningProviderManifestReceipt? = null,
    ): LearningJobDispatchResult {
        val type = LearningJobType.entries.firstOrNull { it.name == job.jobType }
            ?: return LearningJobDispatchResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        val binding = bindings[type]
            ?: return LearningJobDispatchResult.Retry(
                LearningJobFailureCode.WAITING_CONFIGURATION,
                DEFAULT_CONFIGURATION_RETRY_DELAY_MS,
            )
        val input = job.toExecutionInput(providerAttemptAuthority, providerManifestReceipt)
            ?: return LearningJobDispatchResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        return binding.dispatch(
            input = input,
            control = LearningJobExecutionControl(monotonicDeadlineMs, monotonicMs),
        )
    }

    internal class Builder {
        private val bindings = linkedMapOf<LearningJobType, ErasedLearningJobHandlerBinding>()

        fun <O : LearningJobTypedOutput> register(
            jobType: LearningJobType,
            handler: LearningJobHandler<O>,
            outputCommitter: LearningJobTypedOutputCommitter<O>,
            readiness: LearningJobHandlerReadinessProbe =
                LearningJobHandlerReadinessProbe { LearningJobHandlerReadiness.READY },
            heartbeatRequired: Boolean = true,
        ): Builder = apply {
            check(jobType !in bindings) { "Duplicate learning job handler" }
            bindings[jobType] = TypedLearningJobHandlerBinding(
                handler = handler,
                outputCommitter = outputCommitter,
                readiness = readiness,
                heartbeatRequired = heartbeatRequired,
            )
        }

        fun build(): LearningJobHandlerRegistry = LearningJobHandlerRegistry(bindings.toMap())
    }

    companion object {
        /** No P0 placeholder is executable before P1 canonical output tables/handlers exist. */
        fun empty(): LearningJobHandlerRegistry = LearningJobHandlerRegistry(emptyMap())
    }
}

private interface ErasedLearningJobHandlerBinding {
    val readiness: LearningJobHandlerReadinessProbe

    suspend fun dispatch(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobDispatchResult
}

private class TypedLearningJobHandlerBinding<O : LearningJobTypedOutput>(
    private val handler: LearningJobHandler<O>,
    private val outputCommitter: LearningJobTypedOutputCommitter<O>,
    override val readiness: LearningJobHandlerReadinessProbe,
    private val heartbeatRequired: Boolean,
) : ErasedLearningJobHandlerBinding {
    override suspend fun dispatch(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobDispatchResult = when (val result = handler.execute(input, control)) {
        is LearningJobHandlerResult.Success -> LearningJobDispatchResult.Success(
            completion = PreparedLearningJobCompletion.create(
                input = input,
                output = result.output,
                committer = outputCommitter,
            ),
            heartbeatRequired = heartbeatRequired,
        )

        is LearningJobHandlerResult.Retry -> LearningJobDispatchResult.Retry(
            result.errorCode,
            result.retryDelayMs,
        )

        is LearningJobHandlerResult.DeadLetter -> LearningJobDispatchResult.DeadLetter(
            result.errorCode,
        )
    }
}

private fun LearningJobEntity.toExecutionInput(
    providerAttemptAuthority: BackgroundProviderAttemptAuthority?,
    providerManifestReceipt: LearningProviderManifestReceipt?,
): LearningJobExecutionInputV1? {
    val spec = LearningJobExecutionSpecs.resolve(this) ?: return null
    val providerBackedJob = spec.isProviderEffectJob()
    if (
        (providerAttemptAuthority != null) != providerBackedJob ||
        (providerManifestReceipt != null) != providerBackedJob
    ) return null
    if (
        providerManifestReceipt != null &&
        providerManifestReceipt.providerRequestKey !=
        providerAttemptAuthority?.stableProviderIdempotencyKey
    ) return null
    return LearningJobExecutionInputV1(
        jobId = id,
        sourceEventId = sourceEventId,
        streamId = streamId,
        scopeKind = scopeKind,
        scopeId = scopeId,
        replayGeneration = replayGeneration,
        createdAtMs = createdAtMs,
        attempt = attempts,
        stableProviderIdempotencyKey = providerManifestReceipt?.providerRequestKey
            ?: learningProviderIdempotencyKey(id),
        executionSpec = spec,
        providerAttemptAuthority = providerAttemptAuthority,
        providerManifestReceipt = providerManifestReceipt,
    )
}

private fun LearningJobExecutionInputV1.matches(job: LearningJobEntity): Boolean =
    jobId == job.id &&
        sourceEventId == job.sourceEventId &&
        streamId == job.streamId &&
        scopeKind == job.scopeKind &&
        scopeId == job.scopeId &&
        replayGeneration == job.replayGeneration &&
        createdAtMs == job.createdAtMs &&
        attempt == job.attempts &&
        executionSpec == LearningJobExecutionSpecs.resolve(job)

internal fun learningProviderIdempotencyKey(jobId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("learning-provider-idempotency-v1\u0000$jobId".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "learning-provider-v1:$digest"
}

private fun LearningJobExecutionSpecV1.isProviderEffectJob(): Boolean =
    providerKindIdentity in setOf("local_litert", "remote") &&
        (jobType == LearningJobType.REFLECT_EPISODE_V1 ||
            jobType == LearningJobType.DISTILL_POLICY_V1)

private const val MAX_HANDLER_RETRY_DELAY_MS = 24L * 60L * 60L * 1_000L
internal const val DEFAULT_CONFIGURATION_RETRY_DELAY_MS = 6L * 60L * 60L * 1_000L
private val SAFE_OUTPUT_SCHEMA_IDENTITY = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
