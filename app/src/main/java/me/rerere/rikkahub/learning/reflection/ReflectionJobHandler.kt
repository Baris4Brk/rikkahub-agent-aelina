package me.rerere.rikkahub.learning.reflection

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

data class ReflectionJobMaterial(
    val input: ReflectionInputBundle,
    /** Exact content-free model identity frozen when this job was claimed. */
    val frozenModel: ResolvedLearningModel,
)

fun interface ReflectionJobMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): ReflectionJobMaterial?
}

sealed interface EpisodeLessonJobOutput : LearningJobTypedOutput {
    override val outputSchemaIdentity: String
        get() = "episode-lesson-v1"

    data class Abstained(
        val inputId: String,
        val producerConfigurationDigest: String,
        val producerProviderKind: String,
        val outboundReceipt: LearningOutboundReceipt,
    ) : EpisodeLessonJobOutput {
        override fun toString(): String = "EpisodeLessonJobOutput.Abstained(id=<redacted>)"
    }

    data class Lesson(
        val episodeId: String,
        val draft: ValidatedEpisodeLessonDraft,
        val producerProviderDigest: String,
        val producerModelDigest: String,
        val producerConfigurationDigest: String,
        val producerProviderKind: String,
        val promptVersion: String,
        val templateVersion: String,
        val outboundReceipt: LearningOutboundReceipt,
    ) : EpisodeLessonJobOutput {
        override fun toString(): String =
            "EpisodeLessonJobOutput.Lesson(type=${draft.lessonType}, evidence=${draft.evidence.size}, " +
                "producer=<redacted>, ids=<redacted>)"
    }
}

/** One bounded reflection call. It never creates or mutates a Policy. */
class ReflectionJobHandler(
    private val materialResolver: ReflectionJobMaterialResolver,
    private val client: BackgroundGenerationClient,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : LearningJobHandler<EpisodeLessonJobOutput> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<EpisodeLessonJobOutput> {
        control.checkpoint()
        val attemptAuthority = input.providerAttemptAuthority
            ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        val manifest = input.providerManifestReceipt
            ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        if (
            manifest.providerRequestKey != input.stableProviderIdempotencyKey ||
            attemptAuthority.stableProviderIdempotencyKey != input.stableProviderIdempotencyKey ||
            attemptAuthority.expectedDispatchAttestationSha256 !=
            manifest.dispatchAttestationSha256 ||
            manifest.maxOutputTokens != ReflectionPrompt.MAX_OUTPUT_TOKENS.toLong() ||
            manifest.maxOutputUtf8Bytes != ReflectionPrompt.MAX_OUTPUT_UTF8_BYTES.toLong() ||
            manifest.maxProviderCalls != 1 || !manifest.hasValidCostReservation() ||
            manifest.timeoutMs != REFLECTION_TIMEOUT_MS
        ) return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
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
        if (!manifest.matchesFrozenDispatchModel(material.frozenModel)) {
            return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        }
        val request = try {
            BackgroundGenerationRequestV1(
                prompt = ReflectionPrompt.create(material.input),
                frozenModel = material.frozenModel,
                templateVersion = ReflectionPrompt.TEMPLATE_VERSION,
                inputIdentitySha256 = manifest.inputIdentitySha256,
                maxOutputTokens = ReflectionPrompt.MAX_OUTPUT_TOKENS,
                maxOutputUtf8Bytes = ReflectionPrompt.MAX_OUTPUT_UTF8_BYTES,
                timeoutMs = REFLECTION_TIMEOUT_MS,
                providerAttemptAuthority = attemptAuthority,
            )
        } catch (_: IllegalArgumentException) {
            return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        }
        control.checkpoint()
        return when (val result = client.generate(request)) {
            is BackgroundGenerationResult.Success -> {
                val receipt = LearningOutboundReceipt(
                    providerIdentityDigest = material.frozenModel.providerIdentityDigest,
                    modelIdentityDigest = material.frozenModel.modelIdentityDigest,
                    fieldCategories = request.prompt.fieldCategories,
                    inputTokens = result.usage?.promptTokens?.toLong(),
                    outputTokens = result.usage?.completionTokens?.toLong(),
                    costMicros = result.usage?.cost?.toMicrosOrNull(),
                    createdAtMs = clockMs().coerceAtLeast(0L),
                )
                when (val parsed = ReflectionParser.parse(result.text, material.input)) {
                    ReflectionParseResult.Abstained -> LearningJobHandlerResult.Success(
                        EpisodeLessonJobOutput.Abstained(
                            inputId = material.input.inputId,
                            producerConfigurationDigest = material.frozenModel.configurationDigest,
                            producerProviderKind = material.frozenModel.providerKind.toWireCode(),
                            outboundReceipt = receipt,
                        ),
                    )
                    is ReflectionParseResult.Lesson -> LearningJobHandlerResult.Success(
                        EpisodeLessonJobOutput.Lesson(
                            episodeId = material.input.episodeId.value,
                            draft = parsed.draft,
                            producerProviderDigest = material.frozenModel.providerIdentityDigest,
                            producerModelDigest = material.frozenModel.modelIdentityDigest,
                            producerConfigurationDigest = material.frozenModel.configurationDigest,
                            producerProviderKind = material.frozenModel.providerKind.toWireCode(),
                            promptVersion = ReflectionPrompt.TEMPLATE_VERSION,
                            templateVersion = ReflectionPrompt.TEMPLATE_VERSION,
                            outboundReceipt = receipt,
                        ),
                    )
                    is ReflectionParseResult.Rejected -> LearningJobHandlerResult.DeadLetter(
                        LearningJobFailureCode.INVALID_JOB_SPEC,
                    )
                }
            }

            is BackgroundGenerationResult.Deferred -> if (
                result.providerAttemptState.safeForBlindRetry
            ) {
                LearningJobHandlerResult.Retry(
                    LearningJobFailureCode.WAITING_CONFIGURATION,
                    RETRY_DELAY_MS,
                )
            } else {
                LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INTERNAL)
            }

            is BackgroundGenerationResult.Failure -> when {
                result.providerAttemptState.safeForBlindRetry -> LearningJobHandlerResult.Retry(
                    result.reason.toFailureCode(),
                    RETRY_DELAY_MS,
                )
                else -> LearningJobHandlerResult.DeadLetter(result.reason.toFailureCode())
            }
        }
    }

    private fun BackgroundGenerationFailureReason.toFailureCode(): LearningJobFailureCode = when (this) {
        BackgroundGenerationFailureReason.PROVIDER_TIMEOUT -> LearningJobFailureCode.DEADLINE_EXCEEDED
        BackgroundGenerationFailureReason.PROVIDER_CANCELLED -> LearningJobFailureCode.INTERNAL
        BackgroundGenerationFailureReason.BINDER_FAILURE,
        BackgroundGenerationFailureReason.PROVIDER_FAILURE,
        BackgroundGenerationFailureReason.PROVIDER_INCOMPLETE -> LearningJobFailureCode.INTERNAL
        else -> LearningJobFailureCode.INVALID_JOB_SPEC
    }

    private companion object {
        const val REFLECTION_TIMEOUT_MS = 2L * 60L * 1_000L
        const val RETRY_DELAY_MS = 15L * 60L * 1_000L
    }
}

