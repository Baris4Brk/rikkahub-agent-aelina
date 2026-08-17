package me.rerere.rikkahub.learning.verification

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowSlotType
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot

const val WORKFLOW_CANDIDATE_VERIFIER_VERSION: String = "workflow-candidate-fake-replay-v1"

/** Narrow immutable input; it contains no database, Tool, runtime, provider, or Android handle. */
data class WorkflowVerificationSubject(
    val artifactSha256: String,
    val canonicalTemplateJson: String,
    val typedSlots: List<LearnedWorkflowTypedSlot>,
    val toolSchemaFingerprints: List<LearnedWorkflowToolSchemaFingerprint>,
    val maxOutputUtf8Bytes: Int,
    val verifierVersion: String = WORKFLOW_CANDIDATE_VERIFIER_VERSION,
) {
    init {
        require(artifactSha256.isVerifierSha256())
        require(canonicalTemplateJson.toByteArray(Charsets.UTF_8).size in 2..32 * 1_024)
        require(typedSlots.size <= 32)
        require(toolSchemaFingerprints.size in 1..8)
        require(toolSchemaFingerprints.map { it.actionIndex } ==
            toolSchemaFingerprints.indices.toList())
        require(maxOutputUtf8Bytes in 1..64 * 1_024)
        require(verifierVersion == WORKFLOW_CANDIDATE_VERIFIER_VERSION)
    }

    companion object {
        fun from(candidate: LearnedWorkflowCandidate): WorkflowVerificationSubject =
            WorkflowVerificationSubject(
                artifactSha256 = candidate.artifactSha256,
                canonicalTemplateJson = candidate.canonicalTemplateJson,
                typedSlots = candidate.typedSlots,
                toolSchemaFingerprints = candidate.toolSchemaFingerprints,
                maxOutputUtf8Bytes = candidate.maxOutputUtf8Bytes,
                verifierVersion = candidate.verifierVersion,
            )
    }
}

/**
 * Pure fake/replay verifier. Only declarative fake cases can run. Model/provider output is treated
 * as an opaque result value and is never parsed into actions, slots, or control flow.
 */
class WorkflowCandidateVerifier {
    suspend fun verify(
        candidate: LearnedWorkflowCandidate,
        fixtures: List<WorkflowReplayFixture>,
        fakeTools: FakeWorkflowToolRegistry,
    ): WorkflowVerificationReport = verify(
        WorkflowVerificationSubject.from(candidate),
        fixtures,
        fakeTools,
    )

    suspend fun verify(
        subject: WorkflowVerificationSubject,
        fixtures: List<WorkflowReplayFixture>,
        fakeTools: FakeWorkflowToolRegistry,
    ): WorkflowVerificationReport {
        coroutineContext.ensureActive()
        val fixtureDigest = workflowReplayFixtureSetSha256(fixtures)
        if (fixtures.isEmpty()) return aggregate(
            subject,
            fixtureDigest,
            emptyList(),
            listOf(WorkflowVerificationFailureCode.FIXTURE_SET_EMPTY),
            WorkflowVerificationStatus.ABSTAIN,
        )
        if (fixtures.map(WorkflowReplayFixture::fixtureId).distinct().size != fixtures.size) {
            return aggregate(
                subject,
                fixtureDigest,
                emptyList(),
                listOf(WorkflowVerificationFailureCode.FIXTURE_DUPLICATE),
                WorkflowVerificationStatus.FAILED,
            )
        }
        val template = parseTemplate(subject) ?: return aggregate(
            subject,
            fixtureDigest,
            emptyList(),
            listOf(WorkflowVerificationFailureCode.TEMPLATE_MALFORMED),
            WorkflowVerificationStatus.FAILED,
        )
        val reports = fixtures.sortedBy(WorkflowReplayFixture::fixtureId).map { fixture ->
            coroutineContext.ensureActive()
            verifyFixture(subject, template, fixture, fakeTools)
        }
        val failures = reports.mapNotNull(WorkflowVerificationFixtureReport::failureCode)
            .distinct().sortedBy(Enum<*>::name)
        return aggregate(
            subject = subject,
            fixtureDigest = fixtureDigest,
            reports = reports,
            failures = failures,
            status = if (failures.isEmpty()) {
                WorkflowVerificationStatus.PASSED
            } else {
                WorkflowVerificationStatus.FAILED
            },
        )
    }

