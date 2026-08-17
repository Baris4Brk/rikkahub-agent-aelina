package me.rerere.rikkahub.learning.eval

/** Terminal facts read from an authority stream, not selected by the component adapter. */
internal data class DurableAuthorityTraceRecord(
    val unitId: String,
    val taskOutcome: BinaryObservation,
    val harmfulOutcome: BinaryObservation,
    val userCorrectionCount: Int,
    val outputTokens: Int,
    val toolCalls: Int,
    val toolRetries: Int,
    val recordedLatency: RecordedLatencyObservation,
    val deterministicJudge: JudgeVerdict,
    val humanJudge: JudgeVerdict,
    val llmJudge: JudgeVerdict,
    val scriptActionCount: Int,
) {
    init {
        requireSafeEvalLabel(unitId)
        require(userCorrectionCount >= 0 && outputTokens >= 0)
        require(toolCalls >= 0 && toolRetries in 0..toolCalls)
        require(scriptActionCount == 0)
    }

    fun digestSha256(): String = EvalDigest.sha256(
        "p5-durable-authority-trace-row-v1",
        listOf(DurableFourArmRuntimeEvidenceCodec.encodeAuthority(this)),
    )
}

internal enum class DurableRuntimeEvidenceOrigin {
    /** Checked-in golden data: useful for replay regressions, never rollout evidence. */
    CHECKED_IN_REGRESSION_FIXTURE,

    /** Separately captured executions whose authority rows were supplied after each arm ran. */
    INDEPENDENT_RUNTIME_CAPTURE,
}

/** Versioned identities for three separately executed judge streams. */
internal data class DurableJudgeSourceIdentities(
    val deterministicSourceId: String,
    val humanSourceId: String,
    val llmSourceId: String,
) {
    init {
        listOf(deterministicSourceId, humanSourceId, llmSourceId).forEach(::requireSafeEvalLabel)
    }

    val areIndependent: Boolean
        get() = setOf(deterministicSourceId, humanSourceId, llmSourceId).size == 3

    fun digestFields(): List<String> = listOf(
        deterministicSourceId,
        humanSourceId,
        llmSourceId,
    )
}

internal object DurableRuntimeAuthorityManifest {
    fun digest(
        authoritySourceId: String,
        records: List<DurableAuthorityTraceRecord>,
    ): String = EvalDigest.sha256(
        domain = "p5-independent-runtime-authority-manifest-v1",
        fields = listOf(authoritySourceId) + records.sortedBy { it.unitId }.map {
            it.digestSha256()
        },
    )
}

/** One persisted matrix row, including the assignment frozen before any component ran. */
internal data class DurableFourArmObservationRecord(
    val unitId: String,
    val matchedCohortId: String,
    val sliceDigestSha256: String,
    val primaryArm: OfflineEvalArm,
    val partition: EvalPartition,
    val arm: OfflineEvalArm,
    val authorityTraceDigestSha256: String,
    val observation: OfflineReplayObservation,
) {
    init {
        requireSafeEvalLabel(unitId)
        requireSafeEvalLabel(matchedCohortId)
        require(sliceDigestSha256.isEvalSha256())
        require(authorityTraceDigestSha256.isEvalSha256())
        require(observation.unitId == unitId && observation.arm == arm)
    }

    val key: String get() = "$unitId:${arm.name}"

    fun digestSha256(): String = EvalDigest.sha256(
        "p5-durable-four-arm-observation-row-v1",
        listOf(DurableFourArmRuntimeEvidenceCodec.encodeObservation(this)),
    )
}

internal data class DurableFourArmReceiptRecord(
    val receipt: ProductionComponentReplayReceipt,
) {
    val key: String get() =
        "${receipt.unitId}:${receipt.arm.name}:${receipt.component.name}"

    fun digestSha256(): String = EvalDigest.sha256(
        "p5-durable-four-arm-receipt-row-v1",
        listOf(DurableFourArmRuntimeEvidenceCodec.encodeReceipt(this)),
    )
}