private fun me.rerere.rikkahub.learning.jobs.LearningProviderManifestReceipt
    .hasValidCostReservation(): Boolean = when (providerKind) {
    "local_litert" -> maxCostMicros == 0L
    "remote" -> maxCostMicros ==
        me.rerere.rikkahub.learning.jobs.REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS
    else -> false
}

private fun me.rerere.rikkahub.learning.jobs.LearningProviderManifestReceipt
    .matchesFrozenDispatchModel(model: ResolvedLearningModel): Boolean = when (providerKind) {
    "local_litert" -> model.providerKind ==
        me.rerere.rikkahub.learning.model.LearningProviderKind.LOCAL_LITERT &&
        model.runtimeAttestationDigest == dispatchAttestationSha256
    "remote" -> model.providerKind ==
        me.rerere.rikkahub.learning.model.LearningProviderKind.REMOTE &&
        model.runtimeAttestationDigest == null && dispatchAttestationSha256.length == 64
    else -> false
}

private fun me.rerere.rikkahub.learning.model.LearningProviderKind.toWireCode(): String = when (this) {
    me.rerere.rikkahub.learning.model.LearningProviderKind.LOCAL_LITERT -> "local_litert"
    me.rerere.rikkahub.learning.model.LearningProviderKind.REMOTE -> "remote"
    me.rerere.rikkahub.learning.model.LearningProviderKind.AICORE ->
        throw IllegalArgumentException("AICore is not a Learning provider")
}

private fun Double.toMicrosOrNull(): Long? = runCatching {
    require(isFinite() && this >= 0.0)
    BigDecimal.valueOf(this)
        .multiply(BigDecimal.valueOf(1_000_000L))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