    private suspend fun verifyFixture(
        subject: WorkflowVerificationSubject,
        actions: List<ReplayAction>,
        fixture: WorkflowReplayFixture,
        fakeTools: FakeWorkflowToolRegistry,
    ): WorkflowVerificationFixtureReport {
        if (fixture.fixtureVersion != WORKFLOW_REPLAY_FIXTURE_VERSION) {
            return fixture.failed(WorkflowVerificationFailureCode.FIXTURE_VERSION_MISMATCH)
        }
        if (fixture.subjectArtifactSha256 != subject.artifactSha256) {
            return fixture.failed(WorkflowVerificationFailureCode.FIXTURE_SUBJECT_MISMATCH)
        }
        if (actions.size != subject.toolSchemaFingerprints.size) {
            return fixture.failed(WorkflowVerificationFailureCode.ACTION_COUNT_OUT_OF_BOUNDS)
        }
        val slots = subject.typedSlots.associateBy(LearnedWorkflowTypedSlot::name)
        val unknownFixtureSlot = fixture.slotBindings.keys.firstOrNull { it !in slots }
        if (unknownFixtureSlot != null) {
            return fixture.failed(WorkflowVerificationFailureCode.SLOT_UNKNOWN)
        }
        val observations = mutableListOf<WorkflowVerificationActionObservation>()
        var terminal: WorkflowReplayTerminal? = null
        var outputBytes = 0

        for (action in actions) {
            coroutineContext.ensureActive()
            if (fixture.cancelBeforeActionIndex == action.index) {
                terminal = WorkflowReplayTerminal.CANCELLED
                break
            }
            val expected = fixture.expectedActions.getOrNull(action.index)
                ?: return fixture.failed(
                    WorkflowVerificationFailureCode.ACTION_ORACLE_MISMATCH,
                    action.index,
                    observations,
                )
            if (expected.actionIndex != action.index || expected.toolName != action.toolName) {
                return fixture.failed(
                    WorkflowVerificationFailureCode.ACTION_ORACLE_MISMATCH,
                    action.index,
                    observations,
                )
            }
            val frozen = subject.toolSchemaFingerprints[action.index]
            val registration = fakeTools.registration(action.toolName)
                ?: return fixture.failed(
                    WorkflowVerificationFailureCode.FAKE_TOOL_MISSING,
                    action.index,
                    observations,
                )
            if (action.schemaFingerprint != frozen.schemaFingerprint ||
                action.toolName != frozen.toolName ||
                action.schemaFingerprint != registration.schemaFingerprint ||
                action.schemaFingerprint != expected.schemaFingerprint
            ) {
                return fixture.failed(
                    WorkflowVerificationFailureCode.ACTION_SCHEMA_MISMATCH,
                    action.index,
                    observations,
                )
            }
            val resolved = when (val resolution = resolveReplaySlots(
                action.args,
                slots,
                fixture.slotBindings,
            )) {
                is ReplaySlotResolution.Failed -> return fixture.failed(
                    resolution.code,
                    action.index,
                    observations,
                )
                is ReplaySlotResolution.Ready -> resolution.value
            }
            if (canonicalVerifierJson(resolved) != canonicalVerifierJson(expected.resolvedArgs)) {
                return fixture.failed(
                    WorkflowVerificationFailureCode.ACTION_ORACLE_MISMATCH,
                    action.index,
                    observations,
                )
            }
            val fakeOutcome = registration.adapter.replay(action.index, resolved)
                ?: return fixture.failed(
                    WorkflowVerificationFailureCode.FAKE_CASE_MISSING,
                    action.index,
                    observations,
                )
            val timeoutMs = action.timeoutSeconds.toLong() * 1_000L
            val actual = if (fakeOutcome.simulatedDurationMs >= timeoutMs) {
                ActualReplayResult(WorkflowReplayResultKind.TIMED_OUT)
            } else {
                when (fakeOutcome) {
                    is FakeWorkflowToolOutcome.Cancelled ->
                        ActualReplayResult(WorkflowReplayResultKind.CANCELLED)
                    is FakeWorkflowToolOutcome.Failure -> ActualReplayResult(
                        kind = WorkflowReplayResultKind.FAILED,
                        errorCode = fakeOutcome.errorCode,
                    )
                    is FakeWorkflowToolOutcome.Success -> {
                        val canonicalOutput = canonicalVerifierJson(fakeOutcome.output)
                        val size = canonicalOutput.toByteArray(Charsets.UTF_8).size
                        val exceeds = size > subject.maxOutputUtf8Bytes - outputBytes
                        outputBytes = if (exceeds) outputBytes else outputBytes + size
                        ActualReplayResult(
                            kind = if (exceeds) {
                                WorkflowReplayResultKind.OUTPUT_LIMIT
                            } else {
                                WorkflowReplayResultKind.SUCCESS
                            },
                            output = fakeOutcome.output,
                            outputCanonical = canonicalOutput,
                            outputUtf8Bytes = size,
                        )
                    }
                }
            }
            observations += action.observation(resolved, actual)
            if (!actual.matches(expected.expectedResult)) {
                val code = if (actual.kind == WorkflowReplayResultKind.OUTPUT_LIMIT) {
                    WorkflowVerificationFailureCode.OUTPUT_UTF8_LIMIT_EXCEEDED
                } else {
                    WorkflowVerificationFailureCode.RESULT_ORACLE_MISMATCH
                }
                return fixture.failed(code, action.index, observations)
            }
            terminal = when (actual.kind) {
                WorkflowReplayResultKind.SUCCESS -> null
                WorkflowReplayResultKind.FAILED -> WorkflowReplayTerminal.FAILED
                WorkflowReplayResultKind.TIMED_OUT -> WorkflowReplayTerminal.TIMED_OUT
                WorkflowReplayResultKind.CANCELLED -> WorkflowReplayTerminal.CANCELLED
                WorkflowReplayResultKind.OUTPUT_LIMIT -> WorkflowReplayTerminal.OUTPUT_LIMIT
            }
            // First error/timeout/cancel/output-bound event always stops the program.
            if (terminal != null) break
        }
        if (terminal == null) terminal = WorkflowReplayTerminal.COMPLETED
        if (observations.size != fixture.expectedActions.size || terminal != fixture.expectedTerminal) {
            return fixture.failed(
                WorkflowVerificationFailureCode.TERMINAL_ORACLE_MISMATCH,
                observations.lastOrNull()?.actionIndex,
                observations,
                terminal,
            )
        }
        return WorkflowVerificationFixtureReport(
            fixtureId = fixture.fixtureId,
            status = WorkflowVerificationStatus.PASSED,
            terminal = terminal,
            observations = observations,
            failureCode = null,
            failureActionIndex = null,
        )
    }
}