/** Frozen and journaled before the first arm invokes a component. */
internal data class DurableFourArmPreRegistration internal constructor(
    val schemaVersion: Int,
    val manifestDigestSha256: String,
    val corpusDigestSha256: String,
    val planDigestSha256: String,
    val assignmentManifestSha256: String,
    val digestSha256: String,
) {
    init {
        require(schemaVersion == 1)
        listOf(
            manifestDigestSha256,
            corpusDigestSha256,
            planDigestSha256,
            assignmentManifestSha256,
            digestSha256,
        ).forEach { require(it.isEvalSha256()) }
        require(digestSha256 == computeDigest(
            manifestDigestSha256,
            corpusDigestSha256,
            planDigestSha256,
            assignmentManifestSha256,
        ))
    }

    fun canonicalWire(): String = listOf(
        "p5-durable-four-arm-preregistration-v1",
        schemaVersion.toString(),
        manifestDigestSha256,
        corpusDigestSha256,
        planDigestSha256,
        assignmentManifestSha256,
        digestSha256,
    ).joinToString(WIRE_SEPARATOR)

    companion object {
        fun freeze(manifest: FrozenProductionEvalManifest): DurableFourArmPreRegistration {
            val digest = computeDigest(
                manifest.digestSha256,
                manifest.corpusDigestSha256,
                manifest.planDigestSha256,
                manifest.assignmentManifestSha256,
            )
            return DurableFourArmPreRegistration(
                schemaVersion = 1,
                manifestDigestSha256 = manifest.digestSha256,
                corpusDigestSha256 = manifest.corpusDigestSha256,
                planDigestSha256 = manifest.planDigestSha256,
                assignmentManifestSha256 = manifest.assignmentManifestSha256,
                digestSha256 = digest,
            )
        }

        fun decode(wire: String): DurableFourArmPreRegistration {
            val field = wire.split(WIRE_SEPARATOR)
            require(field.size == 7 &&
                field[0] == "p5-durable-four-arm-preregistration-v1")
            return DurableFourArmPreRegistration(
                schemaVersion = field[1].toInt(),
                manifestDigestSha256 = field[2],
                corpusDigestSha256 = field[3],
                planDigestSha256 = field[4],
                assignmentManifestSha256 = field[5],
                digestSha256 = field[6],
            )
        }

        private fun computeDigest(
            manifestDigestSha256: String,
            corpusDigestSha256: String,
            planDigestSha256: String,
            assignmentManifestSha256: String,
        ): String = EvalDigest.sha256(
            "p5-durable-four-arm-preregistration-v1",
            listOf(
                manifestDigestSha256,
                corpusDigestSha256,
                planDigestSha256,
                assignmentManifestSha256,
            ),
        )
    }
}

/**
 * Exact content read back from a disposable-emulator journal. The committed digest is stored by
 * the journal separately, then compared with [snapshotDigestSha256] only after close/reopen.
 */
internal data class DurableFourArmRuntimeEvidence(
    val schemaVersion: Int,
    val contractId: String,
    val manifestDigestSha256: String,
    val reportDigestSha256: String,
    val corpusDigestSha256: String,
    val planDigestSha256: String,
    val assignmentManifestSha256: String,
    val preRegistrationDigestSha256: String,
    val authorityManifestDigestSha256: String,
    val authoritySourceId: String,
    val origin: DurableRuntimeEvidenceOrigin,
    val judgeSources: DurableJudgeSourceIdentities,
    val authorityRecords: List<DurableAuthorityTraceRecord>,
    val observationRecords: List<DurableFourArmObservationRecord>,
    val receiptRecords: List<DurableFourArmReceiptRecord>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(contractId == FrozenProductionFourArmRuntimeContractV1.CONTRACT_ID)
        listOf(
            manifestDigestSha256,
            reportDigestSha256,
            corpusDigestSha256,
            planDigestSha256,
            assignmentManifestSha256,
            preRegistrationDigestSha256,
            authorityManifestDigestSha256,
        ).forEach { require(it.isEvalSha256()) }
        requireSafeEvalLabel(authoritySourceId)
        if (origin == DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE) {
            require(authoritySourceId != FrozenArmBlindAuthorityTraceV1.TRACE_VERSION)
            require(judgeSources.areIndependent)
        }
    }

    fun headerWire(): String = listOf(
        "p5-durable-four-arm-evidence-v2",
        schemaVersion.toString(),
        contractId,
        manifestDigestSha256,
        reportDigestSha256,
        corpusDigestSha256,
        planDigestSha256,
        assignmentManifestSha256,
        preRegistrationDigestSha256,
        authorityManifestDigestSha256,
        authoritySourceId,
        origin.name,
        judgeSources.deterministicSourceId,
        judgeSources.humanSourceId,
        judgeSources.llmSourceId,
    ).joinToString(WIRE_SEPARATOR)

    fun snapshotDigestSha256(): String = EvalDigest.sha256(
        domain = "p5-durable-four-arm-journal-snapshot-v2",
        fields = buildList {
            add(headerWire())
            authorityRecords.sortedBy { it.unitId }.forEach {
                add(DurableFourArmRuntimeEvidenceCodec.encodeAuthority(it))
            }
            observationRecords.sortedBy(DurableFourArmObservationRecord::key).forEach {
                add(DurableFourArmRuntimeEvidenceCodec.encodeObservation(it))
            }
            receiptRecords.sortedBy(DurableFourArmReceiptRecord::key).forEach {
                add(DurableFourArmRuntimeEvidenceCodec.encodeReceipt(it))
            }
        },
    )

    companion object {
        const val SCHEMA_VERSION: Int = 2
    }
}

