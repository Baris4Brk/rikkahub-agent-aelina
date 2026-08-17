package me.rerere.rikkahub.learning.verification

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class FakeWorkflowToolRisk {
    LOW,
    MEDIUM,
}

enum class FakeWorkflowToolOrigin {
    TRUSTED_WORKFLOW,
}

/** A declarative fake response. No callback capable of network/file/Android access is retained. */
sealed interface FakeWorkflowToolOutcome {
    val simulatedDurationMs: Long

    data class Success(
        val output: JsonElement,
        override val simulatedDurationMs: Long = 0L,
    ) : FakeWorkflowToolOutcome {
        init {
            require(simulatedDurationMs >= 0L)
            require(canonicalVerifierJson(output).toByteArray(Charsets.UTF_8).size <=
                MAX_FAKE_OUTPUT_UTF8_BYTES)
        }
    }

    data class Failure(
        val errorCode: String,
        override val simulatedDurationMs: Long = 0L,
    ) : FakeWorkflowToolOutcome {
        init {
            require(errorCode.matches(SAFE_FAKE_CODE))
            require(simulatedDurationMs >= 0L)
        }
    }

    data class Cancelled(
        override val simulatedDurationMs: Long = 0L,
    ) : FakeWorkflowToolOutcome {
        init {
            require(simulatedDurationMs >= 0L)
        }
    }
}

data class FakeWorkflowToolCase(
    val actionIndex: Int,
    val expectedArgs: JsonObject,
    val outcome: FakeWorkflowToolOutcome,
) {
    init {
        require(actionIndex >= 0)
        require(canonicalVerifierJson(expectedArgs).toByteArray(Charsets.UTF_8).size <=
            MAX_FAKE_ARGS_UTF8_BYTES)
    }
}

/**
 * Explicit host fake adapter. Matching is action-index plus canonical arguments, so model text in
 * an earlier output can never select a later tool or mutate the replay program.
 */
data class FakeWorkflowToolAdapter(
    val adapterVersion: String,
    val cases: List<FakeWorkflowToolCase>,
) {
    init {
        require(adapterVersion.matches(SAFE_FAKE_VERSION))
        require(cases.isNotEmpty() && cases.size <= MAX_FAKE_CASES)
        require(cases.map { it.actionIndex }.distinct().size == cases.size)
    }

    internal fun replay(actionIndex: Int, args: JsonObject): FakeWorkflowToolOutcome? = cases
        .singleOrNull { fakeCase ->
            fakeCase.actionIndex == actionIndex &&
                canonicalVerifierJson(fakeCase.expectedArgs) == canonicalVerifierJson(args)
        }
        ?.outcome
}

data class FakeWorkflowToolRegistration(
    val toolName: String,
    val schemaFingerprint: String,
    val catalogued: Boolean,
    val allowedOrigins: Set<FakeWorkflowToolOrigin>,
    val risk: FakeWorkflowToolRisk,
    val adapter: FakeWorkflowToolAdapter,
) {
    init {
        require(toolName.matches(SAFE_FAKE_TOOL_NAME))
        require(schemaFingerprint.isVerifierSha256())
        require(catalogued) { "Fake Workflow tools must be host-catalogued" }
        require(FakeWorkflowToolOrigin.TRUSTED_WORKFLOW in allowedOrigins) {
            "Fake Workflow tool is not allowed for TrustedWorkflow"
        }
        require(allowedOrigins == setOf(FakeWorkflowToolOrigin.TRUSTED_WORKFLOW))
        // The closed enum intentionally has no High/Critical member.
        require(risk == FakeWorkflowToolRisk.LOW || risk == FakeWorkflowToolRisk.MEDIUM)
    }
}

/** Pure immutable fake registry. It contains no production Tool or executable callback. */
class FakeWorkflowToolRegistry private constructor(
    registrations: List<FakeWorkflowToolRegistration>,
) {
    private val byName = registrations.associateBy(FakeWorkflowToolRegistration::toolName)

    init {
        require(registrations.isNotEmpty() && registrations.size <= MAX_FAKE_TOOLS)
        require(byName.size == registrations.size) { "Duplicate fake Workflow tool" }
    }

    internal fun registration(toolName: String): FakeWorkflowToolRegistration? = byName[toolName]

    fun hasExplicitAdapter(toolName: String, schemaFingerprint: String): Boolean =
        byName[toolName]?.let { it.schemaFingerprint == schemaFingerprint } == true

    override fun toString(): String = "FakeWorkflowToolRegistry(tools=${byName.size})"

    companion object {
        fun of(vararg registrations: FakeWorkflowToolRegistration): FakeWorkflowToolRegistry =
            FakeWorkflowToolRegistry(registrations.toList())
    }
}

private val SAFE_FAKE_TOOL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
private val SAFE_FAKE_VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
private val SAFE_FAKE_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
private const val MAX_FAKE_TOOLS = 64
private const val MAX_FAKE_CASES = 32
private const val MAX_FAKE_ARGS_UTF8_BYTES = 8 * 1_024
private const val MAX_FAKE_OUTPUT_UTF8_BYTES = 64 * 1_024