private data class ReplayAction(
    val index: Int,
    val toolName: String,
    val args: JsonObject,
    val timeoutSeconds: Int,
    val schemaFingerprint: String,
)

private data class ActualReplayResult(
    val kind: WorkflowReplayResultKind,
    val output: JsonElement? = null,
    val outputCanonical: String? = null,
    val outputUtf8Bytes: Int = 0,
    val errorCode: String? = null,
) {
    fun matches(expected: WorkflowReplayExpectedResult): Boolean =
        kind == expected.kind && errorCode == expected.errorCode && when {
            output == null && expected.output == null -> true
            output != null && expected.output != null ->
                outputCanonical == canonicalVerifierJson(expected.output)
            else -> false
        }
}

private sealed interface ReplaySlotResolution {
    data class Ready(val value: JsonObject) : ReplaySlotResolution
    data class Failed(val code: WorkflowVerificationFailureCode) : ReplaySlotResolution
}

private fun resolveReplaySlots(
    args: JsonObject,
    slots: Map<String, LearnedWorkflowTypedSlot>,
    fixtureBindings: Map<String, JsonElement>,
): ReplaySlotResolution {
    var failure: WorkflowVerificationFailureCode? = null
    fun resolve(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { resolve(it.value) })
        is JsonArray -> JsonArray(value.map(::resolve))
        is JsonPrimitive -> {
            val match = value.contentOrNull?.let(SLOT_REFERENCE::matchEntire)
            if (match == null) {
                if (value.contentOrNull?.contains("{{slot:") == true) {
                    failure = WorkflowVerificationFailureCode.SLOT_UNKNOWN
                }
                value
            } else {
                val slot = slots[match.groupValues[1]]
                if (slot == null) {
                    failure = WorkflowVerificationFailureCode.SLOT_UNKNOWN
                    value
                } else {
                    val binding = fixtureBindings[slot.name] ?: slot.value ?: slot.secretRef?.let {
                        JsonPrimitive(it)
                    }
                    when {
                        binding == null && slot.required -> {
                            failure = WorkflowVerificationFailureCode.SLOT_UNBOUND
                            value
                        }
                        binding == null -> JsonNull
                        !bindingMatchesSlot(slot.type, slot.enumValues, binding) -> {
                            failure = WorkflowVerificationFailureCode.SLOT_TYPE_MISMATCH
                            value
                        }
                        else -> binding
                    }
                }
            }
        }
        JsonNull -> JsonNull
    }
    val resolved = resolve(args) as JsonObject
    return failure?.let(ReplaySlotResolution::Failed) ?: ReplaySlotResolution.Ready(resolved)
}

