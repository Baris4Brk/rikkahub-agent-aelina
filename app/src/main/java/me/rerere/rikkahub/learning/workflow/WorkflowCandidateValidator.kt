package me.rerere.rikkahub.learning.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.RiskLevel
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowSlotType
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot
import me.rerere.rikkahub.toolcatalog.ToolCatalogEntry
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import me.rerere.rikkahub.workflow.model.WorkflowInputSchemaValidator

fun interface LearnedWorkflowAuthorityResolver {
    fun existsExact(assistantId: String, authoritySubjectId: String?): Boolean
}

fun interface LearnedWorkflowFakeAdapterRegistry {
    fun hasAdapter(toolName: String, schemaFingerprint: String): Boolean
}

data class LearnedWorkflowValidationContext(
    val requestAssistantId: String,
    val requestAuthoritySubjectId: String?,
    val exactGrant: PolicyGrantAuthoritySnapshot,
    val authorityResolver: LearnedWorkflowAuthorityResolver,
    val fakeAdapters: LearnedWorkflowFakeAdapterRegistry,
    val catalog: ToolCatalogSnapshot,
)

/** P4-003 deterministic local validator. It never invokes a Tool or production runtime. */
class WorkflowCandidateValidator {
    fun validate(
        candidate: LearnedWorkflowCandidate,
        context: LearnedWorkflowValidationContext,
    ): WorkflowCandidateValidationResult {
        if (candidate.state !in VALIDATABLE_STATES) return invalid(
            WorkflowCandidateValidationCode.CANDIDATE_STATE_UNSUPPORTED,
        )
        if (candidate.assistantId != context.requestAssistantId) {
            return invalid(WorkflowCandidateValidationCode.ASSISTANT_MISMATCH)
        }
        if (candidate.authoritySubjectId != context.requestAuthoritySubjectId) {
            return invalid(WorkflowCandidateValidationCode.AUTHORITY_SUBJECT_MISMATCH)
        }
        if (!context.authorityResolver.existsExact(
                candidate.assistantId,
                candidate.authoritySubjectId,
            )
        ) {
            return invalid(WorkflowCandidateValidationCode.ASSISTANT_MISSING)
        }
        if (!candidate.matchesExactGrant(context.exactGrant)) {
            return invalid(WorkflowCandidateValidationCode.GRANT_NOT_EXACT)
        }
        if (candidate.maxOutputUtf8Bytes !in 1..LearnedWorkflowCandidate.MAX_OUTPUT_UTF8_BYTES) {
            return invalid(WorkflowCandidateValidationCode.OUTPUT_BOUND_OUT_OF_BOUNDS)
        }
        val template = runCatching {
            Json.parseToJsonElement(candidate.canonicalTemplateJson) as? JsonObject
        }.getOrNull() ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        if (WorkflowArtifactCanonicalizer.canonicalTemplate(template) !=
            candidate.canonicalTemplateJson
        ) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        }
        if ((template.keys - ROOT_KEYS).isNotEmpty()) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_UNKNOWN_KEY)
        }
        if ((ROOT_REQUIRED_KEYS - template.keys).isNotEmpty()) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        }
        if ((template["id"] as? JsonPrimitive)?.contentOrNull != candidate.id ||
            (template["source_candidate_id"] as? JsonPrimitive)?.contentOrNull != candidate.id ||
            (template["origin"] as? JsonPrimitive)?.contentOrNull != "LEARNED"
        ) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_IDENTITY_MISMATCH)
        }
        if ((template["enabled"] as? JsonPrimitive)?.booleanOrNull != false) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_ENABLED)
        }
        val trigger = template["trigger"] as? JsonObject
            ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        if (trigger.keys != setOf("type") ||
            (trigger["type"] as? JsonPrimitive)?.contentOrNull != "manual"
        ) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_NOT_MANUAL)
        }
        val conditions = template["conditions"] as? JsonArray
            ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        if (conditions.isNotEmpty()) {
            return invalid(WorkflowCandidateValidationCode.TEMPLATE_NOT_MANUAL)
        }
        if ((template["cooldown_seconds"] as? JsonPrimitive)?.longOrNull != 0L ||
            (template["max_runs_per_day"] as? JsonPrimitive)?.longOrNull != 1L
        ) {
            return invalid(WorkflowCandidateValidationCode.EXECUTION_BOUND_OUT_OF_BOUNDS)
        }
        if ((template["authoring_assistant_id"] as? JsonPrimitive)?.contentOrNull !=
            candidate.assistantId
        ) {
            return invalid(WorkflowCandidateValidationCode.ASSISTANT_MISMATCH)
        }
        val templateAuthoritySubject = when (val value = template["authority_subject_id"]) {
            JsonNull -> null
            is JsonPrimitive -> value.contentOrNull
            else -> return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        }
        if (templateAuthoritySubject != candidate.authoritySubjectId) {
            return invalid(WorkflowCandidateValidationCode.AUTHORITY_SUBJECT_MISMATCH)
        }
        val actions = template["actions"] as? JsonArray
            ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        if (actions.size !in 1..LearnedWorkflowCandidate.MAX_ACTIONS ||
            actions.size != candidate.toolSchemaFingerprints.size
        ) {
            return invalid(WorkflowCandidateValidationCode.ACTION_COUNT_OUT_OF_BOUNDS)
        }

        val slots = candidate.typedSlots.associateBy(LearnedWorkflowTypedSlot::name)
        val usedSlots = mutableSetOf<String>()
        val actualCapabilities = sortedSetOf<String>()
        actions.forEachIndexed { index, actionElement ->
            val action = actionElement as? JsonObject ?: return invalid(
                WorkflowCandidateValidationCode.TEMPLATE_MALFORMED,
                index,
            )
            if ((action.keys - ACTION_KEYS).isNotEmpty()) return invalid(
                WorkflowCandidateValidationCode.TEMPLATE_UNKNOWN_KEY,
                index,
            )
            val toolName = (action["tool"] as? JsonPrimitive)?.contentOrNull
                ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED, index)
            if (toolName.isForbiddenLearnedWorkflowTool()) {
                return invalid(WorkflowCandidateValidationCode.TOOL_FORBIDDEN, index)
            }
            val timeout = (action["timeout_seconds"] as? JsonPrimitive)?.intOrNull
                ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED, index)
            if (timeout !in MIN_ACTION_TIMEOUT_SECONDS..MAX_ACTION_TIMEOUT_SECONDS) {
                return invalid(WorkflowCandidateValidationCode.TIMEOUT_OUT_OF_BOUNDS, index)
            }
            val args = action["args"] as? JsonObject ?: return invalid(
                WorkflowCandidateValidationCode.INPUT_SCHEMA_INVALID,
                index,
            )
            if (args.toString().toByteArray(Charsets.UTF_8).size > MAX_ACTION_ARGS_BYTES ||
                jsonNodeCount(args) > MAX_JSON_NODES || jsonDepth(args) > MAX_JSON_DEPTH
            ) {
                return invalid(WorkflowCandidateValidationCode.ARGUMENT_BOUNDS_EXCEEDED, index)
            }
            val entry = context.catalog.entry(toolName) ?: return invalid(
                WorkflowCandidateValidationCode.TOOL_NOT_CATALOGUED,
                index,
            )
            validateToolMetadata(entry, context, index)?.let { return it }
            val frozen = candidate.toolSchemaFingerprints[index]
            val templateFingerprint = (action["tool_schema_fingerprint"] as? JsonPrimitive)
                ?.contentOrNull
            if (frozen.actionIndex != index || frozen.toolName != toolName ||
                frozen.schemaFingerprint != templateFingerprint ||
                frozen.schemaFingerprint != entry.schemaFingerprint
            ) {
                return invalid(WorkflowCandidateValidationCode.TOOL_SCHEMA_MISMATCH, index)
            }
            validateNoSensitiveLiteral(args, slots, index)?.let { return it }
            val resolvedArgs = resolveSlots(args, slots, usedSlots, index).let { resolution ->
                when (resolution) {
                    is SlotResolution.Invalid -> return resolution.failure
                    is SlotResolution.Ready -> resolution.args
                }
            }
            val schema = runCatching { entry.definition.parameters() }.getOrNull()
                ?: return invalid(WorkflowCandidateValidationCode.INPUT_SCHEMA_MISSING, index)
            validateReferencedSlotTypes(args, slots, schema, index)?.let { return it }
            validateInputSchema(resolvedArgs, schema, index)?.let { return it }
            ToolCapabilityResolver.resolve(toolName, resolvedArgs).capabilities
                .mapTo(actualCapabilities) { it.value }
        }
        if (usedSlots != slots.keys) {
            return invalid(WorkflowCandidateValidationCode.SLOT_UNUSED)
        }
        if (actualCapabilities.toList() != candidate.capabilitySnapshot) {
            return invalid(WorkflowCandidateValidationCode.CAPABILITY_SNAPSHOT_MISMATCH)
        }
        val templateCapabilities = (template["capability_snapshot"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: return invalid(WorkflowCandidateValidationCode.TEMPLATE_MALFORMED)
        if (templateCapabilities != candidate.capabilitySnapshot) {
            return invalid(WorkflowCandidateValidationCode.CAPABILITY_SNAPSHOT_MISMATCH)
        }
        val canonicalHash = WorkflowArtifactCanonicalizer.artifactSha256(
            candidate.canonicalTemplateJson,
            WorkflowArtifactCanonicalizer.canonicalSlots(candidate.typedSlots),
            WorkflowArtifactCanonicalizer.canonicalCapabilities(candidate.capabilitySnapshot),
            WorkflowArtifactCanonicalizer.canonicalToolSchemas(candidate.toolSchemaFingerprints),
            candidate.assistantId,
            candidate.authoritySubjectId,
            candidate.sourcePolicyId,
            candidate.sourcePolicyRevision,
            candidate.sourcePolicyArtifactSha256,
            candidate.sourceGrantDigest,
            candidate.compilerVersion,
            candidate.templateVersion,
        )
        if (canonicalHash != candidate.artifactSha256) {
            return invalid(WorkflowCandidateValidationCode.ARTIFACT_HASH_MISMATCH)
        }
        return WorkflowCandidateValidationResult.VALID
    }

    private fun validateToolMetadata(
        entry: ToolCatalogEntry,
        context: LearnedWorkflowValidationContext,
        index: Int,
    ): WorkflowCandidateValidationResult? {
        val descriptor = CapabilityCatalog.byToolName(entry.toolName)
            ?: return invalid(WorkflowCandidateValidationCode.TOOL_NOT_CATALOGUED, index)
        if (entry.externalUntrusted || !entry.currentlyInjectable) {
            return invalid(WorkflowCandidateValidationCode.TOOL_EXTERNAL_UNTRUSTED, index)
        }
        if (ToolCallOrigin.TrustedWorkflow !in descriptor.allowedOrigins ||
            ToolCallOrigin.TrustedWorkflow !in entry.allowedOrigins
        ) {
            return invalid(WorkflowCandidateValidationCode.TOOL_ORIGIN_NOT_TRUSTED_WORKFLOW, index)
        }
        if (descriptor.riskLevel !in setOf(RiskLevel.Low, RiskLevel.Medium) ||
            entry.risk !in setOf(RiskLevel.Low, RiskLevel.Medium)
        ) {
            return invalid(WorkflowCandidateValidationCode.TOOL_RISK_TOO_HIGH, index)
        }
        if (!context.fakeAdapters.hasAdapter(entry.toolName, entry.schemaFingerprint)) {
            return invalid(WorkflowCandidateValidationCode.FAKE_ADAPTER_MISSING, index)
        }
        return null
    }
}

