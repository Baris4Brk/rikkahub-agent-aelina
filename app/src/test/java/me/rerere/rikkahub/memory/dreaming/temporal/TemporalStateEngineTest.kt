package me.rerere.rikkahub.memory.dreaming.temporal

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TemporalStateEngineTest {
    @Test
    fun `planned window moves upcoming current then past unverified without inventing completion`() {
        val parsed = parseDate("2026-08-13", frozenNow = "2026-08-12T00:00:00Z")

        assertEquals(
            TemporalTransitionReason.WINDOW_NOT_STARTED,
            assertState(parsed, "2026-08-12T00:00:00Z", TemporalState.UPCOMING).reason,
        )
        assertEquals(
            TemporalTransitionReason.WINDOW_ACTIVE,
            assertState(
                parsedForNow(parsed, "2026-08-13T00:00:00Z"),
                "2026-08-13T00:00:00Z",
                TemporalState.CURRENT,
            ).reason,
        )
        assertEquals(
            TemporalTransitionReason.WINDOW_ACTIVE,
            assertState(
                parsedForNow(parsed, "2026-08-13T12:00:00Z"),
                "2026-08-13T12:00:00Z",
                TemporalState.CURRENT,
            ).reason,
        )
        val past = assertState(
            parsedForNow(parsed, "2026-08-14T00:00:00Z"),
            "2026-08-14T00:00:00Z",
            TemporalState.PAST_UNVERIFIED,
        )
        assertEquals(
            TemporalTransitionReason.WINDOW_ENDED_WITHOUT_COMPLETION_EVIDENCE,
            past.reason,
        )
    }

    @Test
    fun `only explicit completion or cancellation evidence produces terminal outcome`() {
        val now = "2026-08-14T00:00:00Z"
        val parsed = parseDate("2026-08-13", frozenNow = now)

        val completed = evaluate(parsed, now, ExplicitTemporalOutcome.COMPLETED)
        assertEquals(TemporalState.HISTORICAL_CONFIRMED, completed.state)
        assertEquals(TemporalTransitionReason.EXPLICIT_COMPLETION_EVIDENCE, completed.reason)
        val cancelled = evaluate(parsed, now, ExplicitTemporalOutcome.CANCELLED)
        assertEquals(TemporalState.CANCELLED, cancelled.state)
        assertEquals(TemporalTransitionReason.EXPLICIT_CANCELLATION_EVIDENCE, cancelled.reason)
        val conflicting = evaluate(parsed, now, ExplicitTemporalOutcome.CONFLICTING)
        assertEquals(TemporalState.UNKNOWN, conflicting.state)
        assertEquals(TemporalTransitionReason.CONFLICTING_EXPLICIT_OUTCOME, conflicting.reason)
    }

    @Test
    fun `unknown parse remains unknown even when an outcome flag is supplied`() {
        val now = epoch("2026-08-12T00:00:00Z")
        val unknown = DeterministicTemporalParser.parse(
            TemporalParseRequest("下下周", now, now, "UTC"),
        )
        val result = TemporalStateEngine.evaluate(
            TemporalTransitionRequest(
                parseResult = unknown,
                frozenNowEpochMs = now,
                sourceTimestampEpochMs = now,
                timezoneId = "UTC",
                explicitOutcome = ExplicitTemporalOutcome.COMPLETED,
            ),
        )
        assertEquals(TemporalState.UNKNOWN, result.state)
        assertEquals(TemporalTransitionReason.PARSE_UNKNOWN, result.reason)
    }

    @Test
    fun `blank expression is timeless unless explicit valid outcome changes it`() {
        val now = epoch("2026-08-12T00:00:00Z")
        val timeless = DeterministicTemporalParser.parse(
            TemporalParseRequest(null, now, SOURCE_TIMESTAMP, "UTC"),
        )
        val projection = evaluate(timeless, "2026-08-12T00:00:00Z")
        assertEquals(TemporalState.TIMELESS, projection.state)
        assertEquals(TemporalTransitionReason.TIMELESS_NO_TEMPORAL_SIGNAL, projection.reason)
        assertEquals(
            TemporalState.HISTORICAL_CONFIRMED,
            evaluate(
                timeless,
                "2026-08-12T00:00:00Z",
                ExplicitTemporalOutcome.COMPLETED,
            ).state,
        )

        // A source timestamp is optional for a timeless expression, but parse/evaluate context
        // must still match exactly when one is supplied.
        val timelessWithoutSource = DeterministicTemporalParser.parse(
            TemporalParseRequest(null, now, null, "UTC"),
        )
        val withoutSourceProjection = TemporalStateEngine.evaluate(
            TemporalTransitionRequest(
                parseResult = timelessWithoutSource,
                frozenNowEpochMs = now,
                sourceTimestampEpochMs = null,
                timezoneId = "UTC",
            ),
        )
        assertEquals(TemporalState.TIMELESS, withoutSourceProjection.state)
        assertEquals(
            TemporalTransitionReason.TIMELESS_NO_TEMPORAL_SIGNAL,
            withoutSourceProjection.reason,
        )
    }

    @Test
    fun `memory expiry is reported separately and never means the event ended`() {
        val now = "2026-08-12T00:00:00Z"
        val parsed = parseDate("2026-08-13", frozenNow = now)
        val projection = TemporalStateEngine.evaluate(
            TemporalTransitionRequest(
                parseResult = parsed,
                frozenNowEpochMs = epoch(now),
                sourceTimestampEpochMs = SOURCE_TIMESTAMP,
                timezoneId = "UTC",
                expiresAtEpochMs = epoch("2026-08-11T00:00:00Z"),
            ),
        )

        assertEquals(TemporalState.UPCOMING, projection.state)
        assertEquals(MemoryExpiryState.EXPIRED, projection.memoryExpiryState)
    }

    @Test
    fun `transition rejects replay under different clock source timestamp or zone`() {
        val now = "2026-08-12T00:00:00Z"
        val parsed = parseDate("2026-08-13", frozenNow = now)
        val mismatches = listOf(
            TemporalTransitionRequest(parsed, epoch(now) + 1, SOURCE_TIMESTAMP, "UTC"),
            TemporalTransitionRequest(parsed, epoch(now), SOURCE_TIMESTAMP + 1, "UTC"),
            TemporalTransitionRequest(parsed, epoch(now), SOURCE_TIMESTAMP, "Asia/Shanghai"),
        )

        mismatches.forEach { request ->
            val projection = TemporalStateEngine.evaluate(request)
            assertEquals(TemporalState.UNKNOWN, projection.state)
            assertEquals(TemporalTransitionReason.PARSE_CONTEXT_MISMATCH, projection.reason)
        }
    }

    @Test
    fun `invalid zone and forged relative result fail closed`() {
        val now = epoch("2026-08-12T00:00:00Z")
        val invalidZone = TemporalParseResult.Timeless(
            frozenNowEpochMs = now,
            sourceTimestampEpochMs = null,
            timezoneId = "not/a-zone",
        )
        val invalidZoneProjection = TemporalStateEngine.evaluate(
            TemporalTransitionRequest(
                parseResult = invalidZone,
                frozenNowEpochMs = now,
                sourceTimestampEpochMs = null,
                timezoneId = "not/a-zone",
            ),
        )
        assertEquals(TemporalState.UNKNOWN, invalidZoneProjection.state)
        assertEquals(
            TemporalTransitionReason.INVALID_TIMEZONE_CONTEXT,
            invalidZoneProjection.reason,
        )

        val forgedRelative = TemporalParseResult.Parsed(
            frozenNowEpochMs = now,
            sourceTimestampEpochMs = null,
            timezoneId = "UTC",
            reason = TemporalParseReason.CHINESE_RELATIVE_PARSED,
            window = TemporalWindow(now + 1_000L, now + 2_000L),
            precision = TemporalPrecision.DATE,
            anchorKind = TemporalAnchorKind.SOURCE_TIMESTAMP,
        )
        val forgedRelativeProjection = TemporalStateEngine.evaluate(
            TemporalTransitionRequest(
                parseResult = forgedRelative,
                frozenNowEpochMs = now,
                sourceTimestampEpochMs = null,
                timezoneId = "UTC",
            ),
        )
        assertEquals(TemporalState.UNKNOWN, forgedRelativeProjection.state)
        assertEquals(
            TemporalTransitionReason.RELATIVE_SOURCE_TIMESTAMP_REQUIRED,
            forgedRelativeProjection.reason,
        )
    }

    @Test
    fun `identical transition inputs are deterministic across reruns`() {
        val now = "2026-08-13T12:00:00Z"
        val parsed = parseDate("2026-08-13", frozenNow = now)
        val request = TemporalTransitionRequest(
            parseResult = parsed,
            frozenNowEpochMs = epoch(now),
            sourceTimestampEpochMs = SOURCE_TIMESTAMP,
            timezoneId = "UTC",
        )
        assertEquals(
            TemporalStateEngine.evaluate(request),
            TemporalStateEngine.evaluate(request.copy()),
        )
    }

    private fun parseDate(expression: String, frozenNow: String): TemporalParseResult =
        DeterministicTemporalParser.parse(
            TemporalParseRequest(
                expression = expression,
                frozenNowEpochMs = epoch(frozenNow),
                sourceTimestampEpochMs = SOURCE_TIMESTAMP,
                timezoneId = "UTC",
            ),
        )

    /** Reparse is intentional: frozenNow is part of the deterministic parser contract. */
    private fun parsedForNow(parsed: TemporalParseResult, now: String): TemporalParseResult {
        val expression = when ((parsed as TemporalParseResult.Parsed).precision) {
            TemporalPrecision.DATE -> "2026-08-13"
            else -> error("test helper only supports date precision")
        }
        return parseDate(expression, now)
    }

    private fun assertState(
        parsed: TemporalParseResult,
        now: String,
        expected: TemporalState,
    ): TemporalProjection = evaluate(parsed, now).also { assertEquals(expected, it.state) }

    private fun evaluate(
        parsed: TemporalParseResult,
        now: String,
        outcome: ExplicitTemporalOutcome = ExplicitTemporalOutcome.NONE,
    ): TemporalProjection = TemporalStateEngine.evaluate(
        TemporalTransitionRequest(
            parseResult = parsed,
            frozenNowEpochMs = epoch(now),
            sourceTimestampEpochMs = SOURCE_TIMESTAMP,
            timezoneId = "UTC",
            explicitOutcome = outcome,
        ),
    )

    private fun epoch(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private companion object {
        val SOURCE_TIMESTAMP: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
    }
}
