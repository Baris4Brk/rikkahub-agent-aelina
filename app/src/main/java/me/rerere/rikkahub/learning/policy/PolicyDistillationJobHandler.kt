package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.RoundingMode
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationClient
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationFailureReason
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationRequestV1
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationResult
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionControl
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionInputV1
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import me.rerere.rikkahub.learning.jobs.LearningJobHandler
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerResult
import me.rerere.rikkahub.learning.jobs.LearningJobTypedOutput
import me.rerere.rikkahub.learning.jobs.P1LearningConfigurationUnavailableException
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import me.rerere.rikkahub.learning.privacy.LearningOutboundReceipt

data class PolicyDistillationMaterial(
    val input: PolicyDistillationInput,
    val payloadJson: String,
    val frozenModel: ResolvedLearningModel,
)

fun interface PolicyDistillationMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): PolicyDistillationMaterial?
}

sealed interface PolicyCandidateJobOutput : LearningJobTypedOutput {
    val producerConfigurationDigest: String
    val producerProviderKind: String
    val outboundReceipt: LearningOutboundReceipt

    override val outputSchemaIdentity: String
        get() = "policy-candidate-v1"

    data class Abstained(
        val inputSetIdentity: String,
        override val producerConfigurationDigest: String,
        override val producerProviderKind: String,
        override val outboundReceipt: LearningOutboundReceipt,
    ) : PolicyCandidateJobOutput {
        override fun toString(): String = "PolicyCandidateJobOutput.Abstained(ids=<redacted>)"
    }

    data class Candidate(
        val draft: PolicyCandidateDraft,
        override val producerConfigurationDigest: String,
        override val producerProviderKind: String,
        override val outboundReceipt: LearningOutboundReceipt,
    ) : PolicyCandidateJobOutput {
        override fun toString(): String = "PolicyCandidateJobOutput.Candidate(draft=$draft)"
    }
}

/**
 * Distillation persistence boundary. Reflection never calls this with raw CoT; the resolver
 * supplies a bounded, redacted input and exact evidence allowlist, then this handler owns the
 * single provider call and strict output parse.
 */
