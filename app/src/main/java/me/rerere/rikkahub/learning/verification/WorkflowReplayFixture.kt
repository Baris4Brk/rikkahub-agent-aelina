package me.rerere.rikkahub.learning.verification

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val WORKFLOW_REPLAY_FIXTURE_VERSION: String = "workflow-replay-fixture-v1"

/**
 * A versioned, host-authored replay case. Fixtures contain only bounded, redacted values and an
 * explicit oracle. They are never generated or amended by model output.
 */
data class WorkflowReplayFixture(
    val fixtureVersion: String = WORKFLOW_REPLAY_FIXTURE_VERSION,
    val fixtureId: String,
    val subjectArtifactSha256: String,
    val inputRevision: String,
    val slotBindings: Map<String, JsonElement> = emptyMap(),
    val expectedActions: List<WorkflowReplayExpectedAction>,
    val expectedTerminal: WorkflowReplayTerminal,
    /** Deterministic cancellation injection; the action at this index is never invoked. */
    val cancelBeforeActionIndex: Int? = null,
) {
    init {
        require(fixtureVersion == WORKFLOW_REPLAY_FIXTURE_VERSION)
        require(fixtureId.matches(SAFE_ID))
        require(subjectArtifactSha256.isVerifierSha256())
        require(inputRevision.matches(SAFE_VERSION))
        require(slotBindings.size <= MAX_FIXTURE_SLOTS)
        require(slotBindings.keys.all(SLOT_NAME::matches))
        require(slotBindings.values.all(::isRedactedFixtureInput)) {
            "Replay fixture input is not bounded/redacted"
        }
        require(expectedActions.size <= MAX_FIXTURE_ACTIONS)
        require(expectedActions.map { it.actionIndex } == expectedActions.indices.toList())
        cancelBeforeActionIndex?.let { require(it in 0..MAX_FIXTURE_ACTIONS) }
        if (cancelBeforeActionIndex != null) {
            require(expectedTerminal == WorkflowReplayTerminal.CANCELLED)
            require(expectedActions.size == cancelBeforeActionIndex)
        }
    }

    override fun toString(): String =
        "WorkflowReplayFixture(version=$fixtureVersion, actions=${expectedActions.size}, " +
            "terminal=$expectedTerminal, values=<redacted>)"
}

data class WorkflowReplayExpectedAction(
    val actionIndex: Int,
    val toolName: String,
    val schemaFingerprint: String,
    val resolvedArgs: JsonObject,
    val expectedResult: WorkflowReplayExpectedResult,
) {
    init {
        require(actionIndex >= 0)
        require(toolName.matches(SAFE_TOOL_NAME))
        require(schemaFingerprint.isVerifierSha256())
        require(canonicalVerifierJson(resolvedArgs).toByteArray(Charsets.UTF_8).size <=
            MAX_FIXTURE_ARGS_UTF8_BYTES)
    }

    override fun toString(): String =
        "WorkflowReplayExpectedAction(index=$actionIndex, tool=$toolName, " +
            "result=${expectedResult.kind}, values=<redacted>)"
}

enum class WorkflowReplayResultKind {
    SUCCESS,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    OUTPUT_LIMIT,
}

data class WorkflowReplayExpectedResult(
    val kind: WorkflowReplayResultKind,
    /** Expected fake output is host-authored data. It never controls subsequent actions. */
    val output: JsonElement? = null,
    val errorCode: String? = null,
) {
    init {
        when (kind) {
            WorkflowReplayResultKind.SUCCESS,
            WorkflowReplayResultKind.OUTPUT_LIMIT,
            -> require(output != null && errorCode == null)

            WorkflowReplayResultKind.FAILED ->
                require(output == null && errorCode?.matches(SAFE_CODE) == true)

            WorkflowReplayResultKind.TIMED_OUT,
            WorkflowReplayResultKind.CANCELLED,
            -> require(output == null && errorCode == null)
        }
        output?.let {
            require(canonicalVerifierJson(it).toByteArray(Charsets.UTF_8).size <=
                MAX_FIXTURE_EXPECTED_OUTPUT_UTF8_BYTES)
        }
    }
}

enum class WorkflowReplayTerminal {
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    OUTPUT_LIMIT,
}