private sealed interface SlotResolution {
    data class Ready(val args: JsonObject) : SlotResolution
    data class Invalid(val failure: WorkflowCandidateValidationResult) : SlotResolution
}

private fun resolveSlots(
    args: JsonObject,
    slots: Map<String, LearnedWorkflowTypedSlot>,
    usedSlots: MutableSet<String>,
    actionIndex: Int,
): SlotResolution {
    var failure: WorkflowCandidateValidationResult? = null
    fun resolve(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { resolve(it.value) })
        is JsonArray -> JsonArray(value.map(::resolve))
        is JsonPrimitive -> {
            val match = value.contentOrNull?.let(SLOT_REFERENCE::matchEntire)
            if (match == null) {
                if (value.contentOrNull?.contains("{{slot:") == true) {
                    failure = invalid(WorkflowCandidateValidationCode.SLOT_UNKNOWN, actionIndex)
                }
                value
            } else {
                val slot = slots[match.groupValues[1]]
                if (slot == null) {
                    failure = invalid(WorkflowCandidateValidationCode.SLOT_UNKNOWN, actionIndex)
                    value
                } else if (!slot.isBound) {
                    failure = invalid(WorkflowCandidateValidationCode.SLOT_UNBOUND, actionIndex)
                    value
                } else {
                    usedSlots += slot.name
                    slot.value ?: JsonPrimitive(checkNotNull(slot.secretRef))
                }
            }
        }
        else -> value
    }
    val resolved = resolve(args) as JsonObject
    return failure?.let(SlotResolution::Invalid) ?: SlotResolution.Ready(resolved)
}