private fun bindingMatchesSlot(
    type: LearnedWorkflowSlotType,
    enumValues: List<String>,
    value: JsonElement,
): Boolean = when (type) {
    LearnedWorkflowSlotType.STRING -> value is JsonPrimitive && value.isString
    LearnedWorkflowSlotType.INTEGER -> value is JsonPrimitive && !value.isString &&
        value.longOrNull != null
    LearnedWorkflowSlotType.NUMBER -> value is JsonPrimitive && !value.isString &&
        value.doubleOrNull != null
    LearnedWorkflowSlotType.BOOLEAN -> value is JsonPrimitive && !value.isString &&
        value.booleanOrNull != null
    LearnedWorkflowSlotType.ENUM -> value is JsonPrimitive && value.isString &&
        value.contentOrNull in enumValues
    LearnedWorkflowSlotType.SECRET_REF -> value is JsonPrimitive && value.isString &&
        value.contentOrNull?.matches(SECRET_REF) == true
}

private fun parseTemplate(
    subject: WorkflowVerificationSubject,
): List<ReplayAction>? {
    val root = runCatching {
        Json.parseToJsonElement(subject.canonicalTemplateJson) as? JsonObject
    }.getOrNull() ?: return null
    val actions = root["actions"] as? JsonArray ?: return null
    if (actions.size !in 1..8) return null
    return actions.mapIndexed { index, element ->
        val action = element as? JsonObject ?: return null
        val tool = (action["tool"] as? JsonPrimitive)?.contentOrNull ?: return null
        val args = action["args"] as? JsonObject ?: return null
        val timeout = (action["timeout_seconds"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it in 1L..120L }?.toInt() ?: return null
        val schema = (action["tool_schema_fingerprint"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isVerifierSha256) ?: return null
        ReplayAction(index, tool, args, timeout, schema)
    }
}

private fun ReplayAction.observation(
    resolvedArgs: JsonObject,
    actual: ActualReplayResult,
): WorkflowVerificationActionObservation = WorkflowVerificationActionObservation(
    actionIndex = index,
    toolName = toolName,
    schemaFingerprint = schemaFingerprint,
    argsSha256 = sha256VerifierText(canonicalVerifierJson(resolvedArgs)),
    resultKind = actual.kind,
    outputSha256 = actual.outputCanonical?.let(::sha256VerifierText),
    outputUtf8Bytes = actual.outputUtf8Bytes,
    errorCode = actual.errorCode,
)

private fun WorkflowReplayFixture.failed(
    code: WorkflowVerificationFailureCode,
    actionIndex: Int? = null,
    observations: List<WorkflowVerificationActionObservation> = emptyList(),
    terminal: WorkflowReplayTerminal? = null,
): WorkflowVerificationFixtureReport = WorkflowVerificationFixtureReport(
    fixtureId = fixtureId,
    status = WorkflowVerificationStatus.FAILED,
    terminal = terminal,
    observations = observations,
    failureCode = code,
    failureActionIndex = actionIndex,
)

private fun aggregate(
    subject: WorkflowVerificationSubject,
    fixtureDigest: String,
    reports: List<WorkflowVerificationFixtureReport>,
    failures: List<WorkflowVerificationFailureCode>,
    status: WorkflowVerificationStatus,
) = WorkflowVerificationReport(
    verifierVersion = subject.verifierVersion,
    fixtureSetSha256 = fixtureDigest,
    subjectArtifactSha256 = subject.artifactSha256,
    status = status,
    fixtures = reports,
    failureCodes = failures.distinct().sortedBy(Enum<*>::name),
)

private val SLOT_REFERENCE = Regex("^\\{\\{slot:([a-z][a-z0-9_]{0,63})}}$")
private val SECRET_REF = Regex("^secret-ref:[A-Za-z0-9_.:@/-]{1,160}$")