/** Captures regression or independently supplied authority rows without inventing terminal facts. */
internal object DurableFourArmRuntimeEvidenceCapture {
    private val fixtureJudgeSources = DurableJudgeSourceIdentities(
        deterministicSourceId = "checked-in-shared-fixture-verdict-v1",
        humanSourceId = "checked-in-shared-fixture-verdict-v1",
        llmSourceId = "checked-in-shared-fixture-verdict-v1",
    )

    fun captureCheckedInFixture(
        manifest: FrozenProductionEvalManifest,
        preRegistration: DurableFourArmPreRegistration,
        run: ProductionComponentReplayRun,
    ): DurableFourArmRuntimeEvidence = capture(
        manifest = manifest,
        preRegistration = preRegistration,
        run = run,
        origin = DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE,
        authoritySourceId = FrozenArmBlindAuthorityTraceV1.TRACE_VERSION,
        authorityRecords = FrozenArmBlindAuthorityTraceV1.records,
        judgeSources = fixtureJudgeSources,
        authorityForObservation = { observation ->
            requireNotNull(FrozenArmBlindAuthorityTraceV1.recordFor(observation.unitId))
        },
    )

    /**
     * Builds an approval-eligible envelope only from authority rows captured outside the fixture
     * runner. There must be one unique authority row for every unit/arm execution.
     */
    fun captureIndependentRuntime(
        manifest: FrozenProductionEvalManifest,
        preRegistration: DurableFourArmPreRegistration,
        run: ProductionComponentReplayRun,
        authoritySourceId: String,
        authorityRecordsByObservationKey: Map<String, DurableAuthorityTraceRecord>,
        judgeSources: DurableJudgeSourceIdentities,
    ): DurableFourArmRuntimeEvidence {
        require(authoritySourceId != FrozenArmBlindAuthorityTraceV1.TRACE_VERSION)
        require(judgeSources.areIndependent)
        require(run.adapterIdentities != FrozenProductionComponentReplayV1.adapters.identities) {
            "The checked-in fixed adapter set is regression-only authority evidence"
        }
        val expectedKeys = run.observations.map { "${it.unitId}:${it.arm.name}" }.toSet()
        require(authorityRecordsByObservationKey.keys == expectedKeys)
        val records = authorityRecordsByObservationKey.values.toList()
        require(records.size == expectedKeys.size)
        require(records.map { it.unitId }.toSet().size == records.size)
        require(records.map { it.digestSha256() }.toSet().size == records.size)
        return capture(
            manifest = manifest,
            preRegistration = preRegistration,
            run = run,
            origin = DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE,
            authoritySourceId = authoritySourceId,
            authorityRecords = records,
            judgeSources = judgeSources,
            authorityForObservation = { observation ->
                requireNotNull(
                    authorityRecordsByObservationKey["${observation.unitId}:${observation.arm.name}"],
                )
            },
        )
    }

    private fun capture(
        manifest: FrozenProductionEvalManifest,
        preRegistration: DurableFourArmPreRegistration,
        run: ProductionComponentReplayRun,
        origin: DurableRuntimeEvidenceOrigin,
        authoritySourceId: String,
        authorityRecords: List<DurableAuthorityTraceRecord>,
        judgeSources: DurableJudgeSourceIdentities,
        authorityForObservation: (OfflineReplayObservation) -> DurableAuthorityTraceRecord,
    ): DurableFourArmRuntimeEvidence {
        require(preRegistration == DurableFourArmPreRegistration.freeze(manifest))
        require(run.adapterIdentities == manifest.adapterIdentities)
        val unitById = FrozenReplayCorpusV1.units.associateBy(OfflineReplayUnit::unitId)
        val assignmentById = PreRegisteredAssignmentEngine.assign(
            FrozenReplayCorpusV1.units,
            FrozenOfflineLearningEvaluation.plan,
        ).associateBy(PreRegisteredAssignment::unitId)
        return DurableFourArmRuntimeEvidence(
            schemaVersion = DurableFourArmRuntimeEvidence.SCHEMA_VERSION,
            contractId = FrozenProductionFourArmRuntimeContractV1.CONTRACT_ID,
            manifestDigestSha256 = manifest.digestSha256,
            reportDigestSha256 = run.report.digestSha256(),
            corpusDigestSha256 = run.report.corpusDigestSha256,
            planDigestSha256 = run.report.planDigestSha256,
            assignmentManifestSha256 = run.report.assignmentManifestSha256,
            preRegistrationDigestSha256 = preRegistration.digestSha256,
            authorityManifestDigestSha256 = when (origin) {
                DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE ->
                    FrozenArmBlindAuthorityTraceV1.manifestDigestSha256
                DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE ->
                    DurableRuntimeAuthorityManifest.digest(authoritySourceId, authorityRecords)
            },
            authoritySourceId = authoritySourceId,
            origin = origin,
            judgeSources = judgeSources,
            authorityRecords = authorityRecords,
            observationRecords = run.observations.map { observation ->
                val unit = requireNotNull(unitById[observation.unitId])
                val assignment = requireNotNull(assignmentById[observation.unitId])
                val trace = authorityForObservation(observation)
                DurableFourArmObservationRecord(
                    unitId = observation.unitId,
                    matchedCohortId = unit.matchedCohortId,
                    sliceDigestSha256 = unit.slice.digestSha256(),
                    primaryArm = assignment.primaryArm,
                    partition = assignment.partition,
                    arm = observation.arm,
                    authorityTraceDigestSha256 = trace.digestSha256(),
                    observation = observation,
                )
            },
            receiptRecords = run.receipts.map(::DurableFourArmReceiptRecord),
        )
    }
}