private fun validateNoSensitiveLiteral(
    args: JsonObject,
    slots: Map<String, LearnedWorkflowTypedSlot>,
    actionIndex: Int,
): WorkflowCandidateValidationResult? {
    fun walk(value: JsonElement): WorkflowCandidateValidationResult? = when (value) {
        is JsonObject -> value.entries.firstNotNullOfOrNull { (key, nested) ->
            if (SECRET_KEYWORDS.any(key.lowercase()::contains)) {
                val match = (nested as? JsonPrimitive)?.contentOrNull?.let(SLOT_REFERENCE::matchEntire)
                val slot = match?.groupValues?.get(1)?.let(slots::get)
                if (slot?.type != LearnedWorkflowSlotType.SECRET_REF) {
                    invalid(WorkflowCandidateValidationCode.SECRET_LITERAL, actionIndex)
                } else null
            } else walk(nested)
        }
        is JsonArray -> value.firstNotNullOfOrNull(::walk)
        is JsonPrimitive -> if (!value.isString) null else {
            val text = value.content
            val lower = text.lowercase()
            when {
                SLOT_REFERENCE.matches(text) -> null
                text.any { it.isISOControl() } ->
                    invalid(WorkflowCandidateValidationCode.ARGUMENT_BOUNDS_EXCEEDED, actionIndex)
                SECRET_LITERAL_PATTERNS.any { it.containsMatchIn(text) } ->
                    invalid(WorkflowCandidateValidationCode.SECRET_LITERAL, actionIndex)
                INJECTION_MARKERS.any(lower::contains) ->
                    invalid(WorkflowCandidateValidationCode.PROMPT_INJECTION, actionIndex)
                RAW_PRIVATE_MATERIAL.any { it.containsMatchIn(text) } ->
                    invalid(WorkflowCandidateValidationCode.PROMPT_INJECTION, actionIndex)
                URL_PATTERN.containsMatchIn(text) ->
                    invalid(WorkflowCandidateValidationCode.URL_NOT_ALLOWED, actionIndex)
                PATH_PATTERN.containsMatchIn(text) ->
                    invalid(WorkflowCandidateValidationCode.PATH_NOT_ALLOWED, actionIndex)
                text.length > MAX_STRING_CHARS ->
                    invalid(WorkflowCandidateValidationCode.ARGUMENT_BOUNDS_EXCEEDED, actionIndex)
                else -> null
            }
        }
        else -> null
    }
    return walk(args)
}

