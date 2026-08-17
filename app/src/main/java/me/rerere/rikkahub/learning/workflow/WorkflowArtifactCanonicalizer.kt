package me.rerere.rikkahub.learning.workflow

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot

object WorkflowArtifactCanonicalizer {
    private val strictJson = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun canonicalTemplate(raw: JsonObject): String = canonicalJson(raw).toString()

    fun canonicalSlots(slots: List<LearnedWorkflowTypedSlot>): String = strictJson.encodeToString(
        slots.sortedBy { it.name },
    )

    fun canonicalCapabilities(capabilities: Collection<String>): String = JsonArray(
        capabilities.distinct().sorted().map(::JsonPrimitive),
    ).toString()

    fun canonicalToolSchemas(
        schemas: List<LearnedWorkflowToolSchemaFingerprint>,
    ): String = strictJson.encodeToString(schemas.sortedBy { it.actionIndex })

    fun candidateId(
        sourcePolicyId: String,
        sourcePolicyRevision: Long,
        consumingAssistantId: String,
    ): String = "workflow-candidate-v1:" + LearningCanonicalId.digest(
        domainVersion = "learned-workflow-candidate-id-v1",
        fields = listOf(sourcePolicyId, sourcePolicyRevision.toString(), consumingAssistantId),
    )

    fun artifactSha256(
        canonicalTemplateJson: String,
        canonicalTypedSlots: String,
        canonicalCapabilities: String,
        canonicalToolSchemas: String,
        assistantId: String,
        authoritySubjectId: String?,
        sourcePolicyId: String,
        sourcePolicyRevision: Long,
        sourcePolicyArtifactSha256: String,
        sourceGrantDigest: String,
        compilerVersion: String,
        templateVersion: String,
    ): String = LearningCanonicalId.digest(
        domainVersion = "learned-workflow-artifact-v1",
        fields = listOf(
            canonicalTemplateJson,
            canonicalTypedSlots,
            canonicalCapabilities,
            canonicalToolSchemas,
            assistantId,
            authoritySubjectId.orEmpty(),
            sourcePolicyId,
            sourcePolicyRevision.toString(),
            sourcePolicyArtifactSha256,
            sourceGrantDigest,
            compilerVersion,
            templateVersion,
        ),
    )

    fun grantDigest(
        grantId: String,
        sourceStreamId: String,
        stateVersion: Long,
        policyRevision: Long,
        artifactSha256: String,
    ): String = LearningCanonicalId.digest(
        domainVersion = "learned-workflow-grant-receipt-v1",
        fields = listOf(
            grantId,
            sourceStreamId,
            stateVersion.toString(),
            policyRevision.toString(),
            artifactSha256,
        ),
    )

    private fun canonicalJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { entry ->
            entry.key to canonicalJson(entry.value)
        })
        is JsonArray -> JsonArray(value.map(::canonicalJson))
        is JsonPrimitive,
        JsonNull,
        -> value
    }

    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
