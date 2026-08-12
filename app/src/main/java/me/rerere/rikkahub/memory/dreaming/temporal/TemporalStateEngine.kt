package me.rerere.rikkahub.memory.dreaming.temporal

object TemporalStateEngine {
    fun evaluate(request: TemporalTransitionRequest): TemporalProjection {
        val expiryState = if (
            request.expiresAtEpochMs != null &&
            request.expiresAtEpochMs <= request.frozenNowEpochMs
        ) {
            MemoryExpiryState.EXPIRED
        } else {
            MemoryExpiryState.NOT_EXPIRED
        }
        val parsed = request.parseResult
        if (strictZoneOrNull(request.timezoneId) == null) {
            return projection(
                state = TemporalState.UNKNOWN,
                reason = TemporalTransitionReason.INVALID_TIMEZONE_CONTEXT,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (
            parsed.frozenNowEpochMs != request.frozenNowEpochMs ||
            parsed.sourceTimestampEpochMs != request.sourceTimestampEpochMs ||
            parsed.timezoneId != request.timezoneId
        ) {
            return projection(
                state = TemporalState.UNKNOWN,
                reason = TemporalTransitionReason.PARSE_CONTEXT_MISMATCH,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (
            parsed is TemporalParseResult.Parsed &&
            parsed.anchorKind == TemporalAnchorKind.SOURCE_TIMESTAMP &&
            request.sourceTimestampEpochMs == null
        ) {
            return projection(
                state = TemporalState.UNKNOWN,
                reason = TemporalTransitionReason.RELATIVE_SOURCE_TIMESTAMP_REQUIRED,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (parsed is TemporalParseResult.Unknown) {
            return projection(
                state = TemporalState.UNKNOWN,
                reason = TemporalTransitionReason.PARSE_UNKNOWN,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (request.explicitOutcome == ExplicitTemporalOutcome.CONFLICTING) {
            return projection(
                state = TemporalState.UNKNOWN,
                reason = TemporalTransitionReason.CONFLICTING_EXPLICIT_OUTCOME,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (request.explicitOutcome == ExplicitTemporalOutcome.CANCELLED) {
            return projection(
                state = TemporalState.CANCELLED,
                reason = TemporalTransitionReason.EXPLICIT_CANCELLATION_EVIDENCE,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (request.explicitOutcome == ExplicitTemporalOutcome.COMPLETED) {
            return projection(
                state = TemporalState.HISTORICAL_CONFIRMED,
                reason = TemporalTransitionReason.EXPLICIT_COMPLETION_EVIDENCE,
                parsed = parsed,
                expiryState = expiryState,
            )
        }
        if (parsed is TemporalParseResult.Timeless) {
            return projection(
                state = TemporalState.TIMELESS,
                reason = TemporalTransitionReason.TIMELESS_NO_TEMPORAL_SIGNAL,
                parsed = parsed,
                expiryState = expiryState,
            )
        }

        parsed as TemporalParseResult.Parsed
        val (state, reason) = when {
            request.frozenNowEpochMs < parsed.window.startInclusiveEpochMs ->
                TemporalState.UPCOMING to TemporalTransitionReason.WINDOW_NOT_STARTED

            request.frozenNowEpochMs < parsed.window.endExclusiveEpochMs ->
                TemporalState.CURRENT to TemporalTransitionReason.WINDOW_ACTIVE

            else -> TemporalState.PAST_UNVERIFIED to
                TemporalTransitionReason.WINDOW_ENDED_WITHOUT_COMPLETION_EVIDENCE
        }
        return projection(state, reason, parsed, expiryState)
    }

    private fun projection(
        state: TemporalState,
        reason: TemporalTransitionReason,
        parsed: TemporalParseResult,
        expiryState: MemoryExpiryState,
    ) = TemporalProjection(
        state = state,
        reason = reason,
        parseReason = parsed.reason,
        window = (parsed as? TemporalParseResult.Parsed)?.window,
        memoryExpiryState = expiryState,
    )
}
