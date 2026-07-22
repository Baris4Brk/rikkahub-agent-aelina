package me.rerere.rikkahub.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
enum class MemoryCandidateAction {
    @SerialName("create")
    CREATE,

    @SerialName("update")
    UPDATE,

    @SerialName("merge")
    MERGE,

    @SerialName("ignore")
    IGNORE,
}

@Serializable
enum class MemoryKind {
    @SerialName("user_profile")
    USER_PROFILE,

    @SerialName("preference")
    PREFERENCE,

    @SerialName("long_term_goal")
    LONG_TERM_GOAL,

    @SerialName("project_fact")
    PROJECT_FACT,

    @SerialName("working_constraint")
    WORKING_CONSTRAINT,

    @SerialName("relationship")
    RELATIONSHIP,

    @SerialName("episode")
    EPISODE,

    @SerialName("decision")
    DECISION,

    @SerialName("insight")
    INSIGHT,

    @SerialName("theory")
    THEORY,

    @SerialName("other")
    OTHER,
}

@Serializable
enum class MemoryAttribution {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("shared") SHARED,
    @SerialName("external") EXTERNAL,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class MemoryTruthStatus {
    @SerialName("confirmed") CONFIRMED,
    @SerialName("provisional") PROVISIONAL,
    @SerialName("disputed") DISPUTED,
    @SerialName("superseded") SUPERSEDED,
}

@Serializable
enum class MemoryRelationType {
    FOLLOWS, UPDATES, CORRECTS, SUPERSEDES, SUPPORTS, CONTRADICTS, DERIVED_FROM, RELATED_TO,
}

@Serializable
data class MemoryExtractionEnvelope(
    val version: Int,
    val proposals: List<MemoryProposal>,
    val relations: List<MemoryRelationProposal> = emptyList(),
)

@Serializable
data class MemoryProposal(
    val proposalKey: String? = null,
    val action: MemoryCandidateAction,
    val targetIds: List<Int> = emptyList(),
    val expectedRevisions: List<Int> = emptyList(),
    val title: String = "",
    val content: String = "",
    val kind: MemoryKind = MemoryKind.OTHER,
    val attribution: MemoryAttribution = MemoryAttribution.UNKNOWN,
    val truthStatus: MemoryTruthStatus = MemoryTruthStatus.CONFIRMED,
    val occurredAtMs: Long? = null,
    val participants: List<String> = emptyList(),
    val outcome: String? = null,
    val tags: List<String> = emptyList(),
    val importance: Float = 0.5f,
    val confidence: Float = 0f,
    val expiresAtMs: Long? = null,
    val evidenceMessageIds: List<String> = emptyList(),
    val reason: String = "",
)

@Serializable
data class MemoryRelationProposal(
    val sourceProposalKey: String? = null,
    val sourceMemoryId: Int? = null,
    val targetProposalKey: String? = null,
    val targetMemoryId: Int? = null,
    val type: MemoryRelationType,
    val weight: Float = 0.5f,
    val description: String = "",
    val evidenceMessageIds: List<String> = emptyList(),
)

sealed interface MemoryExtractionParseResult {
    data class Success(val envelope: MemoryExtractionEnvelope) : MemoryExtractionParseResult
    data class Failure(val message: String) : MemoryExtractionParseResult
}

class MemoryExtractionParser(
    private val json: Json = Json {
        // Providers occasionally add harmless explanatory metadata even when the prompt asks for
        // an exact schema. Keep the versioned core contract strict while allowing those fields to
        // be discarded; malformed JSON, wrong types and unknown enum values still fail closed.
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    },
) {
    fun parse(raw: String): MemoryExtractionParseResult {
        val payload = raw.extractJsonObject()
            ?: return MemoryExtractionParseResult.Failure("memory_extraction_json_missing")
        return runCatching {
            json.decodeFromString<MemoryExtractionEnvelope>(payload)
        }.fold(
            onSuccess = { envelope ->
                if (envelope.version !in SUPPORTED_MEMORY_EXTRACTION_VERSIONS) {
                    MemoryExtractionParseResult.Failure("memory_extraction_version_unsupported")
                } else {
                    MemoryExtractionParseResult.Success(envelope)
                }
            },
            onFailure = {
                MemoryExtractionParseResult.Failure("memory_extraction_json_invalid")
            },
        )
    }
}

private val SUPPORTED_MEMORY_EXTRACTION_VERSIONS = setOf(1, 2)

private fun String.extractJsonObject(): String? {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    if (start < 0 || end < start) return null
    return substring(start, end + 1)
}