/**
 * The sole production path that can mint a PASSED durable four-arm attestation. It accepts only
 * a reopened, independently sourced authority matrix. Checked-in fixture evidence is fully
 * validated as a regression and then explicitly abstains.
 */
internal object ProductionFourArmRuntimeEvidenceVerifier {
    fun verifyReopened(
        expectedManifest: FrozenProductionEvalManifest,
        committedSnapshotDigestSha256: String,
        reopenedPreRegistration: DurableFourArmPreRegistration,
        reopened: DurableFourArmRuntimeEvidence,
    ): ProductionFourArmRuntimeAttestation {
        require(committedSnapshotDigestSha256.isEvalSha256())
        val checks = linkedSetOf<ProductionFourArmRuntimeCheck>()
        fun abstain(reason: ProductionFourArmRuntimeReason) =
            ProductionFourArmRuntimeAttestationFactory.abstained(
                manifestDigestSha256 = expectedManifest.digestSha256,
                reportDigestSha256 = reopened.reportDigestSha256,
                reason = reason,
                observedChecks = checks,
                durableEvidenceDigestSha256 = reopened.snapshotDigestSha256(),
            )
        fun reject(reason: ProductionFourArmRuntimeReason) =
            ProductionFourArmRuntimeAttestationFactory.rejected(
                manifestDigestSha256 = expectedManifest.digestSha256,
                reportDigestSha256 = reopened.reportDigestSha256,
                reason = reason,
                observedChecks = checks,
                durableEvidenceDigestSha256 = reopened.snapshotDigestSha256(),
            )

        if (reopenedPreRegistration != DurableFourArmPreRegistration.freeze(expectedManifest) ||
            reopened.preRegistrationDigestSha256 != reopenedPreRegistration.digestSha256 ||
            !identityMatches(expectedManifest, reopened)
        ) {
            return reject(ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH)
        }
        val reopenedDigest = reopened.snapshotDigestSha256()
        if (committedSnapshotDigestSha256 != reopenedDigest) {
            return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
        }

        val expectedUnitCount = FrozenReplayCorpusV1.units.size
        val expectedObservationCount = expectedUnitCount * OfflineEvalArm.entries.size
        val expectedReceiptCount = expectedObservationCount *
            ProductionReplayComponent.entries.size
        val expectedAuthorityCount = when (reopened.origin) {
            DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE -> expectedUnitCount
            DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE -> expectedObservationCount
        }
        if (reopened.authorityRecords.size < expectedAuthorityCount ||
            reopened.observationRecords.size < expectedObservationCount ||
            reopened.receiptRecords.size < expectedReceiptCount
        ) {
            return abstain(ProductionFourArmRuntimeReason.WINDOW_INCOMPLETE)
        }
        if (reopened.authorityRecords.size != expectedAuthorityCount ||
            reopened.observationRecords.size != expectedObservationCount ||
            reopened.receiptRecords.size != expectedReceiptCount
        ) {
            return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
        }

        val authorityByUnit = reopened.authorityRecords.associateBy { it.unitId }
        val authorityByDigest = reopened.authorityRecords.associateBy { it.digestSha256() }
        val observationsByKey = reopened.observationRecords.associateBy { it.key }
        val receiptsByKey = reopened.receiptRecords.associateBy { it.key }
        if (authorityByUnit.size != reopened.authorityRecords.size ||
            authorityByDigest.size != reopened.authorityRecords.size ||
            observationsByKey.size != reopened.observationRecords.size ||
            receiptsByKey.size != reopened.receiptRecords.size
        ) {
            return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
        }
        if (reopened.origin == DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE &&
            reopened.authorityRecords.sortedBy { it.unitId } !=
                FrozenArmBlindAuthorityTraceV1.records.sortedBy { it.unitId }
        ) {
            return reject(ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH)
        }

        val assignments = PreRegisteredAssignmentEngine.assign(
            FrozenReplayCorpusV1.units,
            FrozenOfflineLearningEvaluation.plan,
        ).associateBy(PreRegisteredAssignment::unitId)
        for (unit in FrozenReplayCorpusV1.units) {
            val assignment = assignments.getValue(unit.unitId)
            for (arm in OfflineEvalArm.entries) {
                val row = observationsByKey["${unit.unitId}:${arm.name}"]
                    ?: return reject(
                        ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION,
                    )
                if (row.matchedCohortId != unit.matchedCohortId ||
                    row.sliceDigestSha256 != unit.slice.digestSha256() ||
                    row.arm != arm || row.observation.arm != arm
                ) {
                    return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
                }
                if (row.primaryArm != assignment.primaryArm ||
                    row.partition != assignment.partition
                ) {
                    return reject(ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH)
                }
                val trace = when (reopened.origin) {
                    DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE ->
                        authorityByUnit[unit.unitId]
                    DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE ->
                        authorityByDigest[row.authorityTraceDigestSha256]
                } ?: return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
                if (row.authorityTraceDigestSha256 != trace.digestSha256() ||
                    !row.observation.matchesAuthority(trace, arm)
                ) {
                    return reject(ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH)
                }
                var armWork = DeterministicComponentWork.ZERO
                for (component in ProductionReplayComponent.entries) {
                    val receipt = receiptsByKey[
                        "${unit.unitId}:${arm.name}:${component.name}"
                    ]?.receipt ?: return reject(
                        ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION,
                    )
                    if (receipt.state != expectedReceiptState(arm, component) ||
                        receipt.abstainReason != null
                    ) {
                        return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
                    }
                    armWork += receipt.work
                }
                val expectedTrend = JvmTrendObservation(
                    armWork.operationUnits,
                    armWork.logicalAllocationUnits,
                )
                if (row.observation.jvmTrend != expectedTrend) {
                    return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
                }
            }
        }

        checks += ProductionFourArmRuntimeCheck.MATCHED_COHORTS_COMPLETE
        checks += ProductionFourArmRuntimeCheck.ARM_ASSIGNMENT_PRE_REGISTERED
        checks += ProductionFourArmRuntimeCheck.AUTHORITY_OUTCOMES_DURABLE
        if (reopened.origin == DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE) {
            if (!reopened.judgeSources.areIndependent ||
                !hasIndependentJudgeDivergence(reopened.authorityRecords)
            ) {
                return reject(ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH)
            }
            val referencedAuthorityDigests = reopened.observationRecords
                .map { it.authorityTraceDigestSha256 }
                .toSet()
            if (referencedAuthorityDigests != authorityByDigest.keys) {
                return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
            }
            checks += ProductionFourArmRuntimeCheck.INDEPENDENT_RUNTIME_AUTHORITY_CAPTURED
            checks += ProductionFourArmRuntimeCheck.INDEPENDENT_JUDGE_SOURCES_OBSERVED
        }

        val rows = reopened.observationRecords.map(DurableFourArmObservationRecord::observation)
        val rebuilt = try {
            OfflineLearningEvalHarness.summarizeObserved(
                corpus = FrozenReplayCorpusV1.units,
                plan = FrozenOfflineLearningEvaluation.plan,
                observations = rows,
                corpusId = FrozenReplayCorpusV1.CORPUS_ID,
            )
        } catch (_: IllegalArgumentException) {
            return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
        }
        if (rebuilt.digestSha256() != reopened.reportDigestSha256) {
            return reject(ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH)
        }

        OfflineEvalArm.entries.forEach { arm ->
            if (rows.count { it.arm == arm } != expectedUnitCount) {
                return abstain(ProductionFourArmRuntimeReason.WINDOW_INCOMPLETE)
            }
            checks += arm.runtimeCheck()
        }
        if (!requiredSlicesComplete(reopened.observationRecords)) {
            return reject(ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION)
        }
        checks += ProductionFourArmRuntimeCheck.REQUIRED_SLICES_COMPLETE

        if (reopened.origin == DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE) {
            return abstain(ProductionFourArmRuntimeReason.CHECKED_IN_REGRESSION_FIXTURE_ONLY)
        }
        if (checks != FrozenProductionFourArmRuntimeContractV1.requiredChecks) {
            return abstain(ProductionFourArmRuntimeReason.WINDOW_INCOMPLETE)
        }
        return ProductionFourArmRuntimeAttestationFactory.passed(
            manifestDigestSha256 = expectedManifest.digestSha256,
            reportDigestSha256 = reopened.reportDigestSha256,
            observedChecks = checks,
            durableEvidenceDigestSha256 = reopenedDigest,
        )
    }

