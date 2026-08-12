package me.rerere.rikkahub.memory.dreaming.temporal

const val MAX_TEMPORAL_EXPRESSION_LENGTH = 128

enum class TemporalState {
    TIMELESS,
    UPCOMING,
    CURRENT,
    PAST_UNVERIFIED,
    HISTORICAL_CONFIRMED,
    CANCELLED,
    UNKNOWN,
}

enum class TemporalPrecision {
    DATE,
    DATE_TIME,
    PART_OF_DAY,
    WEEK,
    MONTH,
    INTERVAL,
}

enum class TemporalAnchorKind {
    ABSOLUTE,
    SOURCE_TIMESTAMP,
}

enum class TemporalParseReason {
    NO_TEMPORAL_EXPRESSION,
    CHINESE_RELATIVE_PARSED,
    ISO_DATE_PARSED,
    ISO_DATE_TIME_PARSED,
    ISO_INTERVAL_PARSED,
    INVALID_TIMEZONE,
    RELATIVE_SOURCE_TIMESTAMP_REQUIRED,
    EXPRESSION_TOO_LONG,
    EXPRESSION_CONTAINS_CONTROL_CHARACTER,
    UNSUPPORTED_EXPRESSION,
    INVALID_ISO_DATE,
    INVALID_ISO_DATE_TIME,
    INVALID_ISO_INTERVAL,
    ISO_INTERVAL_NOT_FORWARD,
    DST_GAP_LOCAL_TIME,
    DST_OVERLAP_LOCAL_TIME,
    EPOCH_MILLIS_OUT_OF_RANGE,
}

data class TemporalParseRequest(
    val expression: String?,
    val frozenNowEpochMs: Long,
    val sourceTimestampEpochMs: Long?,
    val timezoneId: String,
)

/** All windows are half-open: [startInclusiveEpochMs, endExclusiveEpochMs). */
data class TemporalWindow(
    val startInclusiveEpochMs: Long,
    val endExclusiveEpochMs: Long,
) {
    init {
        require(endExclusiveEpochMs > startInclusiveEpochMs) {
            "Temporal window must be non-empty and forward"
        }
    }
}

sealed interface TemporalParseResult {
    val frozenNowEpochMs: Long
    val sourceTimestampEpochMs: Long?
    val timezoneId: String
    val reason: TemporalParseReason

    data class Parsed(
        override val frozenNowEpochMs: Long,
        override val sourceTimestampEpochMs: Long?,
        override val timezoneId: String,
        override val reason: TemporalParseReason,
        val window: TemporalWindow,
        val precision: TemporalPrecision,
        val anchorKind: TemporalAnchorKind,
    ) : TemporalParseResult

    data class Timeless(
        override val frozenNowEpochMs: Long,
        override val sourceTimestampEpochMs: Long?,
        override val timezoneId: String,
        override val reason: TemporalParseReason = TemporalParseReason.NO_TEMPORAL_EXPRESSION,
    ) : TemporalParseResult

    data class Unknown(
        override val frozenNowEpochMs: Long,
        override val sourceTimestampEpochMs: Long?,
        override val timezoneId: String,
        override val reason: TemporalParseReason,
    ) : TemporalParseResult
}

/** These outcomes must come from explicit, already-validated authority evidence. */
enum class ExplicitTemporalOutcome {
    NONE,
    COMPLETED,
    CANCELLED,
    CONFLICTING,
}

enum class TemporalTransitionReason {
    TIMELESS_NO_TEMPORAL_SIGNAL,
    WINDOW_NOT_STARTED,
    WINDOW_ACTIVE,
    WINDOW_ENDED_WITHOUT_COMPLETION_EVIDENCE,
    EXPLICIT_COMPLETION_EVIDENCE,
    EXPLICIT_CANCELLATION_EVIDENCE,
    CONFLICTING_EXPLICIT_OUTCOME,
    PARSE_UNKNOWN,
    PARSE_CONTEXT_MISMATCH,
    INVALID_TIMEZONE_CONTEXT,
    RELATIVE_SOURCE_TIMESTAMP_REQUIRED,
}

enum class MemoryExpiryState {
    NOT_EXPIRED,
    EXPIRED,
}

data class TemporalTransitionRequest(
    val parseResult: TemporalParseResult,
    val frozenNowEpochMs: Long,
    val sourceTimestampEpochMs: Long?,
    val timezoneId: String,
    val explicitOutcome: ExplicitTemporalOutcome = ExplicitTemporalOutcome.NONE,
    /** Memory retention/usability metadata; it never changes the event's temporal state. */
    val expiresAtEpochMs: Long? = null,
)

data class TemporalProjection(
    val state: TemporalState,
    val reason: TemporalTransitionReason,
    val parseReason: TemporalParseReason,
    val window: TemporalWindow?,
    val memoryExpiryState: MemoryExpiryState,
)