private fun validateInputSchema(
    args: JsonObject,
    schema: InputSchema,
    actionIndex: Int,
): WorkflowCandidateValidationResult? {
    val error = WorkflowInputSchemaValidator.validate(args, schema) ?: return null
    return invalid(
        WorkflowCandidateValidationCode.INPUT_SCHEMA_INVALID,
        actionIndex,
        when {
            "unknown argument" in error.detail -> "UNKNOWN_ARGUMENT"
            "required argument" in error.detail -> "REQUIRED_ARGUMENT_MISSING"
            else -> "SCHEMA_CONSTRAINT_FAILED"
        },
    )
}

private fun validateReferencedSlotTypes(
    args: JsonObject,
    slots: Map<String, LearnedWorkflowTypedSlot>,
    schema: InputSchema,
    actionIndex: Int,
): WorkflowCandidateValidationResult? {
    val objectSchema = schema as? InputSchema.Obj ?: return invalid(
        WorkflowCandidateValidationCode.INPUT_SCHEMA_INVALID,
        actionIndex,
    )
    args.forEach { (name, value) ->
        val reference = (value as? JsonPrimitive)?.contentOrNull?.let(SLOT_REFERENCE::matchEntire)
            ?: return@forEach
        val slot = slots[reference.groupValues[1]] ?: return@forEach
        val property = objectSchema.properties[name] as? JsonObject ?: return invalid(
            WorkflowCandidateValidationCode.INPUT_SCHEMA_INVALID,
            actionIndex,
            "PROPERTY_SCHEMA_INVALID",
        )
        val allowedTypes = when (val type = property["type"]) {
            is JsonPrimitive -> setOfNotNull(type.contentOrNull)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            null -> emptySet()
            else -> return invalid(
                WorkflowCandidateValidationCode.INPUT_SCHEMA_INVALID,
                actionIndex,
                "PROPERTY_TYPE_INVALID",
            )
        }
        val slotWireType = when (slot.type) {
            LearnedWorkflowSlotType.STRING,
            LearnedWorkflowSlotType.ENUM,
            LearnedWorkflowSlotType.SECRET_REF,
            -> "string"
            LearnedWorkflowSlotType.INTEGER -> "integer"
            LearnedWorkflowSlotType.NUMBER -> "number"
            LearnedWorkflowSlotType.BOOLEAN -> "boolean"
        }
        if (allowedTypes.isNotEmpty() && slotWireType !in allowedTypes &&
            !(slotWireType == "integer" && "number" in allowedTypes)
        ) {
            return invalid(WorkflowCandidateValidationCode.SLOT_TYPE_MISMATCH, actionIndex)
        }
        if (slot.type == LearnedWorkflowSlotType.ENUM) {
            val schemaEnums = (property["enum"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.sorted()
            if (schemaEnums == null || schemaEnums != slot.enumValues) {
                return invalid(WorkflowCandidateValidationCode.SLOT_TYPE_MISMATCH, actionIndex)
            }
        }
    }
    return null
}

private fun LearnedWorkflowCandidate.matchesExactGrant(
    grant: PolicyGrantAuthoritySnapshot,
): Boolean {
    val expectedScope = authoritySubjectId?.let { LearningScope.AuthoritySubject(it) }
        ?: LearningScope.Assistant(kotlin.uuid.Uuid.parse(assistantId))
    val expectedDigest = WorkflowArtifactCanonicalizer.grantDigest(
        grant.grantId,
        grant.sourceStreamId,
        grant.stateVersion,
        grant.contentRevision,
        grant.artifactSha256,
    )
    return grant.state == PolicyGrantAuthorityState.GRANTED &&
        grant.scope == expectedScope &&
        grant.consumingAssistantId.toString() == assistantId &&
        grant.policyId == sourcePolicyId &&
        grant.contentRevision == sourcePolicyRevision &&
        grant.artifactSha256 == sourcePolicyArtifactSha256 &&
        expectedDigest == sourceGrantDigest
}

private fun jsonNodeCount(value: JsonElement): Int = when (value) {
    is JsonObject -> 1 + value.values.sumOf(::jsonNodeCount)
    is JsonArray -> 1 + value.sumOf(::jsonNodeCount)
    else -> 1
}

private fun jsonDepth(value: JsonElement): Int = when (value) {
    is JsonObject -> 1 + (value.values.maxOfOrNull(::jsonDepth) ?: 0)
    is JsonArray -> 1 + (value.maxOfOrNull(::jsonDepth) ?: 0)
    else -> 1
}

private fun invalid(
    code: WorkflowCandidateValidationCode,
    actionIndex: Int? = null,
    detailCode: String? = null,
) = WorkflowCandidateValidationResult(code, actionIndex, detailCode)

private val VALIDATABLE_STATES = setOf(
    LearnedWorkflowCandidateState.PROPOSED,
    LearnedWorkflowCandidateState.VALIDATING,
)
private val ROOT_KEYS = setOf(
    "id", "name", "description", "enabled", "trigger", "conditions", "actions",
    "cooldown_seconds", "max_runs_per_day", "created_at_ms", "updated_at_ms",
    "authoring_assistant_id", "capability_snapshot", "origin", "source_candidate_id",
    "authority_subject_id",
)
private val ROOT_REQUIRED_KEYS = ROOT_KEYS - "description"
private val ACTION_KEYS = setOf(
    "tool", "args", "timeout_seconds", "tool_schema_fingerprint",
)
private val SLOT_REFERENCE = Regex("^\\{\\{slot:([a-z][a-z0-9_]{0,63})}}$")
private val SECRET_KEYWORDS = setOf(
    "password", "secret", "token", "credential", "api_key", "authorization", "cookie",
)
private val SECRET_LITERAL_PATTERNS = listOf(
    Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}"),
    Regex(
        "(?i)(api[_-]?key|password|secret|token|authorization|proxy[_-]?authorization|" +
            "cookie|set[_-]?cookie)\\s*[:=]\\s*[^\\s]{4,}",
    ),
    Regex("-----BEGIN [A-Z ]+PRIVATE KEY-----"),
    Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
    Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
    Regex("\\bxox[baprs]-[A-Za-z0-9-]{12,}\\b"),
    Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
)
private val INJECTION_MARKERS = setOf(
    "ignore previous",
    "ignore all previous",
    "disregard previous",
    "disregard all previous",
    "system prompt",
    "developer prompt",
    "developer message",
    "hidden instructions",
    "<system",
    "</system",
    "<assistant",
    "{{payload",
)
private val RAW_PRIVATE_MATERIAL = listOf(
    Regex(
        "(?i)\\b(?:tool[_ -]?(?:args?|arguments?|output|result|call|call[_ -]?id)|" +
            "function[_ -]?(?:call|arguments?)|raw[_ -]?(?:input|output|prompt|response)|" +
            "chain[_ -]?of[_ -]?thought|private[_ -]?reasoning)\\b\\s*[:=]",
    ),
    Regex("(?s)^\\s*[\\[{].*[\\]}]\\s*$"),
)
private val URL_PATTERN = Regex("(?i)(https?|ftp|file|data)://")
private val PATH_PATTERN = Regex("(?i)(^|[\\s\"'])(/[a-z0-9._-]+/|[a-z]:\\\\|\\\\\\\\)")
private const val MIN_ACTION_TIMEOUT_SECONDS = 1
private const val MAX_ACTION_TIMEOUT_SECONDS = 120
private const val MAX_ACTION_ARGS_BYTES = 8 * 1_024
private const val MAX_JSON_NODES = 256
private const val MAX_JSON_DEPTH = 8
private const val MAX_STRING_CHARS = 2_048