    private fun identityMatches(
        expected: FrozenProductionEvalManifest,
        evidence: DurableFourArmRuntimeEvidence,
    ): Boolean {
        val assignments = PreRegisteredAssignmentEngine.assign(
            FrozenReplayCorpusV1.units,
            FrozenOfflineLearningEvaluation.plan,
        )
        return expected.corpusDigestSha256 == FrozenReplayCorpusV1.digestSha256 &&
            expected.planDigestSha256 == FrozenOfflineLearningEvaluation.plan.digestSha256() &&
            expected.assignmentManifestSha256 ==
                PreRegisteredAssignmentEngine.manifestDigest(assignments) &&
            evidence.contractId == FrozenProductionFourArmRuntimeContractV1.CONTRACT_ID &&
            evidence.manifestDigestSha256 == expected.digestSha256 &&
            evidence.corpusDigestSha256 == expected.corpusDigestSha256 &&
            evidence.planDigestSha256 == expected.planDigestSha256 &&
            evidence.assignmentManifestSha256 == expected.assignmentManifestSha256 &&
            evidence.preRegistrationDigestSha256 ==
                DurableFourArmPreRegistration.freeze(expected).digestSha256 &&
            authorityIdentityMatches(evidence)
    }

    private fun authorityIdentityMatches(evidence: DurableFourArmRuntimeEvidence): Boolean =
        when (evidence.origin) {
            DurableRuntimeEvidenceOrigin.CHECKED_IN_REGRESSION_FIXTURE ->
                evidence.authoritySourceId == FrozenArmBlindAuthorityTraceV1.TRACE_VERSION &&
                    evidence.authorityManifestDigestSha256 ==
                    FrozenArmBlindAuthorityTraceV1.manifestDigestSha256
            DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE ->
                evidence.authoritySourceId != FrozenArmBlindAuthorityTraceV1.TRACE_VERSION &&
                    evidence.judgeSources.areIndependent &&
                    evidence.authorityManifestDigestSha256 == DurableRuntimeAuthorityManifest.digest(
                        evidence.authoritySourceId,
                        evidence.authorityRecords,
                    )
        }