class PolicyDistillationJobHandler(
    private val materialResolver: PolicyDistillationMaterialResolver,
    private val client: BackgroundGenerationClient,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : LearningJobHandler<PolicyCandidateJobOutput> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<PolicyCandidateJobOutput> {
        control.checkpoint()
        val material = try {
            materialResolver.resolve(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: P1LearningConfigurationUnavailableException) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.WAITING_CONFIGURATION,
                RETRY_DELAY_MS,
            )
        } catch (_: Exception) {
            return LearningJobHandlerResult.Retry(LearningJobFailureCode.SOURCE_MISSING, RETRY_DELAY_MS)
        } ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.SOURCE_MISSING)
        val request = try {
            BackgroundGenerationRequestV1(
                prompt = PolicyDistillationPrompt.create(material.payloadJson),
                frozenModel = material.frozenModel,
                templateVersion = PolicyDistillationPrompt.TEMPLATE_VERSION,
                maxOutputTokens = PolicyDistillationPrompt.MAX_OUTPUT_TOKENS,
                maxOutputUtf8Bytes = ReasoningPolicyDistiller.MAX_OUTPUT_UTF8_BYTES,
                timeoutMs = DISTILLATION_TIMEOUT_MS,
            )
        } catch (_: IllegalArgumentException) {
            return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        }
        control.checkpoint()
        return when (val generation = client.generate(request)) {
            is BackgroundGenerationResult.Success -> {
                val receipt = LearningOutboundReceipt(
                    providerIdentityDigest = material.frozenModel.providerIdentityDigest,
                    modelIdentityDigest = material.frozenModel.modelIdentityDigest,
                    fieldCategories = request.prompt.fieldCategories,
                    inputTokens = generation.usage?.promptTokens?.toLong(),
                    outputTokens = generation.usage?.completionTokens?.toLong(),
                    costMicros = generation.usage?.cost?.toDistillationMicrosOrNull(),
                    createdAtMs = clockMs().coerceAtLeast(0L),
                )
                when (
                    val result = ReasoningPolicyDistiller.distill(
                        generation.text,
                        material.input,
                    )
                ) {
                    PolicyDistillationResult.Abstained -> LearningJobHandlerResult.Success(
                        PolicyCandidateJobOutput.Abstained(
                            inputSetIdentity = PolicyCandidateIdFactory.inputSetHash(
                                material.input.evidenceAllowlist.values.toList(),
                            ),
                            producerConfigurationDigest = material.frozenModel.configurationDigest,
                            producerProviderKind = material.frozenModel.providerKind.toPolicyWireCode(),
                            outboundReceipt = receipt,
                        ),
                    )
                    is PolicyDistillationResult.Candidate -> LearningJobHandlerResult.Success(
                        PolicyCandidateJobOutput.Candidate(
                            draft = result.draft,
                            producerConfigurationDigest = material.frozenModel.configurationDigest,
                            producerProviderKind = material.frozenModel.providerKind.toPolicyWireCode(),
                            outboundReceipt = receipt,
                        ),
                    )
                    is PolicyDistillationResult.Rejected -> LearningJobHandlerResult.DeadLetter(
                        LearningJobFailureCode.INVALID_JOB_SPEC,
                    )
                }
            }

            is BackgroundGenerationResult.Deferred -> if (
                generation.providerAttemptState.safeForBlindRetry
            ) {
                LearningJobHandlerResult.Retry(
                    LearningJobFailureCode.WAITING_CONFIGURATION,
                    RETRY_DELAY_MS,
                )
            } else {
                LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INTERNAL)
            }

            is BackgroundGenerationResult.Failure -> when {
                generation.providerAttemptState.safeForBlindRetry -> LearningJobHandlerResult.Retry(
                    generation.reason.toPolicyFailureCode(),
                    RETRY_DELAY_MS,
                )
                else -> LearningJobHandlerResult.DeadLetter(
                    generation.reason.toPolicyFailureCode(),
                )
            }
        }
    }

    private companion object {
        const val DISTILLATION_TIMEOUT_MS = 2L * 60L * 1_000L
        const val RETRY_DELAY_MS = 15L * 60L * 1_000L
    }
}

private fun BackgroundGenerationFailureReason.toPolicyFailureCode(): LearningJobFailureCode =
    when (this) {
        BackgroundGenerationFailureReason.PROVIDER_TIMEOUT ->
            LearningJobFailureCode.DEADLINE_EXCEEDED
        BackgroundGenerationFailureReason.PROVIDER_CANCELLED ->
            LearningJobFailureCode.INTERNAL
        BackgroundGenerationFailureReason.BINDER_FAILURE,
        BackgroundGenerationFailureReason.PROVIDER_FAILURE,
        BackgroundGenerationFailureReason.PROVIDER_INCOMPLETE,
        -> LearningJobFailureCode.INTERNAL
        else -> LearningJobFailureCode.INVALID_JOB_SPEC
    }

private fun me.rerere.rikkahub.learning.model.LearningProviderKind.toPolicyWireCode(): String =
    when (this) {
        me.rerere.rikkahub.learning.model.LearningProviderKind.LOCAL_LITERT -> "local_litert"
        me.rerere.rikkahub.learning.model.LearningProviderKind.REMOTE -> "remote"
        me.rerere.rikkahub.learning.model.LearningProviderKind.AICORE ->
            throw IllegalArgumentException("AICore is not a Learning provider")
    }

private fun Double.toDistillationMicrosOrNull(): Long? = runCatching {
    require(isFinite() && this >= 0.0)
    BigDecimal.valueOf(this)
        .multiply(BigDecimal.valueOf(1_000_000L))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