internal fun workflowReplayFixtureSetSha256(fixtures: List<WorkflowReplayFixture>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateVerifierField("fixture-set-domain", "workflow-replay-fixture-set-v1")
    fixtures.sortedBy(WorkflowReplayFixture::fixtureId).forEach { fixture ->
        digest.updateVerifierField("fixture-version", fixture.fixtureVersion)
        digest.updateVerifierField("fixture-id", fixture.fixtureId)
        digest.updateVerifierField("subject", fixture.subjectArtifactSha256)
        digest.updateVerifierField("input-revision", fixture.inputRevision)
        fixture.slotBindings.toSortedMap().forEach { (name, value) ->
            digest.updateVerifierField("slot-name", name)
            digest.updateVerifierField("slot-value", canonicalVerifierJson(value))
        }
        fixture.expectedActions.forEach { action ->
            digest.updateVerifierField("action-index", action.actionIndex.toString())
            digest.updateVerifierField("action-tool", action.toolName)
            digest.updateVerifierField("action-schema", action.schemaFingerprint)
            digest.updateVerifierField("action-args", canonicalVerifierJson(action.resolvedArgs))
            digest.updateVerifierField("action-result", action.expectedResult.kind.name)
            digest.updateVerifierField(
                "action-output",
                action.expectedResult.output?.let(::canonicalVerifierJson) ?: "absent",
            )
            digest.updateVerifierField(
                "action-error",
                action.expectedResult.errorCode ?: "absent",
            )
        }
        digest.updateVerifierField("terminal", fixture.expectedTerminal.name)
        digest.updateVerifierField(
            "cancel-before",
            fixture.cancelBeforeActionIndex?.toString() ?: "absent",
        )
    }
    return digest.digest().toVerifierHex()
}

internal fun canonicalVerifierJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, nested) ->
            "${JsonPrimitive(key)}:${canonicalVerifierJson(nested)}"
        }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
        canonicalVerifierJson(it)
    }
    is JsonPrimitive -> value.toString()
    JsonNull -> "null"
}

internal fun sha256VerifierText(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .toVerifierHex()

internal fun String.isVerifierSha256(): Boolean = matches(SHA256)

private fun isRedactedFixtureInput(value: JsonElement): Boolean {
    var nodes = 0
    fun visit(element: JsonElement, depth: Int): Boolean {
        nodes += 1
        if (nodes > MAX_FIXTURE_INPUT_NODES || depth > MAX_FIXTURE_INPUT_DEPTH) return false
        return when (element) {
            is JsonObject -> element.size <= MAX_FIXTURE_OBJECT_KEYS &&
                element.keys.all(SAFE_INPUT_KEY::matches) &&
                element.values.all { visit(it, depth + 1) }
            is JsonArray -> element.size <= MAX_FIXTURE_ARRAY_ITEMS &&
                element.all { visit(it, depth + 1) }
            is JsonPrimitive -> if (!element.isString) {
                true
            } else {
                val text = element.content
                text.length <= MAX_FIXTURE_STRING_CHARS &&
                    text.none { it.code < 0x20 || it == '\u007f' } &&
                    REDACTED_SECRET_MARKERS.none { it.containsMatchIn(text) }
            }
            JsonNull -> true
        }
    }
    return visit(value, 0) && canonicalVerifierJson(value).toByteArray(Charsets.UTF_8).size <=
        MAX_FIXTURE_INPUT_UTF8_BYTES
}

private fun MessageDigest.updateVerifierField(name: String, value: String) {
    listOf(name, value).forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        update(bytes)
    }
}

private fun ByteArray.toVerifierHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
private val SAFE_VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
private val SAFE_TOOL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
private val SAFE_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
private val SLOT_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
private val SAFE_INPUT_KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
private val REDACTED_SECRET_MARKERS = listOf(
    Regex("(?i)bearer\\s+[a-z0-9._~+/-]{6,}"),
    Regex("(?i)(api[_-]?key|password|credential|secret|token)\\s*[:=]\\s*[^\\s]{3,}"),
    Regex("-----BEGIN [A-Z ]+PRIVATE KEY-----"),
)
private const val MAX_FIXTURE_SLOTS = 32
private const val MAX_FIXTURE_ACTIONS = 8
private const val MAX_FIXTURE_INPUT_NODES = 256
private const val MAX_FIXTURE_INPUT_DEPTH = 8
private const val MAX_FIXTURE_OBJECT_KEYS = 64
private const val MAX_FIXTURE_ARRAY_ITEMS = 64
private const val MAX_FIXTURE_STRING_CHARS = 2_048
private const val MAX_FIXTURE_INPUT_UTF8_BYTES = 16 * 1_024
private const val MAX_FIXTURE_ARGS_UTF8_BYTES = 8 * 1_024
private const val MAX_FIXTURE_EXPECTED_OUTPUT_UTF8_BYTES = 64 * 1_024