    private fun hasIndependentJudgeDivergence(
        records: List<DurableAuthorityTraceRecord>,
    ): Boolean = records.any {
        it.deterministicJudge != JudgeVerdict.UNKNOWN &&
            it.humanJudge != JudgeVerdict.UNKNOWN &&
            it.llmJudge != JudgeVerdict.UNKNOWN &&
            (it.llmJudge != it.deterministicJudge || it.llmJudge != it.humanJudge)
    }

    private fun requiredSlicesComplete(rows: List<DurableFourArmObservationRecord>): Boolean {
        val unitById = FrozenReplayCorpusV1.units.associateBy(OfflineReplayUnit::unitId)
        return EvalSliceDimension.entries.all { dimension ->
            val values = FrozenReplayCorpusV1.units.map {
                it.slice.dimensions().getValue(dimension)
            }.toSet()
            values.all { value ->
                OfflineEvalArm.entries.all { arm ->
                    rows.any { row ->
                        row.arm == arm && unitById[row.unitId]?.slice?.dimensions()
                            ?.get(dimension) == value
                    }
                }
            }
        }
    }
}

/** Stable, content-free wire codec used by the Android SQLite journal. */
internal object DurableFourArmRuntimeEvidenceCodec {
    fun encodeAuthority(row: DurableAuthorityTraceRecord): String = listOf(
        AUTHORITY_WIRE_VERSION,
        row.unitId,
        row.taskOutcome.toWire(),
        row.harmfulOutcome.toWire(),
        row.userCorrectionCount.toString(),
        row.outputTokens.toString(),
        row.toolCalls.toString(),
        row.toolRetries.toString(),
        row.recordedLatency.ttftMicros.toWire(),
        row.recordedLatency.toolToNextModelMicros.toWire(),
        row.deterministicJudge.name,
        row.humanJudge.name,
        row.llmJudge.name,
        row.scriptActionCount.toString(),
    ).joinToString(WIRE_SEPARATOR)

    fun decodeAuthority(wire: String): DurableAuthorityTraceRecord {
        val field = wire.split(WIRE_SEPARATOR)
        require(field.size == 14 && field[0] == AUTHORITY_WIRE_VERSION)
        return DurableAuthorityTraceRecord(
            unitId = field[1],
            taskOutcome = field[2].toBinaryObservation(),
            harmfulOutcome = field[3].toBinaryObservation(),
            userCorrectionCount = field[4].toInt(),
            outputTokens = field[5].toInt(),
            toolCalls = field[6].toInt(),
            toolRetries = field[7].toInt(),
            recordedLatency = RecordedLatencyObservation(
                field[8].toNullableLong(),
                field[9].toNullableLong(),
            ),
            deterministicJudge = JudgeVerdict.valueOf(field[10]),
            humanJudge = JudgeVerdict.valueOf(field[11]),
            llmJudge = JudgeVerdict.valueOf(field[12]),
            scriptActionCount = field[13].toInt(),
        )
    }

