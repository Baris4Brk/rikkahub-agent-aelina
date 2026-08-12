package me.rerere.rikkahub.memory.dreaming.synthesis

import me.rerere.rikkahub.memory.dreaming.input.DreamModelInput
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256

data class DreamSynthesizeRequest(
    val input: DreamModelInput,
    val maxOutputTokens: Int,
    val promptContractVersion: String,
    val validatorVersion: String,
) {
    init {
        require(maxOutputTokens in 1..65_536)
        require(promptContractVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(validatorVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
    }
}

data class DreamModelAudit(
    val providerKind: String,
    val modelIdentityDigest: DreamSha256,
    val promptContractVersion: String,
    val validatorVersion: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
) {
    init {
        require(providerKind.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(promptContractVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(validatorVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(inputTokens == null || inputTokens >= 0)
        require(outputTokens == null || outputTokens >= 0)
    }
}

sealed interface DreamSynthesizeResult {
    data class Success(
        val rawOutput: String,
        val audit: DreamModelAudit,
    ) : DreamSynthesizeResult

    data class Failure(
        val reason: DreamSynthesizeFailure,
        val retryable: Boolean,
    ) : DreamSynthesizeResult
}

enum class DreamSynthesizeFailure {
    PROVIDER_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    TIMEOUT,
    CANCELLED_BY_PROVIDER,
    OUTPUT_LIMIT,
    SAFETY_REJECTION,
    INVALID_CONFIGURATION,
}

/** The implementation may use a remote model, but is called only after all read transactions end. */
fun interface DreamSynthesizer {
    suspend fun synthesize(request: DreamSynthesizeRequest): DreamSynthesizeResult
}