    fun encodeObservation(row: DurableFourArmObservationRecord): String {
        val observation = row.observation
        return listOf(
            OBSERVATION_WIRE_VERSION,
            row.unitId,
            row.matchedCohortId,
            row.sliceDigestSha256,
            row.primaryArm.name,
            row.partition.name,
            row.arm.name,
            row.authorityTraceDigestSha256,
            observation.taskOutcome.toWire(),
            observation.harmfulOutcome.toWire(),
            observation.userCorrectionCount.toString(),
            observation.resources.inputTokens.toString(),
            observation.resources.outputTokens.toString(),
            observation.resources.retrievalTokens.toString(),
            observation.resources.contextTokens.toString(),
            observation.resources.toolCalls.toString(),
            observation.resources.toolRetries.toString(),
            observation.recordedLatency.ttftMicros.toWire(),
            observation.recordedLatency.toolToNextModelMicros.toWire(),
            observation.policy.candidateCount.toString(),
            observation.policy.compiledCount.toString(),
            observation.policy.dispatchCount.toString(),
            observation.policy.outcome.toWire(),
            observation.scopeLeakCount.toString(),
            observation.staleHitCount.toString(),
            observation.deterministicJudge.name,
            observation.humanJudge.name,
            observation.llmJudge.name,
            observation.scriptActionCount.toString(),
            observation.jvmTrend.operationUnits.toString(),
            observation.jvmTrend.logicalAllocationUnits.toString(),
        ).joinToString(WIRE_SEPARATOR)
    }

    fun decodeObservation(wire: String): DurableFourArmObservationRecord {
        val field = wire.split(WIRE_SEPARATOR)
        require(field.size == 31 && field[0] == OBSERVATION_WIRE_VERSION)
        val arm = OfflineEvalArm.valueOf(field[6])
        val observation = OfflineReplayObservation(
            unitId = field[1],
            arm = arm,
            taskOutcome = field[8].toBinaryObservation(),
            harmfulOutcome = field[9].toBinaryObservation(),
            userCorrectionCount = field[10].toInt(),
            resources = ReplayResourceObservation(
                inputTokens = field[11].toInt(),
                outputTokens = field[12].toInt(),
                retrievalTokens = field[13].toInt(),
                contextTokens = field[14].toInt(),
                toolCalls = field[15].toInt(),
                toolRetries = field[16].toInt(),
            ),
            recordedLatency = RecordedLatencyObservation(
                field[17].toNullableLong(),
                field[18].toNullableLong(),
            ),
            policy = PolicyFunnelObservation(
                candidateCount = field[19].toInt(),
                compiledCount = field[20].toInt(),
                dispatchCount = field[21].toInt(),
                outcome = field[22].toBinaryObservation(),
            ),
            scopeLeakCount = field[23].toInt(),
            staleHitCount = field[24].toInt(),
            deterministicJudge = JudgeVerdict.valueOf(field[25]),
            humanJudge = JudgeVerdict.valueOf(field[26]),
            llmJudge = JudgeVerdict.valueOf(field[27]),
            scriptActionCount = field[28].toInt(),
            jvmTrend = JvmTrendObservation(field[29].toLong(), field[30].toLong()),
        )
        return DurableFourArmObservationRecord(
            unitId = field[1],
            matchedCohortId = field[2],
            sliceDigestSha256 = field[3],
            primaryArm = OfflineEvalArm.valueOf(field[4]),
            partition = EvalPartition.valueOf(field[5]),
            arm = arm,
            authorityTraceDigestSha256 = field[7],
            observation = observation,
        )
    }

    fun encodeReceipt(row: DurableFourArmReceiptRecord): String = with(row.receipt) {
        listOf(
            RECEIPT_WIRE_VERSION,
            unitId,
            arm.name,
            component.name,
            state.name,
            abstainReason?.name ?: NONE,
            work.operationUnits.toString(),
            work.logicalAllocationUnits.toString(),
        ).joinToString(WIRE_SEPARATOR)
    }

    fun decodeReceipt(wire: String): DurableFourArmReceiptRecord {
        val field = wire.split(WIRE_SEPARATOR)
        require(field.size == 8 && field[0] == RECEIPT_WIRE_VERSION)
        return DurableFourArmReceiptRecord(
            ProductionComponentReplayReceipt(
                unitId = field[1],
                arm = OfflineEvalArm.valueOf(field[2]),
                component = ProductionReplayComponent.valueOf(field[3]),
                state = ProductionComponentReceiptState.valueOf(field[4]),
                abstainReason = field[5].takeUnless { it == NONE }
                    ?.let { ProductionComponentAbstainReason.valueOf(it) },
                work = DeterministicComponentWork(field[6].toLong(), field[7].toLong()),
            ),
        )
    }

    fun decodeEvidence(
        headerWire: String,
        authorityWires: List<String>,
        observationWires: List<String>,
        receiptWires: List<String>,
    ): DurableFourArmRuntimeEvidence {
        val field = headerWire.split(WIRE_SEPARATOR)
        require(field.size == 15 && field[0] == "p5-durable-four-arm-evidence-v2")
        return DurableFourArmRuntimeEvidence(
            schemaVersion = field[1].toInt(),
            contractId = field[2],
            manifestDigestSha256 = field[3],
            reportDigestSha256 = field[4],
            corpusDigestSha256 = field[5],
            planDigestSha256 = field[6],
            assignmentManifestSha256 = field[7],
            preRegistrationDigestSha256 = field[8],
            authorityManifestDigestSha256 = field[9],
            authoritySourceId = field[10],
            origin = DurableRuntimeEvidenceOrigin.valueOf(field[11]),
            judgeSources = DurableJudgeSourceIdentities(
                deterministicSourceId = field[12],
                humanSourceId = field[13],
                llmSourceId = field[14],
            ),
            authorityRecords = authorityWires.map(::decodeAuthority),
            observationRecords = observationWires.map(::decodeObservation),
            receiptRecords = receiptWires.map(::decodeReceipt),
        )
    }

    private const val AUTHORITY_WIRE_VERSION = "authority-v1"
    private const val OBSERVATION_WIRE_VERSION = "observation-v1"
    private const val RECEIPT_WIRE_VERSION = "receipt-v1"
    private const val NONE = "NONE"
}

private fun OfflineEvalSlice.digestSha256(): String = EvalDigest.sha256(
    "p5-durable-four-arm-slice-v1",
    dimensions().entries.sortedBy { it.key.ordinal }.flatMap { listOf(it.key.name, it.value) },
)

private fun OfflineReplayObservation.matchesAuthority(
    trace: DurableAuthorityTraceRecord,
    evaluatedArm: OfflineEvalArm,
): Boolean {
    val expectedPolicyOutcome = if (evaluatedArm.requiresReviewedPolicyForEvidence()) {
        trace.taskOutcome
    } else {
        BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
    }
    return taskOutcome == trace.taskOutcome &&
        harmfulOutcome == trace.harmfulOutcome &&
        userCorrectionCount == trace.userCorrectionCount &&
        resources.outputTokens == trace.outputTokens &&
        resources.toolCalls == trace.toolCalls &&
        resources.toolRetries == trace.toolRetries &&
        recordedLatency == trace.recordedLatency &&
        policy.outcome == expectedPolicyOutcome &&
        deterministicJudge == trace.deterministicJudge &&
        humanJudge == trace.humanJudge &&
        llmJudge == trace.llmJudge &&
        scriptActionCount == trace.scriptActionCount
}

private fun expectedReceiptState(
    arm: OfflineEvalArm,
    component: ProductionReplayComponent,
): ProductionComponentReceiptState = when (component) {
    ProductionReplayComponent.DREAM_PROJECTION ->
        if (arm == OfflineEvalArm.A_NO_LEARNING) {
            ProductionComponentReceiptState.SKIPPED_BY_ARM
        } else ProductionComponentReceiptState.OBSERVED
    ProductionReplayComponent.POLICY_RETRIEVAL,
    ProductionReplayComponent.POLICY_EXPOSURE,
    -> if (arm.requiresReviewedPolicyForEvidence()) {
        ProductionComponentReceiptState.OBSERVED
    } else ProductionComponentReceiptState.SKIPPED_BY_ARM
    ProductionReplayComponent.RECALL_COMPILER,
    ProductionReplayComponent.POLICY_OUTCOME,
    -> ProductionComponentReceiptState.OBSERVED
}

private fun OfflineEvalArm.requiresReviewedPolicyForEvidence(): Boolean =
    this == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
        this == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS

private fun OfflineEvalArm.runtimeCheck(): ProductionFourArmRuntimeCheck = when (this) {
    OfflineEvalArm.A_NO_LEARNING -> ProductionFourArmRuntimeCheck.A_NO_LEARNING_OBSERVED
    OfflineEvalArm.B_DREAMING_ONLY -> ProductionFourArmRuntimeCheck.B_DREAMING_ONLY_OBSERVED
    OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ->
        ProductionFourArmRuntimeCheck.C_DREAMING_REVIEWED_POLICY_OBSERVED
    OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS ->
        ProductionFourArmRuntimeCheck.D_FULL_REVIEWED_RUNTIME_NO_JS_OBSERVED
}

private fun BinaryObservation.toWire(): String = when (this) {
    is BinaryObservation.Observed -> if (value) "O:1" else "O:0"
    is BinaryObservation.Unknown -> "U:${reason.name}"
    is BinaryObservation.Censored -> "C:${reason.name}"
}

private fun String.toBinaryObservation(): BinaryObservation {
    val parts = split(':')
    require(parts.size == 2)
    return when (parts[0]) {
        "O" -> BinaryObservation.Observed(
            when (parts[1]) {
                "1" -> true
                "0" -> false
                else -> error("invalid binary observation")
            },
        )
        "U" -> BinaryObservation.Unknown(BinaryUnknownReason.valueOf(parts[1]))
        "C" -> BinaryObservation.Censored(BinaryCensorReason.valueOf(parts[1]))
        else -> error("invalid binary observation")
    }
}

private fun Long?.toWire(): String = this?.toString() ?: "N"
private fun String.toNullableLong(): Long? = takeUnless { it == "N" }?.toLong()

private const val WIRE_SEPARATOR = "|"
